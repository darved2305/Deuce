package com.elevate.app.domain.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test fun match_only() {
        assertEquals(DeepLinkTarget("m_123"), DeepLink.parse("elevate://m/m_123"))
        assertEquals(DeepLinkTarget("m_123"), DeepLink.parse("elevate://m/m_123/"))
    }

    @Test fun rally_with_time() {
        assertEquals(DeepLinkTarget("m_1", "r_4", 83500L), DeepLink.parse("elevate://m/m_1/r/r_4?t_ms=83500"))
    }

    @Test fun rally_without_time_and_match_with_time() {
        assertEquals(DeepLinkTarget("m_1", "r_4", null), DeepLink.parse("elevate://m/m_1/r/r_4"))
        assertEquals(DeepLinkTarget("m_1", null, 0L), DeepLink.parse("elevate://m/m_1?t_ms=0"))
    }

    @Test fun time_is_found_among_other_query_parameters() {
        assertEquals(DeepLinkTarget("m_1", "r_4", 900L), DeepLink.parse("elevate://m/m_1/r/r_4?utm=x&t_ms=900"))
    }

    @Test fun bad_time_is_ignored_but_the_match_still_opens() {
        assertEquals(DeepLinkTarget("m_1", "r_4", null), DeepLink.parse("elevate://m/m_1/r/r_4?t_ms=abc"))
        assertEquals(DeepLinkTarget("m_1", "r_4", null), DeepLink.parse("elevate://m/m_1/r/r_4?t_ms=-5"))
        assertEquals(DeepLinkTarget("m_1", "r_4", null), DeepLink.parse("elevate://m/m_1/r/r_4?t_ms="))
    }

    @Test fun bad_input_is_null() {
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse("   "))
        assertNull(DeepLink.parse("not a uri"))
        assertNull(DeepLink.parse("https://m/m_1"))
        assertNull(DeepLink.parse("elevate://x/m_1"))
        assertNull(DeepLink.parse("elevate://m"))
        assertNull(DeepLink.parse("elevate://m/"))
        assertNull(DeepLink.parse("elevate://m/m_1/r"))
        assertNull(DeepLink.parse("elevate://m/m_1/x/r_4"))
        assertNull(DeepLink.parse("elevate://m/m_1/r/r_4/extra"))
        assertNull(DeepLink.parse("elevate://m/m%201"))
        assertNull(DeepLink.parse("elevate://m/m_1/r/"))
    }
}
