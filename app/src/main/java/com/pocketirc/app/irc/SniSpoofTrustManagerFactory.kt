package com.pocketirc.app.irc

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A [TrustManagerFactory] that delegates chain validation to the platform's
 * default trust managers but verifies the leaf certificate's Subject Alternative
 * Names (and CN as fallback) against a *fixed* hostname instead of whatever
 * hostname the underlying socket happens to be connecting to.
 *
 * We use this when the user has pinned a server entry to IPv4 or IPv6: we
 * resolve the hostname to an IP literal ourselves and hand that IP to KICL
 * so the TCP connection goes to the right address family. The TLS handshake
 * still presents that IP as SNI to the server, but our verifier checks the
 * cert against the original hostname so legitimate certificates are accepted.
 *
 * Caveat: a small number of multi-tenant TLS hosts pick which certificate to
 * present *based on* SNI, and will serve a default cert (or close the
 * connection) when SNI is an IP literal. For nearly all IRC networks (which
 * dedicate a TLS endpoint per network) this is not an issue.
 */
class SniSpoofTrustManagerFactory(expectedHost: String) : TrustManagerFactory(
    Spi(expectedHost),
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).provider,
    "PocketIrcSniSpoof",
) {
    private class Spi(private val expectedHost: String) : TrustManagerFactorySpi() {
        override fun engineInit(ks: KeyStore?) {}
        override fun engineInit(spec: ManagerFactoryParameters?) {}
        override fun engineGetTrustManagers(): Array<TrustManager> {
            val def = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            def.init(null as KeyStore?)
            val base = def.trustManagers.filterIsInstance<X509ExtendedTrustManager>().firstOrNull()
                ?: error("No default X509ExtendedTrustManager available")
            return arrayOf(Wrapping(base, expectedHost))
        }
    }

    private class Wrapping(
        private val delegate: X509ExtendedTrustManager,
        private val expectedHost: String,
    ) : X509ExtendedTrustManager() {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            delegate.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            delegate.checkClientTrusted(chain, authType, engine)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        // The three server-trust overloads all funnel into the same logic:
        // chain validation by the platform default, then a manual hostname
        // check against [expectedHost] (NOT against socket/engine peer).
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            delegate.checkServerTrusted(chain, authType)
            verifyHostname(expectedHost, chain[0])
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            // Skip socket-based default hostname verification — it would check
            // against the IP literal we used to dial. Do chain only.
            delegate.checkServerTrusted(chain, authType)
            verifyHostname(expectedHost, chain[0])
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
            delegate.checkServerTrusted(chain, authType)
            verifyHostname(expectedHost, chain[0])
        }
    }

    companion object {
        /**
         * Verify that [cert] is valid for [host] by walking the Subject
         * Alternative Names (dNSName entries) and falling back to the Common
         * Name. Supports a single leading wildcard label (e.g. *.example.com).
         */
        fun verifyHostname(host: String, cert: X509Certificate) {
            val needle = host.lowercase().trimEnd('.')
            // Try SAN first.
            val san: Collection<List<*>>? = try { cert.subjectAlternativeNames } catch (_: Throwable) { null }
            if (san != null) {
                for (entry in san) {
                    if (entry.size < 2) continue
                    val type = entry[0] as? Int ?: continue
                    if (type != 2) continue // 2 = dNSName
                    val name = (entry[1] as? String)?.lowercase()?.trimEnd('.') ?: continue
                    if (matches(needle, name)) return
                }
            }
            // Fall back to CN inside the Subject DN.
            val dn = cert.subjectX500Principal.name
            val cn = parseCn(dn)?.lowercase()?.trimEnd('.')
            if (cn != null && matches(needle, cn)) return
            throw CertificateException(
                "Certificate is not valid for host $host (subject=$dn)"
            )
        }

        private fun matches(host: String, pattern: String): Boolean {
            if (host == pattern) return true
            // Wildcard form: *.example.com matches one label of example.com
            if (pattern.startsWith("*.")) {
                val tail = pattern.substring(2)
                val dot = host.indexOf('.')
                if (dot < 0) return false
                return host.substring(dot + 1) == tail
            }
            return false
        }

        private fun parseCn(dn: String): String? {
            // Very small RDN parser: looks for CN= ... up to comma (no escapes).
            for (rdn in dn.split(',')) {
                val t = rdn.trim()
                if (t.startsWith("CN=", ignoreCase = true)) return t.substring(3)
            }
            return null
        }
    }
}
