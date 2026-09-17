package com.dot.gallery.cloud.nextcloud.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dot.gallery.cloud.network.CloudTlsClient
import com.dot.gallery.cloud.webdav.data.api.buildWebDavOkHttp

import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.auth.CloudInteractiveAuthHandler
import com.dot.gallery.cloud.core.auth.InteractiveAuthPollResult
import com.dot.gallery.cloud.core.auth.InteractiveAuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

class NextcloudInteractiveAuthHandler @Inject constructor(
    private val client: NextcloudLoginFlowClient,
    @param:ApplicationContext private val context: Context
) : CloudInteractiveAuthHandler {
    override val providerType: ProviderType = ProviderType.NEXTCLOUD

    override suspend fun begin(serverUrl: String): InteractiveAuthSession = withContext(Dispatchers.IO) {
        client.begin(serverUrl)
    }

    private val transports = object : LinkedHashMap<CloudServerConfig, OkHttpClient>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CloudServerConfig, OkHttpClient>): Boolean {
            if (size <= 8) return false
            eldest.value.connectionPool.evictAll()
            return true
        }
    }

    @Synchronized
    private fun transport(config: CloudServerConfig): OkHttpClient = transports.getOrPut(config) {
        CloudTlsClient(context, config).wrap(buildWebDavOkHttp(30))
    }

    override suspend fun begin(config: CloudServerConfig): InteractiveAuthSession = withContext(Dispatchers.IO) {
        client.begin(config.serverUrl, transport(config)).copy(connectionConfig = config)
    }

    override suspend fun poll(session: InteractiveAuthSession): InteractiveAuthPollResult =
        withContext(Dispatchers.IO) {
            session.connectionConfig?.let { client.poll(session, transport(it)) } ?: client.poll(session)
        }

    override suspend fun revoke(config: CloudServerConfig): Result<Unit> {
        val username = config.username.orEmpty()
        val password = config.password.orEmpty()
        if (username.isBlank() || password.isBlank()) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            client.revoke(config.serverUrl, username, password, transport(config))
        }
    }
}
