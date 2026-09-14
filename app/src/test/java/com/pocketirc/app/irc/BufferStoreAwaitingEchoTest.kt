package com.pocketirc.app.irc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the "did the server ever take this message?" bookkeeping.
 *
 * With `echo-message` negotiated, Pocket IRC draws nothing when you send --
 * the server's copy is the one displayed. That is fine until the copy never
 * comes. It happened for real: the bouncer's upstream had dropped and was
 * sitting out a 240-second reconnect wait, so three messages typed into a
 * channel were refused. The refusal went to a different window, and the
 * channel showed no text and no error whatsoever.
 *
 * [BufferStore.noteAwaitingEcho] records the debt, [BufferStore.confirmEcho]
 * clears it when the copy lands, and [BufferStore.abandonEcho] reports whether
 * it was still outstanding -- which is the signal to put the text back in
 * front of the user.
 */
class BufferStoreAwaitingEchoTest {

    private val server = "srv1"

    private fun store() = BufferStore()

    private fun selfMessage(target: String, text: String) = IrcEvent.Message(
        serverId = server,
        target = target,
        sender = "inhahe",
        text = text,
        timestampMs = 1_000L,
    )

    @Test
    fun `an echo that never arrives is reported`() {
        val s = store()
        val id = s.noteAwaitingEcho(server, "#chan", "hello")
        assertTrue("still outstanding, so the user must be told", s.abandonEcho(id))
    }

    @Test
    fun `an echo that arrives cancels the warning`() {
        val s = store()
        val id = s.noteAwaitingEcho(server, "#chan", "hello")
        s.ingest(selfMessage("#chan", "hello"), ourNick = "inhahe")
        assertFalse("the message came back, so nothing is owed", s.abandonEcho(id))
    }

    @Test
    fun `confirmation is matched per send, not per text`() {
        // Sending the same line twice owes two copies back. One arriving must
        // clear exactly one warning, or the second send is reported as fine
        // when it never made it.
        val s = store()
        val first = s.noteAwaitingEcho(server, "#chan", "ping")
        val second = s.noteAwaitingEcho(server, "#chan", "ping")
        s.ingest(selfMessage("#chan", "ping"), ourNick = "inhahe")
        assertFalse("oldest send is the one confirmed", s.abandonEcho(first))
        assertTrue("second send is still unaccounted for", s.abandonEcho(second))
    }

    @Test
    fun `confirmation is scoped to the target`() {
        val s = store()
        val id = s.noteAwaitingEcho(server, "#chan", "hello")
        s.ingest(selfMessage("#other", "hello"), ourNick = "inhahe")
        assertTrue("same text elsewhere is not this message", s.abandonEcho(id))
    }

    @Test
    fun `a warning is only ever reported once`() {
        val s = store()
        val id = s.noteAwaitingEcho(server, "#chan", "hello")
        assertTrue(s.abandonEcho(id))
        assertFalse("a second timeout must not warn again", s.abandonEcho(id))
    }

    @Test
    fun `a private message is confirmed by the copy addressed to the recipient`() {
        // The echo of a PM carries the *recipient* as its target, not us, so
        // the key has to match what was sent, not who sent it.
        val s = store()
        val id = s.noteAwaitingEcho(server, "aelita", "you there?")
        s.ingest(selfMessage("aelita", "you there?"), ourNick = "inhahe")
        assertFalse(s.abandonEcho(id))
    }
}
