package com.dot.gallery.cloud.network

import android.content.Context
import android.security.KeyChain
import com.dot.gallery.cloud.core.CloudServerConfig
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * One TLS identity for one account's effective base URL. Recreate on URL/certificate changes.
 * Clients retain their server trust policy, interceptors and cache, but never share authenticated
 * connections or TLS sessions with another account/URL. Redirects to other origins get no identity.
 */
class CloudTlsClient internal constructor(config: CloudServerConfig, private val load: (String) -> ClientIdentity) {
    constructor(context: Context, config: CloudServerConfig) : this(config, androidIdentityLoader(context.applicationContext))
    private val alias = ClientCertificates.alias(config.clientCertificates, config.serverUrl)
    private val origin = if (alias != null) config.serverUrl.trim().toHttpUrl() else null
    private val clients = mutableMapOf<OkHttpClient, OkHttpClient>()

    @Synchronized
    fun wrap(base: OkHttpClient): OkHttpClient {
        val selectedAlias = alias ?: return base
        return clients.getOrPut(base) {
            val manager = ClientCertificateKeyManager(origin!!, selectedAlias, load)
            val trust = requireNotNull(base.x509TrustManager) { "Cloud transport must support HTTPS" }
            val tls = SSLContext.getInstance("TLS").apply {
                init(arrayOf(manager), arrayOf(trust), null)
            }
            base.newBuilder()
                .connectionPool(ConnectionPool())
                .sslSocketFactory(tls.socketFactory, trust)
                // A distinct verifier disables HTTP/2 cross-host connection coalescing. Otherwise
                // a redirect could reuse an authenticated connection without invoking the key manager.
                .hostnameVerifier { host, session -> base.hostnameVerifier.verify(host, session) }
                .build()
        }
    }
}

private fun androidIdentityLoader(context: Context): (String) -> ClientIdentity = { selected ->
    try {
        val key = KeyChain.getPrivateKey(context, selected)
        val chain = KeyChain.getCertificateChain(context, selected)
        if (key == null || chain.isNullOrEmpty()) throw SSLHandshakeException("Client certificate is unavailable")
        chain.first().checkValidity()
        ClientIdentity(key, chain)
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        throw SSLHandshakeException(
            "Cannot use the client certificate. Select a valid certificate in the account settings."
        ).apply { initCause(error) }
    }
}

internal data class ClientIdentity(val key: PrivateKey, val chain: Array<X509Certificate>)

internal class ClientCertificateKeyManager(
    private val origin: HttpUrl,
    private val alias: String,
    private val load: (String) -> ClientIdentity
) : X509ExtendedKeyManager() {
    private fun choose(host: String?, port: Int, types: Array<out String>?): String? {
        if (!origin.host.equals(host, ignoreCase = true) || origin.port != port) return null
        val identity = load(alias)
        if (types != null && types.none { it.substringBefore('_') == identity.key.algorithm }) return null
        return alias
    }

    override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? {
        val session = (socket as? SSLSocket)?.handshakeSession ?: return null
        return choose(session.peerHost, session.peerPort, types)
    }

    override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        choose(engine?.peerHost, engine?.peerPort ?: -1, types)

    override fun getPrivateKey(requestedAlias: String?): PrivateKey? =
        if (requestedAlias == alias) load(alias).key else null

    override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate>? =
        if (requestedAlias == alias) load(alias).chain else null

    override fun getClientAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun getServerAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
