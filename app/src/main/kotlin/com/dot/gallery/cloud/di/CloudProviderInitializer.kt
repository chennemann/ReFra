/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.di

import android.content.Context
import com.dot.gallery.cloud.core.CloudRuntimeSettings
import com.dot.gallery.cloud.core.ConnectionState
import com.dot.gallery.cloud.core.CredentialEncryptor
import com.dot.gallery.cloud.core.MediaCapabilityProvider
import com.dot.gallery.cloud.core.ProviderInstanceFactory
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.capabilities.RemoteMediaProvider
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.data.dao.CloudServerConfigDao
import com.dot.gallery.cloud.data.repository.CloudRepository
import com.dot.gallery.cloud.network.ServerUrlResolver
import com.dot.gallery.cloud.sync.CloudIndexProgressManager
import com.dot.gallery.core.Resource
import com.dot.gallery.feature_node.presentation.util.printDebug
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eagerly initializes all cloud providers by accepting the multibinding set.
 * Each provider module contributes to the set via @IntoSet, and
 * the provider's @Provides method handles registration into ProviderRegistry.
 *
 * Inject this class in GalleryApp.onCreate() to trigger initialization.
 * Call [initializeAsync] from a background coroutine to auto-configure
 * providers that have stored server configs.
 */
@Singleton
class CloudProviderInitializer @Inject constructor(
    @ApplicationContext context: Context,
    private val providerFactories: Set<@JvmSuppressWildcards ProviderInstanceFactory>,
    private val registry: ProviderRegistry,
    private val configDao: CloudServerConfigDao,
    private val credentialEncryptor: CredentialEncryptor,
    private val cloudMediaDao: CloudMediaDao,
    private val cloudRepository: CloudRepository,
    private val urlResolver: ServerUrlResolver,
    private val indexProgressManager: CloudIndexProgressManager
) {

    private val prefetchPreferences = context.getSharedPreferences(
        PREFETCH_PREFERENCES,
        Context.MODE_PRIVATE
    )

    private val factoriesByType by lazy { providerFactories.associateBy { it.providerType } }

    /** Last effective (resolved) server URL applied per account, to skip no-op reconfigures. */
    private val lastResolvedUrl = ConcurrentHashMap<Long, String>()

    /** Long-lived scope for non-blocking network prefetches (assets/trash). */
    private val prefetchScope = CoroutineScope(Dispatchers.IO)

    /**
     * App-lifetime scope for account reconfigures. Deliberately NOT tied to any ViewModel/UI
     * scope: a URL switch triggered from a settings screen must survive the user navigating
     * away, otherwise the in-flight re-authentication is cancelled ("Socket closed") and the
     * provider is left half-switched (new base URL, stale/absent auth) with no data reload.
     */
    private val reconfigureScope = CoroutineScope(Dispatchers.IO)

    /**
     * Pages through [provider]'s remote assets (and its trash), non-blocking. Providers own
     * persistence of the returned rows; writing them again here caused duplicate Room
     * invalidations and repeated timeline rebuilds.
     *
     * Newly registered/reconfigured accounts populate immediately. Startup auto-auth uses the
     * cached rows first, skips a recent index attempt, and delays a stale index until the UI
     * has had time to render.
     */
    private fun prefetchProviderData(
        provider: RemoteMediaProvider,
        label: String,
        configId: Long,
        isStartup: Boolean = false
    ) {
        prefetchScope.launch {
            if (isStartup) {
                val cachedCount = cloudMediaDao.countByConfig(configId)
                val lastRefresh = prefetchPreferences.getLong(refreshKey(configId), 0L)
                val now = System.currentTimeMillis()
                if (!shouldRefreshCloudIndex(cachedCount, lastRefresh, now)) {
                    printDebug("CloudProviderInitializer: Using recent cache for $label ($cachedCount assets)")
                    return@launch
                }
                if (cachedCount > 0) {
                    // Mark the attempt before waiting/network I/O. This coalesces rapid relaunches
                    // and prevents an unavailable server from starting another timeout-heavy full
                    // index on every launch; an empty cache still retries immediately.
                    prefetchPreferences.edit().putLong(refreshKey(configId), now).apply()
                    delay(WARM_CACHE_PREFETCH_DELAY_MS)
                }
            }

            indexProgressManager.start(configId, label)
            var completed = false
            try {
                var page = 0
                var total = 0
                while (true) {
                    val resource = provider.getRemoteAssets(page, PREFETCH_PAGE_SIZE).first()
                    if (resource !is Resource.Success) break
                    val items = resource.data ?: emptyList()
                    if (items.isNotEmpty()) {
                        total += items.size
                        indexProgressManager.update(configId, total, label)
                    }
                    if (items.size < PREFETCH_PAGE_SIZE) {
                        completed = true
                        break
                    }
                    page++
                    if (page >= MAX_PREFETCH_PAGES) {
                        completed = true
                        break
                    }
                }
                if (completed) {
                    prefetchPreferences.edit()
                        .putLong(refreshKey(configId), System.currentTimeMillis())
                        .apply()
                }
                printDebug("CloudProviderInitializer: Cached $total assets for $label")

                // Keep this in the same background task instead of issuing another simultaneous
                // startup request. Providers persist fetched trash just like normal assets.
                provider.getRemoteTrashed().first()
            } catch (e: Exception) {
                printDebug("CloudProviderInitializer: Asset prefetch failed for $label: ${e.message}")
            } finally {
                indexProgressManager.finish(configId)
            }
        }
    }

    /**
     * Creates a fresh, UNconfigured provider instance for [type] (or null if that provider
     * is not built into this variant). Used by the add-account wizard to test a connection or
     * list remote albums before the config has been persisted/registered.
     */
    fun createTransientProvider(type: ProviderType): MediaCapabilityProvider? =
        factoriesByType[type]?.create()

    /**
     * Mints (or reuses), configures, authenticates and registers the provider instance for a
     * single account [configId]. Call after a new account is saved so it becomes usable
     * immediately, without waiting for the next app start. Must run off the main thread.
     */
    suspend fun registerAccount(configId: Long) {
        val entity = configDao.getById(configId) ?: return
        if (!entity.isActive) return
        val provider = (registry.getByConfigId(configId) as? RemoteMediaProvider)
            ?: (factoriesByType[entity.providerType]?.create() as? RemoteMediaProvider ?: return)
        try {
            val config = entity.toCloudServerConfig().let { cfg ->
                cfg.copy(
                    apiKey = cfg.apiKey?.let { credentialEncryptor.decrypt(it) },
                    password = cfg.password?.let { credentialEncryptor.decrypt(it) }
                )
            }
            val resolved = urlResolver.resolve(config)
            lastResolvedUrl[entity.id] = resolved.serverUrl
            provider.configure(resolved)
            provider.authenticate(resolved)
            registry.register(entity.id, provider)
            cloudRepository.notifyProviderConnected(entity.providerType, ConnectionState.CONNECTED)
            // Populate the cache immediately so a freshly added account's media/albums appear
            // in the timeline and album grid without waiting for the next app start or sync.
            prefetchProviderData(provider, entity.displayName.ifBlank { entity.providerType.displayName }, entity.id)
            printDebug("CloudProviderInitializer: Registered account ${entity.providerType} #${entity.id}")
        } catch (e: Exception) {
            printDebug("CloudProviderInitializer: registerAccount failed for #${entity.id}: ${e.message}")
        }
    }

    /**
     * Auto-configure and authenticate remote providers that have an active
     * server config stored in the database. Must be called from a background
     * coroutine — never from the main thread.
     */
    suspend fun initializeAsync() {
        val activeConfigs = configDao.getAll().first().filter { it.isActive }
        // Prime the global viewer/advanced preferences snapshot from the active account so
        // settings like "Verbose logging" take effect from app start, not only after the
        // user visits the settings screen.
        CloudRuntimeSettings.apply(activeConfigs.firstOrNull()?.toCloudServerConfig())
        for (entity in activeConfigs) {
            val factory = factoriesByType[entity.providerType] ?: continue
            val provider = factory.create() as? RemoteMediaProvider ?: continue
            try {
                val config = entity.toCloudServerConfig().let { cfg ->
                    cfg.copy(
                        apiKey = cfg.apiKey?.let { credentialEncryptor.decrypt(it) },
                        password = cfg.password?.let { credentialEncryptor.decrypt(it) }
                    )
                }
                val resolved = urlResolver.resolve(config)
                lastResolvedUrl[entity.id] = resolved.serverUrl
                provider.configure(resolved)
                provider.authenticate(resolved)
                registry.register(entity.id, provider)
                // Notify CONNECTED immediately so cached data from Room is displayed right away
                cloudRepository.notifyProviderConnected(entity.providerType, ConnectionState.CONNECTED)
                printDebug("CloudProviderInitializer: Auto-authenticated ${entity.providerType} #${entity.id} with ${resolved.serverUrl}")
                // Proactive cache: fetch fresh data from network in parallel (non-blocking).
                prefetchProviderData(
                    provider,
                    entity.displayName.ifBlank { entity.providerType.displayName },
                    entity.id,
                    isStartup = true
                )
            } catch (e: Exception) {
                printDebug("CloudProviderInitializer: Auto-auth failed for ${entity.providerType} #${entity.id}: ${e.message}")
            }
        }
    }

    /**
     * Re-resolve and re-apply the server URL for a single account [configId] after its config
     * changed — e.g. the user toggled automatic URL switching or edited the local URL/SSID in
     * settings. Unlike [reconfigureActiveProviders] this does NOT require [autoUrlSwitch] to be
     * enabled, so turning the feature OFF correctly reverts the provider to its external URL.
     * No-op when the effective URL is unchanged. Must run off the main thread.
     */
    fun reconfigureAccountAsync(configId: Long) {
        reconfigureScope.launch { reconfigureAccount(configId) }
    }

    suspend fun reconfigureAccount(configId: Long) {
        val entity = configDao.getById(configId) ?: return
        if (!entity.isActive) return
        val provider = registry.getByConfigId(configId) as? RemoteMediaProvider ?: return
        try {
            val config = entity.toCloudServerConfig().let { cfg ->
                cfg.copy(
                    apiKey = cfg.apiKey?.let { credentialEncryptor.decrypt(it) },
                    password = cfg.password?.let { credentialEncryptor.decrypt(it) }
                )
            }
            val resolved = urlResolver.resolve(config)
            if (lastResolvedUrl[entity.id] == resolved.serverUrl) return
            lastResolvedUrl[entity.id] = resolved.serverUrl
            provider.configure(resolved)
            provider.authenticate(resolved)
            cloudRepository.notifyProviderConnected(entity.providerType, ConnectionState.CONNECTED)
            printDebug("CloudProviderInitializer: Reconfigured account #${entity.id} -> ${resolved.serverUrl}")
            // Re-pull data from the new URL so the timeline/albums reflect the switched host.
            prefetchProviderData(provider, entity.displayName.ifBlank { entity.providerType.displayName }, entity.id)
        } catch (e: Exception) {
            printDebug("CloudProviderInitializer: reconfigureAccount failed for #${entity.id}: ${e.message}")
        }
    }

    /**
     * Re-resolve and re-apply server URLs for active auto-URL-switching providers. Intended to
     * be called when the network changes (e.g. moving between the local network and mobile data).
     * Only providers whose effective URL actually changed are reconfigured + re-authenticated.
     * Safe to call repeatedly; must run off the main thread.
     */
    suspend fun reconfigureActiveProviders() {
        val activeConfigs = configDao.getAll().first().filter { it.isActive && it.autoUrlSwitch }
        for (entity in activeConfigs) {
            val provider = registry.getByConfigId(entity.id) as? RemoteMediaProvider ?: continue
            try {
                val config = entity.toCloudServerConfig().let { cfg ->
                    cfg.copy(
                        apiKey = cfg.apiKey?.let { credentialEncryptor.decrypt(it) },
                        password = cfg.password?.let { credentialEncryptor.decrypt(it) }
                    )
                }
                val resolved = urlResolver.resolve(config)
                if (lastResolvedUrl[entity.id] == resolved.serverUrl) continue
                lastResolvedUrl[entity.id] = resolved.serverUrl
                provider.configure(resolved)
                provider.authenticate(resolved)
                cloudRepository.notifyProviderConnected(entity.providerType, ConnectionState.CONNECTED)
                printDebug("CloudProviderInitializer: Reconfigured ${entity.providerType} #${entity.id} -> ${resolved.serverUrl}")
            } catch (e: Exception) {
                printDebug("CloudProviderInitializer: Reconfigure failed for ${entity.providerType} #${entity.id}: ${e.message}")
            }
        }
    }

    companion object {
        private const val PREFETCH_PREFERENCES = "cloud_index_refresh"
        private const val REFRESH_KEY_PREFIX = "last_refresh_"

        /** Avoid re-indexing the entire remote library on every warm app launch. */
        internal const val CLOUD_INDEX_REFRESH_INTERVAL_MS = 30 * 60 * 1000L

        /** Let cached media render and interaction settle before a stale background index. */
        private const val WARM_CACHE_PREFETCH_DELAY_MS = 5_000L

        /** Page size for the startup asset prefetch. */
        private const val PREFETCH_PAGE_SIZE = 200

        /**
         * Hard cap on prefetch pages (safety valve against a misbehaving provider that never
         * returns a short page). 500 pages * 200 = 100k assets, well beyond typical libraries.
         */
        private const val MAX_PREFETCH_PAGES = 500

        private fun refreshKey(configId: Long) = "$REFRESH_KEY_PREFIX$configId"
    }
}

internal fun shouldRefreshCloudIndex(
    cachedCount: Int,
    lastRefreshMillis: Long,
    nowMillis: Long
): Boolean = cachedCount == 0 ||
    lastRefreshMillis <= 0L ||
    nowMillis - lastRefreshMillis >= CloudProviderInitializer.CLOUD_INDEX_REFRESH_INTERVAL_MS
