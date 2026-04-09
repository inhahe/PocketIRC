package com.pocketirc.app.error

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists uncaught fatal crashes to a file in app-private storage so the
 * app can prompt the user to report them on the next launch.
 *
 * The file format is plain text:
 *   line 1: timestamp millis
 *   line 2: app version name
 *   line 3: device + Android version
 *   blank line
 *   rest:   the full stack trace, including caused-by chains
 *
 * The handler runs synchronously on the dying thread; it must not perform
 * any I/O that could block longer than a few hundred milliseconds.
 */
object CrashFile {

    private const val FILE_NAME = "last-crash.txt"

    /** Install the uncaught-exception handler. Call once at app startup. */
    fun install(context: Context, appVersion: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(context, appVersion, throwable)
            } catch (_: Throwable) {
                // Best-effort. If even writing the crash file fails, give up
                // gracefully and let the system handle the death.
            }
            // Chain to the previous handler (Android's default) so the system
            // still gets to record the crash and show its dialog.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, appVersion: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val body = buildString {
            append(System.currentTimeMillis()).append('\n')
            append(appVersion).append('\n')
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append('\n')
            append(sw.toString())
        }
        File(context.filesDir, FILE_NAME).writeText(body)
    }

    /** Returns the contents of the last-crash file, or null if none. */
    fun read(context: Context): String? {
        val f = File(context.filesDir, FILE_NAME)
        return if (f.exists()) f.readText() else null
    }

    /** Delete the last-crash file (call after the user dismisses or sends). */
    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
