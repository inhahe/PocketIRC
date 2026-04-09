package com.pocketirc.app.irc

/**
 * Validates a nickname against the RFC 2812 nick syntax (with the de facto
 * additions used by every modern IRC daemon). Length is intentionally NOT
 * checked because the maximum length varies network-to-network — Libera
 * allows 16, ircu allows 9, modern Solanum allows 30+, etc. ISUPPORT NICKLEN
 * is the authoritative source and we can't know it before connecting.
 *
 * The grammar is:
 *   nickname = ( letter / special ) *( letter / digit / special / "-" )
 *
 * where letter is ASCII A-Z / a-z, digit is 0-9, and special is
 *   [ ] \ ` _ ^ { | }
 *
 * The hyphen is allowed only as a subsequent character, not as the first.
 * The first character cannot be a digit.
 */
object NickValidator {

    private val SPECIAL_CHARS = setOf('[', ']', '\\', '`', '_', '^', '{', '|', '}')

    private fun isAsciiLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'
    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    /** True if [nick] is a syntactically valid IRC nickname (length unchecked). */
    fun isValid(nick: String): Boolean {
        if (nick.isEmpty()) return false
        val first = nick[0]
        if (!(isAsciiLetter(first) || first in SPECIAL_CHARS)) return false
        for (i in 1 until nick.length) {
            val c = nick[i]
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c in SPECIAL_CHARS || c == '-')) {
                return false
            }
        }
        return true
    }

    /** Returns an error message describing why [nick] is invalid, or null if valid. */
    fun reasonInvalid(nick: String): String? {
        if (nick.isEmpty()) return "Nick cannot be empty."
        val first = nick[0]
        if (isAsciiDigit(first)) return "Nick cannot start with a digit."
        if (first == '-') return "Nick cannot start with a hyphen."
        if (!(isAsciiLetter(first) || first in SPECIAL_CHARS)) {
            return "Nick must start with a letter or one of: [ ] \\ ` _ ^ { | }"
        }
        for (i in 1 until nick.length) {
            val c = nick[i]
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c in SPECIAL_CHARS || c == '-')) {
                return "Nick contains invalid character '$c' at position ${i + 1}."
            }
        }
        return null
    }
}
