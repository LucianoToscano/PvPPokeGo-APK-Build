package com.lucianotoscano.pvppokego.data

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded, app-private diagnostic trail. Never reads another application's Logcat.
 * Deliberately records short event codes, not raw OCR, screenshots, player names,
 * passwords, or device identifiers. Disk I/O is off the battle/capture thread.
 */
object AppDiagnosticLog {
    private const val TAG = "PvPPokeGo"
    private const val MAX_FILE_BYTES = 128 * 1024L
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PvPPokeGo-diagnostics").apply { isDaemon = true }
    }

    @Volatile private var file: File? = null

    @Synchronized
    fun initialize(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "pvppokego-runtime-events.log")
        record("app", "diagnostic-log-ready")
    }

    /** Values passed here must be non-sensitive event codes/counters only. */
    fun record(category: String, event: String) {
        val safeCategory = category.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(36)
        val safeEvent = event.replace(Regex("[^a-zA-Z0-9_=;:,. /-]"), "_").take(160)
        val line = "${SystemClock.elapsedRealtime()}|$safeCategory|$safeEvent"
        Log.i(TAG, line)
        val destination = file ?: return
        runCatching {
            writer.execute {
                runCatching {
                    destination.appendText(line + "\n")
                    if (destination.length() > MAX_FILE_BYTES) {
                        val tail = destination.readLines().takeLast(400).joinToString("\n", postfix = "\n")
                        destination.writeText(tail.takeLast(MAX_FILE_BYTES.toInt()))
                    }
                }
            }
        }
    }

    fun exportRuntimeEvents(): String = runCatching {
        writer.submit<String> {
            val saved = file ?: return@submit "# Diagnóstico indisponível: aplicativo não iniciado.\n"
            if (saved.exists()) saved.readText().takeLast(MAX_FILE_BYTES.toInt())
            else "# Nenhum evento registrado.\n"
        }.get(2L, TimeUnit.SECONDS)
    }.getOrElse { "# Registro local indisponível nesta exportação.\n" }

    /**
     * Best-effort access to this app's OWN process messages only.
     * Android may deny or omit them; no READ_LOGS permission or root is requested.
     * This is never an Android-wide logcat or a substitute for adb bugreport.
     */
    fun exportOwnLogcat(): String {
        val process = runCatching {
            ProcessBuilder(
                "logcat", "-d", "-t", "100", "-v", "threadtime",
                "--pid=${Process.myPid()}"
            ).redirectErrorStream(true).start()
        }.getOrElse { return "# Logcat do próprio processo indisponível: ${it.javaClass.simpleName}\n" }
        return try {
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "# Logcat indisponível (tempo limite). Veja runtime-events.log.\n"
            } else {
                // Filter the process output again by our dedicated tag to avoid library logs.
                val own = process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().filter { it.contains("PvPPokeGo") }.take(100).toList()
                }
                if (own.isEmpty()) "# Sem entradas do processo disponíveis pelo Android.\n"
                else own.joinToString("\n", postfix = "\n").takeLast(24_000)
            }
        } catch (e: Exception) {
            process.destroy()
            "# Logcat indisponível: ${e.javaClass.simpleName}.\n"
        }
    }
}
