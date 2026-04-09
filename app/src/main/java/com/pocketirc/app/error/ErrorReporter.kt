package com.pocketirc.app.error

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Process-wide singleton that collects reportable errors and surfaces them
 * to the UI as snackbar/dialog events. Nothing is ever sent automatically —
 * the UI presents a "Report" button that the user can choose to tap, which
 * opens a pre-filled GitHub issue URL or an email client.
 *
 * Use [report] from try/catch blocks at every place where an exception would
 * otherwise silently break a feature (event collectors, hook dispatch, etc.).
 *
 * For uncaught fatal crashes, see [CrashFile] which writes a file at the
 * moment of death and is consumed on next launch.
 */
object ErrorReporter {

    /** A single reportable error event. */
    data class Event(
        val title: String,
        val context: String,
        val throwable: Throwable,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        /** A multi-line trace string suitable for embedding in an issue body. */
        fun fullTrace(): String {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Push an error to the UI. Safe to call from any thread or coroutine.
     * Returns the [Event] so callers can also log it locally if they want.
     */
    fun report(throwable: Throwable, context: String): Event {
        val title = throwable.javaClass.simpleName + ": " +
            (throwable.message?.take(120) ?: "(no message)")
        val ev = Event(title = title, context = context, throwable = throwable)
        _events.tryEmit(ev)
        // Also log to logcat so the trace is visible in `adb logcat` for
        // anyone running a debug build.
        android.util.Log.e("PocketIRC", "[$context] $title", throwable)
        return ev
    }
}
