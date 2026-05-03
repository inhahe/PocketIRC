package com.pocketirc.app.irc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * In-memory registry of [OnHook]s plus matcher and dispatcher.
 *
 * Lifecycle: a single instance is owned by [ConnectionManager] (or whichever
 * thing routes IrcEvents). [load] is called at startup with the JSON blob from
 * settings; [serialize] is called whenever a hook is added/removed so the
 * caller can persist it back.
 */
class OnHookEngine {

    private val _hooks = MutableStateFlow<List<OnHook>>(emptyList())
    val hooks: StateFlow<List<OnHook>> = _hooks

    private val json = Json { ignoreUnknownKeys = true }

    fun load(serialized: String) {
        if (serialized.isBlank()) { _hooks.value = emptyList(); return }
        _hooks.value = runCatching {
            json.decodeFromString<List<OnHook>>(serialized)
        }.getOrDefault(emptyList())
    }

    fun serialize(): String =
        json.encodeToString(kotlinx.serialization.serializer(), _hooks.value)

    /** Add or replace a hook (matched by name + event). Returns the (possibly auto-generated) name. */
    fun add(hook: OnHook): String {
        val cur = _hooks.value
        val withName = if (hook.name.isBlank())
            hook.copy(name = autoName(hook.event, cur))
        else hook
        _hooks.value = cur.filterNot {
            it.event == withName.event && it.name == withName.name
        } + withName
        return withName.name
    }

    /** Remove a hook by event + name. Returns true if anything was removed. */
    fun remove(event: String, name: String): Boolean {
        val cur = _hooks.value
        val next = cur.filterNot { it.event == event && it.name == name }
        val changed = next.size != cur.size
        if (changed) _hooks.value = next
        return changed
    }

    /** Returns all hooks (optionally filtered by event), sorted for stable display. */
    fun list(event: String? = null): List<OnHook> {
        val all = _hooks.value
        val filt = if (event == null) all else all.filter { it.event == event }
        return filt.sortedWith(compareBy({ it.event }, { it.name }))
    }

    private fun autoName(event: String, existing: List<OnHook>): String {
        val taken = existing.filter { it.event == event }.map { it.name }.toSet()
        var i = 1
        while ("$event$i" in taken) i++
        return "$event$i"
    }

    // -------- Dispatch --------

    /**
     * Try every matching hook for [event] and run [executor] with the expanded
     * action string for each. Returns the number of hooks fired.
     */
    /** Result of [dispatch]: how many hooks fired and whether any of them
     *  asked to suppress the default handling of the event. */
    data class Result(val fired: Int, val suppress: Boolean, val quiet: Boolean = false)

    fun dispatch(
        event: String,
        networkName: String,
        vars: Map<String, String>,
        executor: (commandLine: String) -> Unit,
        notifier: ((title: String, body: String, channel: String?) -> Unit)? = null,
    ): Result {
        var fired = 0
        var suppress = false
        var quiet = false
        for (h in _hooks.value) {
            try {
            if (h.event != event) continue
            if (h.networkFilter != null &&
                !h.networkFilter.equals(networkName, ignoreCase = true)) continue
            val ch = vars["channel"]
            if (h.channelFilter != null) {
                if (ch == null || !ch.equals(h.channelFilter, ignoreCase = true)) continue
            }
            val nick = vars["nick"]
            val user = vars["user"]
            if (h.nickMaskFilter != null) {
                val candidate = user ?: nick ?: continue
                if (!matchMask(candidate, h.nickMaskFilter)) continue
            }
            val text = vars["text"] ?: vars["message"] ?: ""
            if (h.textPattern != null && !matchPattern(text, h.textPattern)) continue

            val merged = vars + ("server" to networkName)
            // Side effects (notification) before the action so the user sees
            // the alert even if the command errors out.
            if ((h.desktop || h.channel != null) && notifier != null) {
                val title = buildString {
                    append("[$networkName")
                    if (!ch.isNullOrBlank()) append(" $ch")
                    append("] $event")
                }
                val body = if (text.isNotEmpty()) {
                    if (nick != null) "<$nick> $text" else text
                } else {
                    expand(h.action, merged)
                }
                notifier(title, body, h.channel)
            }
            if (h.action.isNotBlank()) {
                executor(expand(h.action, merged))
            }
            if (h.suppressDefault) suppress = true
            if (h.suppressNotification) quiet = true
            fired++
            } catch (t: Throwable) {
                com.pocketirc.app.error.ErrorReporter.report(
                    t, "OnHookEngine hook=${h.name} event=$event")
            }
        }
        return Result(fired, suppress, quiet)
    }

    companion object {
        /** Wildcard matcher: '*' = any run, '?' = single char. Case-insensitive. */
        fun matchMask(candidate: String, mask: String): Boolean {
            val pat = "^" +
                Regex.escape(mask)
                    .replace("\\*", ".*")
                    .replace("\\?", ".") +
                "$"
            return Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(candidate)
        }

        /**
         * Text pattern matcher. `/regex/[ims]` runs as a regex with optional
         * flags; otherwise treated as a glob (`*` and `?`).
         */
        fun matchPattern(text: String, pattern: String): Boolean {
            if (pattern.startsWith("/")) {
                val end = pattern.lastIndexOf('/')
                if (end > 0) {
                    val body = pattern.substring(1, end)
                    val flags = pattern.substring(end + 1)
                    val opts = mutableSetOf<RegexOption>()
                    if ('i' in flags) opts += RegexOption.IGNORE_CASE
                    if ('s' in flags) opts += RegexOption.DOT_MATCHES_ALL
                    if ('m' in flags) opts += RegexOption.MULTILINE
                    return runCatching {
                        Regex(body, opts).containsMatchIn(text)
                    }.getOrDefault(false)
                }
            }
            return matchMask(text, pattern)
        }

        /**
         * Expands `{var}` and `$var` placeholders in [s] using [vars]
         * (case-insensitive lookup). `$$` escapes to a literal `$`. Unknown
         * placeholders are left untouched so users can spot typos.
         */
        fun expand(s: String, vars: Map<String, String>): String {
            fun lookup(key: String): String? {
                val k = key.lowercase()
                return vars[k] ?: vars.entries.firstOrNull { it.key.lowercase() == k }?.value
            }
            // {name} form first.
            val braced = Regex("""\{([a-zA-Z_]+)\}""").replace(s) { m ->
                lookup(m.groupValues[1]) ?: m.value
            }
            // $name form (with $$ → $ escape).
            val out = StringBuilder()
            var i = 0
            while (i < braced.length) {
                val c = braced[i]
                if (c == '$' && i + 1 < braced.length && braced[i + 1] == '$') {
                    out.append('$'); i += 2; continue
                }
                if (c == '$' && i + 1 < braced.length && (braced[i + 1].isLetter() || braced[i + 1] == '_')) {
                    var j = i + 1
                    while (j < braced.length && (braced[j].isLetterOrDigit() || braced[j] == '_')) j++
                    val name = braced.substring(i + 1, j)
                    val value = lookup(name)
                    if (value != null) { out.append(value); i = j; continue }
                }
                out.append(c); i++
            }
            return out.toString()
        }
    }
}
