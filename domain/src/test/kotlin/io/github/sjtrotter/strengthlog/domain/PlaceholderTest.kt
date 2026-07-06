package io.github.sjtrotter.strengthlog.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {

    @Test
    fun `module name is pinned`() {
        assertEquals("domain", Placeholder.MODULE_NAME)
    }
}
