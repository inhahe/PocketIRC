package com.pocketirc.app.irc

import com.pocketirc.app.model.TreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the self-message handling in [BufferStore.ingest].
 *
 * The bug these guard against: the store used to drop every inbound message
 * whose sender was our own nick, on the assumption that it must be an echo of
 * something this device had already displayed locally. Behind a bouncer that is
 * wrong most of the time -- a message typed on the desktop arrives with exactly
 * the same shape and was silently thrown away, so it never appeared on the
 * phone at all, live or in backscroll.
 *
 * The distinction is now [BufferStore.noteLocalEcho]: only a message matching a
 * send this device made moments ago is swallowed, and only once each.
 */
class BufferStoreEchoTest {

    private val server = "srv1"
    private val nick = "inhahe"

    private fun store() = BufferStore()

    private fun msg(
        target: String,
        sender: String = "inhahe",
        text: String = "hello",
    ) = IrcEvent.Message(
        serverId = server,
        target = target,
        sender = sender,
        text = text,
        timestampMs = 1_000L,
    )

    private fun lines(s: BufferStore, buffer: String) =
        s.buffers.value["$server::$buffer"]?.lines.orEmpty()

    @Test
    fun `own message from another device is displayed`() {
        val s = store()
        s.ingest(msg("#techcrap", text = "two minute papers"), nick)
        val shown = lines(s, "#techcrap")
        assertEquals(1, shown.size)
        assertEquals("two minute papers", shown[0].text)
        assertEquals(nick, shown[0].sender)
    }

    @Test
    fun `local echo swallows exactly one server copy`() {
        val s = store()
        s.noteLocalEcho(server, "#techcrap", "hi")
        s.appendOwnMessage(server, "#techcrap", nick, "hi", action = false)
        assertEquals(1, lines(s, "#techcrap").size)

        s.ingest(msg("#techcrap", text = "hi"), nick)
        assertEquals("the echo of our own send must not duplicate the local line",
            1, lines(s, "#techcrap").size)
    }

    @Test
    fun `a second identical copy is not swallowed`() {
        val s = store()
        s.noteLocalEcho(server, "#techcrap", "hi")
        s.ingest(msg("#techcrap", text = "hi"), nick)   // consumed
        s.ingest(msg("#techcrap", text = "hi"), nick)   // from elsewhere
        assertEquals(1, lines(s, "#techcrap").size)
    }

    @Test
    fun `two local sends absorb two copies`() {
        val s = store()
        s.noteLocalEcho(server, "#techcrap", "hi")
        s.noteLocalEcho(server, "#techcrap", "hi")
        s.ingest(msg("#techcrap", text = "hi"), nick)
        s.ingest(msg("#techcrap", text = "hi"), nick)
        assertTrue(lines(s, "#techcrap").isEmpty())
    }

    @Test
    fun `echo record is per target`() {
        val s = store()
        s.noteLocalEcho(server, "#techcrap", "hi")
        s.ingest(msg("#other", text = "hi"), nick)
        assertEquals(1, lines(s, "#other").size)
    }

    @Test
    fun `echo record is per exact text`() {
        val s = store()
        s.noteLocalEcho(server, "#techcrap", "hi")
        s.ingest(msg("#techcrap", text = "hi there"), nick)
        assertEquals(1, lines(s, "#techcrap").size)
    }

    @Test
    fun `echo record ignores target case`() {
        val s = store()
        s.noteLocalEcho(server, "#TechCrap", "hi")
        s.ingest(msg("#techcrap", text = "hi"), nick)
        assertTrue(lines(s, "#techcrap").isEmpty())
    }

    @Test
    fun `echo record is per server`() {
        val s = store()
        s.noteLocalEcho("other-server", "#techcrap", "hi")
        s.ingest(msg("#techcrap", text = "hi"), nick)
        assertEquals(1, lines(s, "#techcrap").size)
    }

    @Test
    fun `messages from other people are untouched`() {
        val s = store()
        s.noteLocalEcho(server, "#techcrap", "hi")
        s.ingest(msg("#techcrap", sender = "someoneelse", text = "hi"), nick)
        assertEquals(1, lines(s, "#techcrap").size)
    }

    @Test
    fun `own private message files under the recipient not under us`() {
        val s = store()
        // An echo of a PM we sent to bob: target is bob, sender is us.
        s.ingest(msg("bob", text = "hey bob"), nick)
        assertEquals(1, lines(s, "bob").size)
        assertTrue("must not open a query buffer named after ourselves",
            lines(s, nick).isEmpty())
        assertEquals(TreeNode.Buffer.Kind.QUERY, s.buffers.value["$server::bob"]?.kind)
    }

    @Test
    fun `incoming private message still files under the sender`() {
        val s = store()
        s.ingest(msg(nick, sender = "bob", text = "hey"), nick)
        assertEquals(1, lines(s, "bob").size)
    }

    @Test
    fun `our own words never highlight`() {
        val s = store()
        // A PM would normally highlight, and so would our own nick in the text.
        val fired = s.ingest(msg("bob", text = "inhahe was here"), nick)
        assertFalse("a message we sent ourselves must not notify us", fired)
        assertFalse(s.buffers.value["$server::bob"]?.highlighted ?: true)
    }

    @Test
    fun `someone else saying our nick does highlight`() {
        val s = store()
        val fired = s.ingest(msg("#techcrap", sender = "bob", text = "inhahe: ping"), nick)
        assertTrue(fired)
        assertTrue(s.buffers.value["$server::#techcrap"]?.highlighted ?: false)
    }
}
