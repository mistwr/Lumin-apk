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
 */
object LocalRebornEngine {
    private const val MODEL_NAME = "qwen3-1.7b-int4.litertlm"
    private var engine: Engine? = null
    private var loadedPath: String? = null

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
    @Synchronized
    fun ensureReady(context: Context): String {
        val file = modelFile(context)
        if (!isInstalled(context)) throw IllegalStateException("Modelo local Qwen3 não instalado")
        if (engine != null && loadedPath == file.absolutePath) return "READY"
        close()
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath
        )
        val created = Engine(config)
        runBlocking { created.initialize() }
        engine = created
        loadedPath = file.absolutePath
        return "READY"
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
    }
}
