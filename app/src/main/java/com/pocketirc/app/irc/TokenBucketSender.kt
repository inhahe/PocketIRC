package com.pocketirc.app.irc

import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.feature.sending.QueueProcessingThreadSender
import java.util.function.Function

/**
 * Token-bucket outbound message rate limiter for KICL.
 *
 * - The bucket starts full with [burst] tokens.
 * - Every [refillIntervalMs] milliseconds, one token is added (up to [burst]).
 * - Sending a message consumes a token.
 * - When the bucket is empty, the sender thread sleeps until the next token.
 *
 * Tuned defaults: burst=8, refill=400ms. That's "8 messages instantly, then
 * 2.5 messages per second" — well under Libera's flood threshold of 8 messages
 * per 4 seconds while still feeling instant for normal use.
 */
class TokenBucketSender(
    client: Client,
    name: String,
    private val burst: Int,
    private val refillIntervalMs: Long,
) : QueueProcessingThreadSender(client, name) {

    private var tokens: Int = burst
    private var lastRefillMs: Long = System.currentTimeMillis()

    @Synchronized
    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillMs
        if (elapsed <= 0) return
        val newTokens = (elapsed / refillIntervalMs).toInt()
        if (newTokens > 0) {
            tokens = minOf(burst, tokens + newTokens)
            lastRefillMs += newTokens * refillIntervalMs
        }
    }

    override fun checkReady(message: String): Boolean {
        while (true) {
            synchronized(this) {
                refill()
                if (tokens > 0) {
                    tokens--
                    return true
                }
            }
            // Bucket empty: sleep until the next token would be available.
            val sleepMs = refillIntervalMs - (System.currentTimeMillis() - lastRefillMs)
                .coerceIn(0L, refillIntervalMs)
            try {
                Thread.sleep(sleepMs.coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    companion object {
        fun supplier(
            burst: Int = 8,
            refillIntervalMs: Long = 400,
        ): Function<Client.WithManagement, TokenBucketSender> =
            Function { client -> TokenBucketSender(client, "PocketIRC-out", burst, refillIntervalMs) }
    }
}
