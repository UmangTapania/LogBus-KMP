package com.github.umangtapania.logbus

import kotlin.test.Test
import kotlin.test.assertTrue

class SharedLogicIOSTest {

    @Test
    fun nowMillisLinksAndReturnsPositive() {
        assertTrue(nowMillis() > 0)
    }
}
