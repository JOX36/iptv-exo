package com.jox3.tvdesktop

import com.jox3.tvdesktop.player.MultiView
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiViewTest {
    @Test fun maximoCuatroYDisenos() {
        assertEquals(4, MultiView.MAX)
        assertEquals(listOf(2, 4, 4), MultiView.Layout.entries.map { it.slots })
    }
}
