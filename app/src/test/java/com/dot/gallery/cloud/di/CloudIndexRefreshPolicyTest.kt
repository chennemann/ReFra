/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudIndexRefreshPolicyTest {

    @Test
    fun `empty cache refreshes even after a recent run`() {
        assertTrue(shouldRefreshCloudIndex(cachedCount = 0, lastRefreshMillis = 900L, nowMillis = 1_000L))
    }

    @Test
    fun `warm cache without a completed index refreshes`() {
        assertTrue(shouldRefreshCloudIndex(cachedCount = 12, lastRefreshMillis = 0L, nowMillis = 1_000L))
    }

    @Test
    fun `recent warm cache skips a redundant full index`() {
        val now = CloudProviderInitializer.CLOUD_INDEX_REFRESH_INTERVAL_MS

        assertFalse(shouldRefreshCloudIndex(cachedCount = 12, lastRefreshMillis = 1L, nowMillis = now))
    }

    @Test
    fun `stale warm cache refreshes at the interval boundary`() {
        val interval = CloudProviderInitializer.CLOUD_INDEX_REFRESH_INTERVAL_MS

        assertTrue(shouldRefreshCloudIndex(cachedCount = 12, lastRefreshMillis = 100L, nowMillis = 100L + interval))
    }
}
