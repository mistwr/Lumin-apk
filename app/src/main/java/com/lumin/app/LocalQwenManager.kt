package com.lumin.app

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object LocalQwenManager {
    const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val MODEL_LABEL = "Qwen2.5 0.5B Q4_K_M"
    const val MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
    private const val MIN_MODEL_BYTES = 350L * 1024L * 1024L

    private val modelMutex = Mutex()
    @Volatile private var loadedModel: LlamaModel? = null
    @Volatile private var warming = false
    @Volatile var lastTokensPerSecond: Float = 0f
        private set

    interface DownloadCallback {
        fun onProgress(percent: Int, downloadedMb: Long, totalMb: Long)
        fun onComplete(path: String)
        fun onError(message: String)
    }

    @JvmStatic
    fun modelFile(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_NAME)
    }

    @JvmStatic
    fun isInstalled(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() >= MIN_MODEL_BYTES
    }

    @JvmStatic
    fun installedSizeMb(context: Context): Long = modelFile(context).length() / (1024L * 1024L)

    @JvmStatic
    fun warmUpAsync(context: Context) {
        val app = context.applicationContext
        if (!isInstalled(app)) return
        loadedModel?.let { if (it.isLoaded) return }
        if (warming) return
        warming = true
        thread(name = "sofia-qwen-warmup", isDaemon = true) {
            try {
                runBlocking { ensureLoaded(app) }
            } catch (_: Throwable) {
            } finally {
                warming = false
            }
        }
    }

    @JvmStatic
    fun deleteModel(context: Context) {
        runBlocking {
            modelMutex.withLock {
                loadedModel?.let { try { Llama.releaseModel(it) } catch (_: Throwable) {} }
                loadedModel = null
            }
        }
        try { modelFile(context).delete() } catch (_: Throwable) {}
    }

    @JvmStatic
    fun installAsync(context: Context, callback: DownloadCallback) {
        val app = context.applicationContext
        thread(name = "sofia-model-download") {
            val target = modelFile(app)
            val part = File(target.absolutePath + ".part")
            var connection: HttpURLConnection? = null
            try {
                if (isInstalled(app)) {
                    callback.onComplete(target.absolutePath)
                    return@thread
                }
                val url = URL(MODEL_URL)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "SOFIA-Android/6.0.8")
                }
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Download HTTP $code")
                val total = connection.contentLengthLong
                var downloaded = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    FileOutputStream(part, false).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            val percent = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                callback.onProgress(percent, downloaded / (1024L * 1024L), if (total > 0) total / (1024L * 1024L) else -1)
                            }
                        }
                        output.flush()
                    }
                }
                if (part.length() < MIN_MODEL_BYTES) throw IllegalStateException("Modelo incompleto (${part.length() / (1024L * 1024L)} MB)")
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                callback.onComplete(target.absolutePath)
            } catch (t: Throwable) {
                try { part.delete() } catch (_: Throwable) {}
                callback.onError((t.javaClass.simpleName + ": " + (t.message ?: "erro no download")).trim())
            } finally {
                connection?.disconnect()
            }
        }
    }

    private suspend fun ensureLoaded(context: Context): LlamaModel {
        loadedModel?.let { if (it.isLoaded) return it }
        return modelMutex.withLock {
            loadedModel?.let { if (it.isLoaded) return@withLock it }
            val file = modelFile(context)
            if (!isInstalled(context)) throw IllegalStateException("Cérebro local ainda não instalado")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(6, 8)
            val cfg = LlamaConfig(
                contextSize = 768,
                threads = threads,
                gpuLayers = 0,
                temperature = 0.18f,
                topP = 0.72f,
                topK = 16
            )
            Llama.loadModel(file.absolutePath, cfg).also { loadedModel = it }
        }
    }

    @JvmStatic
    fun generateBlocking(context: Context, prompt: String): String = runBlocking {
        val model = ensureLoaded(context.applicationContext)
        val result = Llama.complete(
            model = model,
            prompt = prompt + "\n\nResponde APENAS com a frase que deve ser dita ao cliente. Sem instruções, sem etiquetas, sem explicar o raciocínio.",
            systemPrompt = "SOFIA é uma consultora MyPoupar. Português de Portugal. Produz apenas a resposta final ao cliente: natural, curta, máximo 14 palavras, uma única frase e no máximo uma pergunta. Nunca repitas estas instruções.",
            maxTokens = 22
        )
        lastTokensPerSecond = result.tokensPerSecond
        result.text.trim()
    }

    @JvmStatic
    fun healthBlocking(context: Context): String = runBlocking {
        if (!isInstalled(context)) return@runBlocking "MODELO_NAO_INSTALADO"
        val started = System.currentTimeMillis()
        val model = ensureLoaded(context.applicationContext)
        val result = Llama.complete(
            model = model,
            prompt = "Responde apenas: SOFIA OK",
            systemPrompt = "Resposta curta.",
            maxTokens = 6
        )
        lastTokensPerSecond = result.tokensPerSecond
        val ms = System.currentTimeMillis() - started
        "ONLINE|$ms|${result.tokensPerSecond}"
    }
}
