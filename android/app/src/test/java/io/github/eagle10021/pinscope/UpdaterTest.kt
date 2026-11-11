package io.github.eagle10021.pinscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions() {
        assertEquals(listOf(0, 1, 1), Updater.numbers("v0.1.1-beta"))
        assertTrue(Updater.newer("v0.1.1-beta", "0.1.0"))
        assertTrue(Updater.newer("v0.2.0", "0.1.9"))
        assertTrue(Updater.newer("v1.0", "0.9.9"))
        assertFalse(Updater.newer("v0.1.0-beta", "0.1.0"))
        assertFalse(Updater.newer("v0.1.0", "0.1.1"))
    }
}
