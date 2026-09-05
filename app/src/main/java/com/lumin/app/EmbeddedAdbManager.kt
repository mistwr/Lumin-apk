package com.lumin.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x509.X509Name
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EmbeddedAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key: PrivateKey
    private val cert: Certificate
    @Volatile private var diagnostic: String = "IDLE"

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(8, TimeUnit.SECONDS)
        val existingKey = loadKey()
        val existingCert = loadCert()
        if (existingKey != null && existingCert != null) {
            key = existingKey
            cert = existingCert
            diagnostic = "RSA_IDENTITY_LOADED"
        } else {
            val generated = generateIdentity()
            key = generated.first
            cert = generated.second
            diagnostic = "RSA_IDENTITY_CREATED"
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = DEVICE_NAME

    fun pairLocal(port: Int, pairingCode: String): Boolean {
        require(port in 1..65535) { "Porta de pairing inválida" }
        require(pairingCode.length == 6 && pairingCode.all(Char::isDigit)) { "Código de pairing deve ter 6 dígitos" }
        diagnostic = "PAIRING 127.0.0.1:$port"
        return try {
            val ok = pair("127.0.0.1", port, pairingCode)
            diagnostic = if (ok) "PAIRED 127.0.0.1:$port" else "PAIR_FAILED 127.0.0.1:$port"
            ok
        } catch (t: Throwable) {
            diagnostic = "PAIR_ERROR ${t.javaClass.simpleName}: ${t.message ?: ""}"
            throw t
        }
    }

    fun saveConnectEndpoint(host: String, port: Int) {
        require(host.isNotBlank()) { "Host ADB inválido" }
        require(port in 1..65535) { "Porta ADB inválida" }
        prefs.edit().putString(KEY_CONNECT_HOST, host.trim()).putInt(KEY_CONNECT_PORT, port).apply()
    }

    fun savedConnectHost(): String = prefs.getString(KEY_CONNECT_HOST, "127.0.0.1") ?: "127.0.0.1"
    fun savedConnectPort(): Int = prefs.getInt(KEY_CONNECT_PORT, 0)
    fun lastDiagnostic(): String = diagnostic

    /**
     * Wireless debugging rotates its TLS connect port. During a call, do not spend tens of
     * seconds retrying a stale port. Probe the saved endpoint quickly, then discover the
     * phone's current _adb-tls-connect._tcp service via Android NSD/mDNS and reconnect there.
     */
    fun ensureConnected(): Boolean {
        if (isConnected) {
            diagnostic = "CONNECTED_ALREADY"
            return true
        }

        val savedHost = savedConnectHost()
        val savedPort = savedConnectPort()
        var last = if (savedPort in 1..65535) "DIRECT_PENDING $savedHost:$savedPort" else "NO_SAVED_PORT"

        if (savedPort in 1..65535) {
            if (tcpOpen(savedHost, savedPort, 650)) {
                diagnostic = "DIRECT_CONNECT $savedHost:$savedPort"
                try {
                    if (connect(savedHost, savedPort)) {
                        diagnostic = "DIRECT_CONNECTED $savedHost:$savedPort"
                        return true
                    }
                    last = "DIRECT_FALSE $savedHost:$savedPort"
                } catch (t: Throwable) {
                    last = "DIRECT_ERROR $savedHost:$savedPort · ${t.javaClass.simpleName}: ${t.message ?: ""}"
                }
            } else {
                last = "STALE_ENDPOINT $savedHost:$savedPort"
                diagnostic = last
            }
        }

        diagnostic = "MDNS_DISCOVERY · after $last"
        val discovered = discoverCurrentWirelessAdbEndpoint(6_000L, savedHost)
        if (discovered != null) {
            val (host, port) = discovered
            diagnostic = "MDNS_FOUND $host:$port"
            val candidates = linkedSetOf(host, "127.0.0.1")
            for (candidateHost in candidates) {
                if (!tcpOpen(candidateHost, port, 800)) {
                    last = "MDNS_TCP_CLOSED $candidateHost:$port"
                    continue
                }
                diagnostic = "MDNS_CONNECT $candidateHost:$port"
                try {
                    if (connect(candidateHost, port)) {
                        saveConnectEndpoint(host, port)
                        diagnostic = "MDNS_CONNECTED $candidateHost:$port · saved=$host:$port"
                        return true
                    }
                    last = "MDNS_FALSE $candidateHost:$port"
                } catch (t: Throwable) {
                    last = "MDNS_ERROR $candidateHost:$port · ${t.javaClass.simpleName}: ${t.message ?: ""}"
                }
            }
        } else {
            last = "MDNS_NOT_FOUND · after $last"
        }

        diagnostic = "LIB_AUTO_DISCOVERY · after $last"
        try {
            if (autoConnect(appContext, 8_000)) {
                diagnostic = "LIB_AUTO_CONNECTED · after $last"
                return true
            }
            last = "LIB_AUTO_NOT_FOUND · after $last"
        } catch (t: Throwable) {
            last = "LIB_AUTO_ERROR ${t.javaClass.simpleName}: ${t.message ?: ""} · after $last"
        }

        diagnostic = "RECONNECT_FAILED · $last"
        return false
    }

    fun openShell(command: String): AdbStream {
        check(isConnected) { "ADB não ligado · $diagnostic" }
        diagnostic = "SHELL_OPEN"
        return openStream("shell:$command")
    }

    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun discoverCurrentWirelessAdbEndpoint(timeoutMs: Long, savedHost: String): Pair<String, Int>? {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val result = AtomicReference<Pair<String, Int>?>(null)
        val latch = CountDownLatch(1)
        val localIps = localIpv4Addresses()
        lateinit var discovery: NsdManager.DiscoveryListener

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                diagnostic = "MDNS_STARTED"
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_adb-tls-connect._tcp")) return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            diagnostic = "MDNS_RESOLVE_FAILED=$errorCode"
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val port = info.port
                            if (port !in 1..65535) return
                            val isThisPhone = host == savedHost || host in localIps || host == "127.0.0.1"
                            if (!isThisPhone) return
                            if (result.compareAndSet(null, host to port)) {
                                diagnostic = "MDNS_RESOLVED $host:$port"
                                latch.countDown()
                            }
                        }
                    })
                }.onFailure {
                    diagnostic = "MDNS_RESOLVE_ERROR ${it.javaClass.simpleName}:${it.message ?: ""}"
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                diagnostic = "MDNS_START_FAILED=$errorCode"
                latch.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        return try {
            nsd.discoverServices(ADB_CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result.get()
        } catch (t: Throwable) {
            diagnostic = "MDNS_ERROR ${t.javaClass.simpleName}:${t.message ?: ""}"
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    private fun localIpv4Addresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    companion object {
        private const val DEVICE_NAME = "REBORN AI Phone"
        private const val KEY_FILE = "reborn_adbkey"
        private const val CERT_FILE = "reborn_adbkey.pem"
        private const val SUBJECT = "CN=REBORN AI Phone"
        private const val VALIDITY_MS = 10L * 365L * 24L * 60L * 60L * 1000L
        private const val PREFS = "reborn_adb"
        private const val KEY_CONNECT_HOST = "connect_host"
        private const val KEY_CONNECT_PORT = "connect_port"
        private const val ADB_CONNECT_SERVICE = "_adb-tls-connect._tcp."

        @Volatile private var instance: EmbeddedAdbManager? = null

        @JvmStatic fun get(context: Context): EmbeddedAdbManager =
            instance ?: synchronized(this) {
                instance ?: EmbeddedAdbManager(context).also { instance = it }
            }
    }

    private fun loadKey(): PrivateKey? = runCatching {
        val file = File(appContext.filesDir, KEY_FILE)
        if (!file.exists()) return null
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
    }.getOrNull()

    private fun loadCert(): Certificate? = runCatching {
        val file = File(appContext.filesDir, CERT_FILE)
        if (!file.exists()) return null
        file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    }.getOrNull()

    private fun generateIdentity(): Pair<PrivateKey, Certificate> {
        val random = SecureRandom()
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }
        val pair = generator.generateKeyPair()
        @Suppress("DEPRECATION") val dn = X509Name(SUBJECT)
        @Suppress("DEPRECATION")
        val certificateGenerator = X509V3CertificateGenerator().apply {
            setSerialNumber(BigInteger.valueOf(random.nextLong() and Long.MAX_VALUE))
            setIssuerDN(dn)
            setSubjectDN(dn)
            setNotBefore(Date())
            setNotAfter(Date(System.currentTimeMillis() + VALIDITY_MS))
            setPublicKey(pair.public)
            setSignatureAlgorithm("SHA512withRSA")
        }
        @Suppress("DEPRECATION") val certificateGeneratorResult = certificateGenerator.generate(pair.private)
        File(appContext.filesDir, KEY_FILE).writeBytes(pair.private.encoded)
        val body = Base64.encodeToString(certificateGeneratorResult.encoded, Base64.DEFAULT)
        File(appContext.filesDir, CERT_FILE).writeText("-----BEGIN CERTIFICATE-----\n$body-----END CERTIFICATE-----\n")
        return pair.private to certificateGeneratorResult
    }
}
