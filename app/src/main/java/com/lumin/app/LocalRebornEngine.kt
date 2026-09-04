package com.lumin.app

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Fully local REBORN brain. No OpenAI, no remote LLM endpoint and no Ollama server.
 * Default target model: Qwen3-1.7B INT4 LiteRT-LM.
 * Prefer GPU on Galaxy for much faster startup/inference; fall back to CPU if GPU init fails.
 */
object LocalRebornEngine {
    private const val MODEL_NAME = "qwen3-1.7b-int4.litertlm"
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var activeBackend: String = "NONE"

    @JvmStatic
    fun modelFile(context: Context): File {
        val dir = File(context.filesDir, "reborn-models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_NAME)
    }

    @JvmStatic
    fun isInstalled(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > 100L * 1024L * 1024L
    }

    @JvmStatic
    fun backendName(): String = activeBackend

    @JvmStatic
    @Synchronized
    fun ensureReady(context: Context): String {
        val file = modelFile(context)
        if (!isInstalled(context)) throw IllegalStateException("Modelo local Qwen3 não instalado")
        if (engine != null && loadedPath == file.absolutePath) return "READY:$activeBackend"
        close()

        // First try the Galaxy GPU/OpenCL path. LiteRT-LM recommends GPU on Android
        // when the device exposes libOpenCL/libvndksupport. If anything fails, keep
        // the app usable by falling back to CPU automatically.
        try {
            val gpuConfig = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            val gpuEngine = Engine(gpuConfig)
            runBlocking { gpuEngine.initialize() }
            engine = gpuEngine
            loadedPath = file.absolutePath
            activeBackend = "GPU"
            return "READY:GPU"
        } catch (_: Throwable) {
            close()
        }

        val cpuConfig = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath
        )
        val cpuEngine = Engine(cpuConfig)
        runBlocking { cpuEngine.initialize() }
        engine = cpuEngine
        loadedPath = file.absolutePath
        activeBackend = "CPU"
        return "READY:CPU"
    }

    @JvmStatic
    @Synchronized
    fun generate(context: Context, prompt: String): String {
        ensureReady(context)
        val e = engine ?: throw IllegalStateException("Motor local indisponível")
        val out = StringBuilder()
        runBlocking {
            e.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).collect { chunk -> out.append(chunk) }
            }
        }
        return out.toString().trim()
    }

    @JvmStatic
    @Synchronized
    fun close() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
        loadedPath = null
        activeBackend = "NONE"
    }
}
