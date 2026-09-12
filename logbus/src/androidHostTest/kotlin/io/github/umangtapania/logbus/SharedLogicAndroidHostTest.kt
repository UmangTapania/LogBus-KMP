package io.github.umangtapania.logbus

import kotlin.test.Test
import kotlin.test.assertTrue

class SharedLogicAndroidHostTest {

    @Test
    fun nowMillisLinksAndReturnsPositive() {
        assertTrue(nowMillis() > 0)
    }
}
