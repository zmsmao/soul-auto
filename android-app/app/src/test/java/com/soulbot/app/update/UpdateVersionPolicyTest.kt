package com.soulbot.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionPolicyTest {
    @Test
    fun comparesNumericParts_notLexicographically() {
        assertTrue(UpdateVersionPolicy.isNewer("2.10.0", "2.9.9"))
        assertFalse(UpdateVersionPolicy.isNewer("2.1.1", "2.2.0"))
    }

    @Test
    fun missingTrailingPartsAreZero() {
        assertEquals(0, UpdateVersionPolicy.compare("2.2", "2.2.0"))
        assertTrue(UpdateVersionPolicy.isNewer("2.2.1", "2.2"))
    }

    @Test
    fun rejectsMalformedVersions() {
        assertFalse(UpdateVersionPolicy.isValidVersion("v2.2.0"))
        assertFalse(UpdateVersionPolicy.isValidVersion("2.x.0"))
        assertFalse(UpdateVersionPolicy.isNewer("2.x.0", "2.1.1"))
    }
}
