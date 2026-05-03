package com.pocketirc.app.error

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
        /** A multi-line trace string suitable for embedding in an issue body.
         *  Unlike [Throwable.printStackTrace], this never abbreviates frames
         *  with "... N more". */
        fun fullTrace(): String = fullStackTrace(throwable)
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

/**
 * Formats the full exception chain without the "... N more" abbreviation
 * that [Throwable.printStackTrace] uses. Every frame is always printed.
 */
fun fullStackTrace(throwable: Throwable): String = buildString {
    var current: Throwable? = throwable
    var prefix = ""
    while (current != null) {
        append(prefix)
        append(current.javaClass.name)
        current.message?.let { append(": ").append(it) }
        append('\n')
        for (frame in current.stackTrace) {
            append("\tat ").append(frame).append('\n')
        }
        current = current.cause
        if (current != null) prefix = "Caused by: "
    }
}
