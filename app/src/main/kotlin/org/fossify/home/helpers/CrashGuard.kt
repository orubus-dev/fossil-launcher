package org.fossify.home.helpers

import android.content.Context
import android.content.Intent
import android.os.Build
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fossil crash capture.
 *
 * The launcher used to "keep closing" with nothing left behind. This guard
 * installs itself as the process-wide uncaught-exception handler and writes
 * every crash (full stack trace + device/build context) to
 * files/crash/last_crash.txt before the process dies. On the next start
 * MainActivity finds the file and offers to share the log, so a crash can
 * finally be diagnosed from the actual stack trace.
 *
 * The previous handler always runs afterwards, so the system crash dialog /
 * ANR pipeline behaves exactly as before.
 */
object CrashGuard {

    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"
    private const val MAX_BYTES = 512 * 1024

    @Volatile
    private var installed = false

    fun install(appContext: Context) {
        if (installed) {
            return
        }
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext.applicationContext, thread, throwable)
            } catch (_: Throwable) {
                // never let the guard itself break crash reporting
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(appContext: Context, thread: Thread, throwable: Throwable) {
        val dir = File(appContext.filesDir, CRASH_DIR).apply { mkdirs() }
        val file = File(dir, CRASH_FILE)

        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val report = buildString {
            appendLine("Fossil crash report — $timestamp")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("app: v${try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName } catch (_: Exception) { "?" }}")
            appendLine()
            append(trace.take(MAX_BYTES))
        }

        // keep one rotating file pair so a crash during startup is never lost
        val previousCrash = File(dir, "last_crash.1.txt")
        if (file.exists()) {
            previousCrash.delete()
            file.copyTo(previousCrash, overwrite = true)
        }
        file.writeText(report)
    }

    /** The pending crash file, or null when the last run ended cleanly. */
    fun pendingCrashFile(context: Context): File? {
        val file = File(File(context.filesDir, CRASH_DIR), CRASH_FILE)
        return if (file.exists() && file.length() > 0) file else null
    }

    /** Move the handled crash out of the way once the user acted on it. */
    fun clearPendingCrash(context: Context) {
        ensureBackgroundThread {
            try {
                val dir = File(context.filesDir, CRASH_DIR)
                File(dir, CRASH_FILE).delete()
                File(dir, "last_crash.1.txt").delete()
            } catch (_: Exception) {
            }
        }
    }

    /** Build the share intent for a crash log (text/plain attachment). */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Fossil crash log")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share crash log"
        )
    }
}
