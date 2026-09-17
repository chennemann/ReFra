package com.dot.gallery.cloud.network

import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.data.entity.CloudServerConfigEntity
import com.dot.gallery.cloud.ui.AddServerUiState
import com.dot.gallery.cloud.ui.mergeCloudServerConfig
import org.junit.Assert.*
import org.junit.Test

class ClientCertificatesTest {
    @Test fun `choices are independent by scheme host port and base path`() {
        val urls = listOf("https://example.com/cloud", "https://example.com:8443/cloud", "https://lan.example/cloud", "https://example.com/other")
        var choices = "{}"
        urls.forEachIndexed { i, url -> choices = ClientCertificates.set(choices, url, "cert-$i") }
        urls.forEachIndexed { i, url -> assertEquals("cert-$i", ClientCertificates.alias(choices, url)) }
        assertEquals("cert-0", ClientCertificates.alias(choices, " https://EXAMPLE.com:443/cloud/ "))
        assertNull(ClientCertificates.alias(choices, "http://example.com/cloud"))
        assertNull(ClientCertificates.alias(choices, "https://example.com/cloud-other"))
        assertNull(ClientCertificates.alias(choices, "https://example.com.attacker.test/cloud"))
    }

    @Test fun `removal only affects selected URL and new addresses do not inherit consent`() {
        var choices = ClientCertificates.set("{}", "https://one.test", "one")
        choices = ClientCertificates.set(choices, "https://two.test", "two")
        assertEquals("two", ClientCertificates.alias(ClientCertificates.set(choices, "https://one.test", null), "https://two.test"))
        assertEquals("{}", ClientCertificates.retain(choices, listOf("https://new.test")))
        assertEquals(choices, ClientCertificates.set(choices, "http://one.test", "unsafe"))
    }

    @Test fun `account edits persist all current URL choices and discard removed URLs`() {
        val primary = "https://primary.test"
        val local = "https://local.test"
        val extra = "https://extra.test"
        var choices = "{}"
        listOf(primary, local, extra, "https://removed.test").forEach { choices = ClientCertificates.set(choices, it, it) }
        val old = CloudServerConfigEntity(providerType = ProviderType.NEXTCLOUD, serverUrl = primary, externalUrls = "[\"$extra\"]")
        val state = AddServerUiState(providerType = ProviderType.NEXTCLOUD, serverUrl = primary, localServerUrl = local,
            externalUrls = old.externalUrls, clientCertificates = choices)
        val saved = mergeCloudServerConfig(old, state) { it }
        assertEquals(setOf(primary, local, extra), ClientCertificates.decode(saved.clientCertificates).keys)
        assertEquals(saved.clientCertificates, saved.toCloudServerConfig().clientCertificates)
        assertEquals(saved.clientCertificates, CloudServerConfigEntity.fromCloudServerConfig(saved.toCloudServerConfig()).clientCertificates)
    }
}
