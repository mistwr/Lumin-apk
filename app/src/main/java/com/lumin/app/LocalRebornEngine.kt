package com.lumin.app

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Fully local REBORN brain. No OpenAI, no remote LLM endpoint and no Ollama server.
 * Default target model: Qwen3-1.7B INT4 LiteRT-LM.
 *
 * Performance strategy for calls:
 * - prefer Galaxy GPU/OpenCL, CPU only as fallback;
 * - keep the Engine loaded instead of reinitialising between turns;
 * - keep one Conversation alive during the call so KV/context can be reused;
 * - expose backend diagnostics so we can see whether GPU really engaged.
 */
object LocalRebornEngine {
    private const val MODEL_NAME = "qwen3-1.7b-int4.litertlm"
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedPath: String? = null
    private var activeBackend: String = "NONE"
    private var lastInitMs: Long = 0
    private var lastGenerationMs: Long = 0

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

    @JvmStatic fun backendName(): String = activeBackend
    @JvmStatic fun lastInitMs(): Long = lastInitMs
    @JvmStatic fun lastGenerationMs(): Long = lastGenerationMs

    @JvmStatic
    @Synchronized
    fun ensureReady(context: Context): String {
        val file = modelFile(context)
        if (!isInstalled(context)) throw IllegalStateException("Modelo local Qwen3 não instalado")
        if (engine != null && loadedPath == file.absolutePath) return "READY:$activeBackend"

        close()
        val started = System.currentTimeMillis()

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
            lastInitMs = System.currentTimeMillis() - started
            return "READY:GPU"
        } catch (_: Throwable) {
            try { engine?.close() } catch (_: Throwable) {}
            engine = null
            conversation = null
            loadedPath = null
            activeBackend = "NONE"
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
        lastInitMs = System.currentTimeMillis() - started
        return "READY:CPU"
    }

    /** Preload the runtime and create the call conversation before the customer speaks. */
    @JvmStatic
    @Synchronized
    fun warmUp(context: Context): String {
        ensureReady(context)
        ensureConversation()
        return "READY:$activeBackend"
    }

    private fun ensureConversation(): Conversation {
        conversation?.let { return it }
        val e = engine ?: throw IllegalStateException("Motor local indisponível")
        return e.createConversation().also { conversation = it }
    }

    /** Persistent call-session generation. This is the fast path used by QwenClient. */
    @JvmStatic
    @Synchronized
    fun generate(context: Context, prompt: String): String {
        ensureReady(context)
        val c = ensureConversation()
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        runBlocking {
            c.sendMessageAsync(prompt).collect { chunk -> out.append(chunk) }
        }
        lastGenerationMs = System.currentTimeMillis() - started
        return out.toString().trim()
    }

    /** One-shot inference for diagnostics, without contaminating the live call context. */
    @JvmStatic
    @Synchronized
    fun generateOneShot(context: Context, prompt: String): String {
        ensureReady(context)
        val e = engine ?: throw IllegalStateException("Motor local indisponível")
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        runBlocking {
            e.createConversation().use { c ->
                c.sendMessageAsync(prompt).collect { chunk -> out.append(chunk) }
            }
        }
        lastGenerationMs = System.currentTimeMillis() - started
        return out.toString().trim()
    }

    /** Start a fresh conversation at the beginning/end of each phone call. */
    @JvmStatic
    @Synchronized
    fun resetConversation() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
    }

    @JvmStatic
    @Synchronized
    fun close() {
        resetConversation()
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        loadedPath = null
        activeBackend = "NONE"
        lastInitMs = 0
        lastGenerationMs = 0
    }
}
