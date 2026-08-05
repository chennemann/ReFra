/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.domain.util

import com.dot.gallery.feature_node.domain.model.Media
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaGroupingKeyTest {

    @Test
    fun stripsSupportedGroupingSuffixes() {
        assertEquals("PXL_20260418_155541857", media("PXL_20260418_155541857.RAW-01.jpg").groupBaseName)
        assertEquals("IMG_20250305_123456", media("IMG_20250305_123456(1).jpg").groupBaseName)
        assertEquals("IMG_20250305_123456", media("IMG_20250305_123456-edited.jpg").groupBaseName)
        assertEquals("IMG_20250305_123456", media("IMG_20250305_123456_HDR.jpg").groupBaseName)
    }

    @Test
    fun preservesPlainFilename() {
        assertEquals("holiday-photo", media("holiday-photo.jpg").groupBaseName)
    }

    private fun media(label: String) = Media.EncryptedMedia(
        id = 1L,
        label = label,
        bytes = byteArrayOf(),
        path = "/Pictures/$label",
        relativePath = "Pictures",
        albumID = 1L,
        albumLabel = "Pictures",
        timestamp = 0L,
        fullDate = "",
        mimeType = "image/jpeg",
        favorite = 0,
        trashed = 0,
        size = 1L
    )
}
