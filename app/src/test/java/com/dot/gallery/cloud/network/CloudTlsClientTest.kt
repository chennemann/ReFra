package com.dot.gallery.cloud.network

import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.ProviderType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class CloudTlsClientTest {
    private val serverCertificate = HeldCertificate.Builder().commonName("localhost")
        .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val alice = HeldCertificate.Builder().commonName("alice").build()
    private val bob = HeldCertificate.Builder().commonName("bob").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(serverCertificate)
        .addTrustedCertificate(alice.certificate).addTrustedCertificate(bob.certificate).build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(serverCertificate.certificate).build()
    private val base = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
        .callTimeout(5, TimeUnit.SECONDS).build()

    private fun server(required: Boolean = true) = MockWebServer().apply {
        useHttps(serverTls.sslSocketFactory(), false)
        if (required) requireClientAuth() else requestClientAuth()
        start()
    }

    private fun profile(url: String, alias: String = "alice"): CloudTlsClient {
        val config = CloudServerConfig(providerType = ProviderType.WEBDAV, serverUrl = url,
            clientCertificates = ClientCertificates.set("{}", url, alias))
        return CloudTlsClient(config) { selected ->
            val cert = if (selected == "alice") alice else bob
            ClientIdentity(cert.keyPair.private, arrayOf(cert.certificate))
        }
    }

    private fun get(client: OkHttpClient, url: String): Int = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test fun `server receives selected certificate and identities cannot reuse connections`() {
        server().use { server ->
            val url = server.url("/cloud").toString()
            server.enqueue(MockResponse().setBody("alice"))
            server.enqueue(MockResponse().setBody("bob"))
            assertEquals(200, get(profile(url).wrap(base), url))
            assertEquals(alice.certificate, server.takeRequest().handshake!!.peerCertificates.single())
            assertEquals(200, get(profile(url, "bob").wrap(base), url))
            val request = server.takeRequest()
            assertEquals(bob.certificate, request.handshake!!.peerCertificates.single())
            assertEquals(0, request.sequenceNumber)
        }
    }

    @Test fun `redirect to another origin never sends the client identity`() {
        server().use { first -> server(required = false).use { second ->
            val url = first.url("/").toString()
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/")))
            second.enqueue(MockResponse().setBody("redirected"))
            assertEquals(200, get(profile(url).wrap(base), url))
            assertEquals(alice.certificate, first.takeRequest().handshake!!.peerCertificates.single())
            assertTrue(second.takeRequest().handshake!!.peerCertificates.isEmpty())
        } }
    }

    @Test fun `client certificate does not weaken server certificate validation`() {
        server().use { server ->
            val url = server.url("/").toString()
            val untrusted = OkHttpClient.Builder()
                .dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }
                .callTimeout(5, TimeUnit.SECONDS).build()
            val failure = runCatching { get(profile(url).wrap(untrusted), url) }.exceptionOrNull()
            assertTrue("Expected server trust rejection, got $failure", failure is SSLHandshakeException)
        }
    }

    @Test fun `unavailable identity fails authentication instead of falling back`() {
        server().use { server ->
            val url = server.url("/").toString()
            val config = CloudServerConfig(providerType = ProviderType.WEBDAV, serverUrl = url,
                clientCertificates = ClientCertificates.set("{}", url, "deleted"))
            val client = CloudTlsClient(config) { throw SSLHandshakeException("Certificate unavailable") }.wrap(base)
            assertTrue(runCatching { get(client, url) }.isFailure)
        }
    }

    @Test fun `accounts without certificates retain their existing transport`() {
        val config = CloudServerConfig(providerType = ProviderType.WEBDAV, serverUrl = "https://example.com")
        assertSame(base, CloudTlsClient(config) { error("Must not read KeyChain") }.wrap(base))
    }

    @Test fun `redirect to another hostname on the same server cannot coalesce authenticated connections`() {
        server(required = false).use { server ->
            val url = server.url("/")
            val otherHost = url.newBuilder().host("127.0.0.1").build()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", otherHost))
            server.enqueue(MockResponse().setBody("redirected"))
            assertEquals(200, get(profile(url.toString()).wrap(base), url.toString()))
            assertEquals(alice.certificate, server.takeRequest().handshake!!.peerCertificates.single())
            val redirected = server.takeRequest()
            assertTrue(redirected.handshake!!.peerCertificates.isEmpty())
            assertEquals(0, redirected.sequenceNumber)
        }
    }
}
