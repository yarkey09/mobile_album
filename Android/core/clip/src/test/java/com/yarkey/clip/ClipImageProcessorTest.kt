package com.yarkey.clip

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipImageProcessorTest {
    @Test
    fun `center crop trims landscape image equally from both sides`() {
        assertEquals(
            CenterCropBounds(left = 350, top = 0, right = 1250, bottom = 900),
            ClipImageProcessor.centerCropBounds(width = 1600, height = 900),
        )
    }

    @Test
    fun `center crop trims portrait image equally from top and bottom`() {
        assertEquals(
            CenterCropBounds(left = 0, top = 350, right = 900, bottom = 1250),
            ClipImageProcessor.centerCropBounds(width = 900, height = 1600),
        )
    }

    @Test
    fun `center crop keeps square image unchanged`() {
        assertEquals(
            CenterCropBounds(left = 0, top = 0, right = 1024, bottom = 1024),
            ClipImageProcessor.centerCropBounds(width = 1024, height = 1024),
        )
    }
}
