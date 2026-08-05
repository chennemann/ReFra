/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMappingLayoutTest {

    @Test
    fun normalGroupingSelectsOnlyNormalRepresentation() {
        assertEquals(
            MediaMappingLayout.NORMAL,
            resolveMediaMappingLayout(groupByMonth = false, groupByYear = false, withMonthHeader = true)
        )
    }

    @Test
    fun monthlyGroupingSelectsOnlyMonthlyRepresentation() {
        assertEquals(
            MediaMappingLayout.MONTHLY,
            resolveMediaMappingLayout(groupByMonth = true, groupByYear = false, withMonthHeader = true)
        )
    }

    @Test
    fun yearlyGroupingSelectsOnlyYearlyRepresentation() {
        assertEquals(
            MediaMappingLayout.YEARLY,
            resolveMediaMappingLayout(groupByMonth = false, groupByYear = true, withMonthHeader = true)
        )
    }

    @Test
    fun disabledAlternateHeadersProduceNoRepresentation() {
        assertEquals(
            MediaMappingLayout.NONE,
            resolveMediaMappingLayout(groupByMonth = true, groupByYear = false, withMonthHeader = false)
        )
    }
}
