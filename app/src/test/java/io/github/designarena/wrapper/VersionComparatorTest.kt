package io.github.designarena.wrapper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun detectsNewerSemanticVersions() {
        assertTrue(VersionComparator.isNewer("v1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.2.0", "1.1.9"))
        assertTrue(VersionComparator.isNewer("v2.0.0", "1.9.9"))
        assertTrue(VersionComparator.isNewer("1.0.0.1", "1.0.0"))
    }

    @Test
    fun rejectsEqualOrOlderVersions() {
        assertFalse(VersionComparator.isNewer("v1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.1"))
        assertFalse(VersionComparator.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun handlesSuffixesAndMalformedTagsSafely() {
        assertTrue(VersionComparator.isNewer("v1.1.0", "1.0.0-debug"))
        assertFalse(VersionComparator.isNewer("latest", "1.0.0"))
        assertFalse(VersionComparator.isNewer("", "1.0.0"))
    }
}
