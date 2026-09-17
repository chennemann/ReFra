package com.dot.gallery.cloud.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Device-local KeyChain aliases, keyed by the configured HTTPS base URL. Never contains keys. */
object ClientCertificates {
    fun urlKey(url: String): String? = url.trim().toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        ?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()?.trimEnd('/')

    fun decode(value: String): Map<String, String> = Json.decodeFromString(value)

    fun alias(value: String, url: String): String? =
        urlKey(url)?.let { decode(value)[it] }?.takeIf { it.isNotBlank() }

    fun set(value: String, url: String, alias: String?): String {
        val key = urlKey(url) ?: return value
        val aliases = decode(value).toMutableMap()
        if (alias.isNullOrBlank()) aliases.remove(key) else aliases[key] = alias
        return Json.encodeToString(aliases)
    }

    /** Drop orphaned choices when a URL is removed; editing an address never transfers consent. */
    fun retain(value: String, urls: List<String>): String {
        val keys = urls.mapNotNull(::urlKey).toSet()
        return Json.encodeToString(decode(value).filterKeys { it in keys })
    }
}
