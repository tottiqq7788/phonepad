package cn.phonepad.logging

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object PhonePadLogger {
    private const val TAG = "PhonePad"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "phonepad-android.log"
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024
    private const val MAX_ROTATED_FILES = 5

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var debugBuild = false

    private val fileLock = Any()
    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .withZone(ZoneId.systemDefault())

    fun init(context: Context) {
        debugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        synchronized(fileLock) {
            logDir = dir
            rotateIfNeededLocked(dir)
        }
        i("app", "logger_init", "debug=$debugBuild")
    }

    fun d(module: String, event: String, fields: String = "") {
        if (debugBuild) {
            write(Log.DEBUG, module, event, fields)
        }
    }

    fun i(module: String, event: String, fields: String = "") {
        write(Log.INFO, module, event, fields)
    }

    fun w(module: String, event: String, fields: String = "", throwable: Throwable? = null) {
        write(Log.WARN, module, event, fields, throwable)
    }

    fun e(module: String, event: String, fields: String = "", throwable: Throwable? = null) {
        write(Log.ERROR, module, event, fields, throwable)
    }

    fun shortId(value: String): String =
        if (value.length <= 8) value else "${value.take(8)}…"

    private fun write(
        level: Int,
        module: String,
        event: String,
        fields: String,
        throwable: Throwable? = null,
    ) {
        val line = buildString {
            append(timestampFormatter.format(Instant.now()))
            append(' ')
            append(levelName(level))
            append(' ')
            append(module)
            append(' ')
            append(event)
            if (fields.isNotBlank()) {
                append(' ')
                append(fields)
            }
        }
        when (level) {
            Log.ERROR -> runCatching { Log.e(TAG, line, throwable) }
            Log.WARN -> runCatching { Log.w(TAG, line, throwable) }
            Log.DEBUG -> runCatching { Log.d(TAG, line, throwable) }
            else -> runCatching { Log.i(TAG, line, throwable) }
        }
        appendToFile(line, throwable)
    }

    private fun appendToFile(line: String, throwable: Throwable?) {
        val dir = logDir ?: return
        synchronized(fileLock) {
            rotateIfNeededLocked(dir)
            val file = File(dir, LOG_FILE)
            runCatching {
                FileWriter(file, true).use { writer ->
                    writer.appendLine(line)
                    if (throwable != null) {
                        writer.appendLine(Log.getStackTraceString(throwable))
                    }
                }
            }
        }
    }

    private fun rotateIfNeededLocked(dir: File) {
        val current = File(dir, LOG_FILE)
        if (!current.exists() || current.length() < MAX_LOG_BYTES) {
            return
        }
        for (index in MAX_ROTATED_FILES - 1 downTo 1) {
            val from = if (index == 1) current else File(dir, "$LOG_FILE.${index - 1}")
            val to = File(dir, "$LOG_FILE.$index")
            if (!from.exists()) {
                continue
            }
            if (to.exists()) {
                to.delete()
            }
            from.renameTo(to)
        }
    }

    private fun levelName(level: Int): String =
        when (level) {
            Log.ERROR -> "ERROR"
            Log.WARN -> "WARN"
            Log.DEBUG -> "DEBUG"
            else -> "INFO"
        }
}
