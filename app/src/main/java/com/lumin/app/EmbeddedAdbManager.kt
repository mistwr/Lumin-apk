package com.lumin.app

import android.content.Context
import android.os.Build
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x509.X509Name
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

class EmbeddedAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(20, TimeUnit.SECONDS)
        val existingKey = loadKey()
        val existingCert = loadCert()
        if (existingKey != null && existingCert != null) {
            key = existingKey
            cert = existingCert
        } else {
            val generated = generateIdentity()
            key = generated.first
            cert = generated.second
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = DEVICE_NAME

    fun pairLocal(port: Int, pairingCode: String): Boolean {
        require(port in 1..65535) { "Porta de pairing inválida" }
        require(pairingCode.length == 6 && pairingCode.all(Char::isDigit)) { "Código de pairing deve ter 6 dígitos" }
        return pair("127.0.0.1", port, pairingCode)
    }

    fun saveConnectEndpoint(host: String, port: Int) {
        require(host.isNotBlank()) { "Host ADB inválido" }
        require(port in 1..65535) { "Porta ADB inválida" }
        prefs.edit().putString(KEY_CONNECT_HOST, host.trim()).putInt(KEY_CONNECT_PORT, port).apply()
    }

    fun savedConnectHost(): String = prefs.getString(KEY_CONNECT_HOST, "127.0.0.1") ?: "127.0.0.1"
    fun savedConnectPort(): Int = prefs.getInt(KEY_CONNECT_PORT, 0)

    fun ensureConnected(): Boolean {
        if (isConnected) return true
        val host = savedConnectHost()
        val port = savedConnectPort()
        if (port in 1..65535) {
            val direct = runCatching { connect(host, port) }.getOrDefault(false)
            if (direct) return true
        }
        return runCatching { autoConnect(appContext, 15_000) }.getOrDefault(false)
    }

    fun openShell(command: String): AdbStream {
        check(isConnected) { "ADB não ligado" }
        return openStream("shell:$command")
    }

    companion object {
        private const val DEVICE_NAME = "REBORN AI Phone"
        private const val KEY_FILE = "reborn_adbkey"
        private const val CERT_FILE = "reborn_adbkey.pem"
        private const val SUBJECT = "CN=REBORN AI Phone"
        private const val VALIDITY_MS = 10L * 365L * 24L * 60L * 60L * 1000L
        private const val PREFS = "reborn_adb"
        private const val KEY_CONNECT_HOST = "connect_host"
        private const val KEY_CONNECT_PORT = "connect_port"

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
        @Suppress("DEPRECATION") val certificate = certificateGenerator.generate(pair.private)
        File(appContext.filesDir, KEY_FILE).writeBytes(pair.private.encoded)
        val body = Base64.encodeToString(certificate.encoded, Base64.DEFAULT)
        File(appContext.filesDir, CERT_FILE).writeText("-----BEGIN CERTIFICATE-----\n$body-----END CERTIFICATE-----\n")
        return pair.private to certificate
    }
}
