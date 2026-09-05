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
 *
 * Current call-build priority is stability on the Galaxy S26 Ultra. The previous GPU-first
 * path could leave LiteRT-LM half initialised after an Adreno/OpenCL failure and the next
 * createConversation() returned FAILED_PRECONDITION. Until GPU is proven cleanly on-device,
 * REBORN uses the CPU backend and automatically rebuilds the engine once if conversation
 * creation fails.
 */
object LocalRebornEngine {
    private const val MODEL_NAME = "qwen3-1.7b-int4.litertlm"
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedPath: String? = null
    private var activeBackend: String = "NONE"
    private var lastInitMs: Long = 0
    private var lastGenerationMs: Long = 0
    private var lastError: String = ""

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
    @JvmStatic fun lastError(): String = lastError

    @JvmStatic
    @Synchronized
    fun ensureReady(context: Context): String {
        val file = modelFile(context)
        if (!isInstalled(context)) throw IllegalStateException("Modelo local Qwen3 não instalado")
        if (engine != null && loadedPath == file.absolutePath) return "READY:$activeBackend"

        closeEngineOnly()
        val started = System.currentTimeMillis()
        lastError = ""

        val cpuConfig = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath
        )
        val cpuEngine = Engine(cpuConfig)
        runBlocking { cpuEngine.initialize() }
        engine = cpuEngine
        loadedPath = file.absolutePath
        activeBackend = "CPU_STABLE"
        lastInitMs = System.currentTimeMillis() - started
        return "READY:$activeBackend"
    }

    @JvmStatic
    @Synchronized
    fun warmUp(context: Context): String {
        ensureReady(context)
        ensureConversationRecovering(context)
        return "READY:$activeBackend"
    }

    private fun ensureConversationRecovering(context: Context): Conversation {
        conversation?.let { return it }
        var e = engine ?: throw IllegalStateException("Motor local indisponível")
        try {
            return e.createConversation().also { conversation = it }
        } catch (first: Throwable) {
            lastError = "createConversation: ${first.javaClass.simpleName}: ${first.message ?: ""}"
            // One hard reset is safer than leaving a native LiteRT-LM engine in an invalid state.
            closeEngineOnly()
            ensureReady(context)
            e = engine ?: throw IllegalStateException("Motor local indisponível após reinício")
            return try {
                e.createConversation().also { conversation = it }
            } catch (second: Throwable) {
                lastError = "createConversation retry: ${second.javaClass.simpleName}: ${second.message ?: ""}"
                throw second
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun generate(context: Context, prompt: String): String {
        ensureReady(context)
        val c = ensureConversationRecovering(context)
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        runBlocking {
            c.sendMessageAsync(prompt).collect { chunk -> out.append(chunk) }
        }
        lastGenerationMs = System.currentTimeMillis() - started
        return out.toString().trim()
    }

    /** Diagnostics use the same persistent conversation path now: on this LiteRT-LM build,
     * repeatedly creating disposable conversations can itself trigger FAILED_PRECONDITION.
     */
    @JvmStatic
    @Synchronized
    fun generateOneShot(context: Context, prompt: String): String {
        resetConversation()
        val answer = generate(context, prompt)
        resetConversation()
        return answer
    }

    @JvmStatic
    @Synchronized
    fun resetConversation() {
        try { conversation?.close() } catch (_: Throwable) {}
        conversation = null
    }

    private fun closeEngineOnly() {
        resetConversation()
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        loadedPath = null
        activeBackend = "NONE"
    }

    @JvmStatic
    @Synchronized
    fun close() {
        closeEngineOnly()
        lastInitMs = 0
        lastGenerationMs = 0
        lastError = ""
    }
}
