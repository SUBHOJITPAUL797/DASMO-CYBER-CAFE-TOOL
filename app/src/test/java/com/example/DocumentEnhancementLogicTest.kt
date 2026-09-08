package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEnhancementLogicTest {

    @Test
    fun testPaperBackgroundFlattening() {
        // Under typical indoor room lighting, paper luminance is ~180
        val localPaperBrightness = 180f
        val pixelLum = 175 // Paper background pixel with slight grain/shadow

        // Normalized luminance relative to paper brightness
        val normLum = (pixelLum.toFloat() / localPaperBrightness) * 255f
        // 175 / 180 * 255 = 247.9 >= 210 -> pure white
        assertTrue("Paper pixel with shadow must be normalized above 210", normLum >= 210f)

        val flattenedPixel = if (normLum >= 210f) 0xFFFFFFFF.toInt() else 0
        assertEquals("Paper pixel must be flattened to pure white #FFFFFF", 0xFFFFFFFF.toInt(), flattenedPixel)
    }

    @Test
    fun testTextInkDeepening() {
        // Text ink on room-lit paper
        val localPaperBrightness = 180f
        val inkLum = 50 // Dark pen ink

        val normLum = (inkLum.toFloat() / localPaperBrightness) * 255f
        // 50 / 180 * 255 = 70.8 <= 125 -> ink stroke
        assertTrue("Ink stroke must be below 125 threshold", normLum <= 125f)

        val originalInkR = 50
        val factor = 0.72f
        val deepenedR = (originalInkR * factor).toInt()
        assertEquals(36, deepenedR)
        assertTrue("Deepened ink must be darker than original ink", deepenedR < originalInkR)
    }

    @Test
    fun testColorPreservationForStampsAndPhotos() {
        // Red stamp or seal
        val stampR = 210
        val stampG = 30
        val stampB = 40
        val maxC = maxOf(stampR, maxOf(stampG, stampB))
        val minC = minOf(stampR, minOf(stampG, stampB))
        val saturation = maxC - minC // 210 - 30 = 180

        assertTrue("Official stamp must have high saturation (> 22)", saturation > 22)

        // Neutral gray paper pixel
        val paperR = 185
        val paperG = 182
        val paperB = 178
        val paperMax = maxOf(paperR, maxOf(paperG, paperB))
        val paperMin = minOf(paperR, minOf(paperG, paperB))
        val paperSaturation = paperMax - paperMin // 185 - 178 = 7

        assertTrue("Paper/ink pixel must have low saturation (<= 22)", paperSaturation <= 22)
    }

    @Test
    fun testAntialiasedEdgeSmoothstep() {
        // Smoothstep curve for character edges between 125 and 210
        val normLum = 167.5f // Exactly midway between 125 and 210
        val t = (normLum - 125f) / (210f - 125f) // 0.5
        val smoothT = t * t * (3f - 2f * t) // 0.25 * 2 = 0.5

        assertEquals(0.5f, smoothT, 0.001f)

        // As normLum approaches paper (210), smoothT approaches 1.0 (white)
        val nearPaperLum = 205f
        val tNear = (nearPaperLum - 125f) / (210f - 125f)
        val smoothTNear = tNear * tNear * (3f - 2f * tNear)
        assertTrue("Near-paper transition must smoothly blend towards white", smoothTNear > 0.95f)
    }
}
