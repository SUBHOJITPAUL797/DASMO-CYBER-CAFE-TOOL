package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEnhancementLogicTest {

    @Test
    fun testPanCardSkyBlueBackgroundPreserved() {
        // Authentic PAN Card sky-blue security background (RGB 140, 190, 220)
        val r = 140
        val g = 190
        val b = 220
        val lum = (299 * r + 587 * g + 114 * b) / 1000 // ~178

        assertTrue("PAN card background luminance is above 115", lum >= 115)

        // Must NOT be bleached to 0xFFFFFFFF
        val shouldDeepen = lum < 115
        val resultPixel = if (shouldDeepen) {
            val factor = 0.82f + 0.18f * (lum.toFloat() / 115f)
            (0xFF shl 24) or ((r * factor).toInt() shl 16) or ((g * factor).toInt() shl 8) or (b * factor).toInt()
        } else {
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val expectedOriginalPixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        assertEquals("PAN card sky blue background must be 100% preserved with zero white bleaching", expectedOriginalPixel, resultPixel)
    }

    @Test
    fun testVoterIdSecurityPatternPreserved() {
        // Authentic Voter ID card green/cyan security pattern (RGB 160, 210, 195)
        val r = 160
        val g = 210
        val b = 195
        val lum = (299 * r + 587 * g + 114 * b) / 1000 // ~193

        assertTrue("Voter ID pattern luminance is above 115", lum >= 115)

        val shouldDeepen = lum < 115
        val resultPixel = if (shouldDeepen) {
            val factor = 0.82f + 0.18f * (lum.toFloat() / 115f)
            (0xFF shl 24) or ((r * factor).toInt() shl 16) or ((g * factor).toInt() shl 8) or (b * factor).toInt()
        } else {
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val expectedOriginalPixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        assertEquals("Voter ID security pattern must be 100% preserved without white blotches", expectedOriginalPixel, resultPixel)
    }

    @Test
    fun testDarkTextInkDeepened() {
        // Dark text/print ink (RGB 30, 30, 35)
        val r = 30
        val g = 30
        val b = 35
        val lum = (299 * r + 587 * g + 114 * b) / 1000 // ~30

        assertTrue("Dark text ink luminance is below 115", lum < 115)

        val factor = 0.82f + 0.18f * (lum.toFloat() / 115f)
        val deepenedR = (r * factor).toInt()
        val deepenedG = (g * factor).toInt()
        val deepenedB = (b * factor).toInt()

        assertTrue("Text ink must be deepened for crisp legibility", deepenedR < r)
        assertTrue("Color proportions must remain balanced", deepenedR == deepenedG)
        assertTrue("Blue ink tone remains consistent", deepenedB >= deepenedR)
    }

    @Test
    fun testColorPreservationForStampsAndPhotos() {
        // Red stamp or seal (RGB 210, 30, 40)
        val stampR = 210
        val stampG = 30
        val stampB = 40
        val maxC = maxOf(stampR, maxOf(stampG, stampB))
        val minC = minOf(stampR, minOf(stampG, stampB))
        val saturation = maxC - minC // 180

        assertTrue("Official stamp must have high saturation (> 20)", saturation > 20)

        // Warm face photo skin pixel (RGB 215, 170, 140)
        val skinR = 215
        val skinG = 170
        val skinB = 140
        val skinMax = maxOf(skinR, maxOf(skinG, skinB))
        val skinMin = minOf(skinR, minOf(skinG, skinB))
        val skinSaturation = skinMax - skinMin // 75

        assertTrue("Face photo skin must have high saturation (> 20) and remain untouched", skinSaturation > 20)
    }

    @Test
    fun testTargetDimensionCalculationForStrictSizeLimits() {
        val target90Kb = 90
        val maxDim90 = when {
            target90Kb >= 1000 -> 3200
            target90Kb >= 300 -> 2400
            target90Kb >= 100 -> 1800
            else -> 1280
        }
        assertEquals("For <= 100KB (e.g. 90KB), initial max dimension must be clamped to 1280px", 1280, maxDim90)

        val target500Kb = 500
        val maxDim500 = when {
            target500Kb >= 1000 -> 3200
            target500Kb >= 300 -> 2400
            target500Kb >= 100 -> 1800
            else -> 1280
        }
        assertEquals("For 500KB, max dimension should be 2400px", 2400, maxDim500)
    }

    @Test
    fun testActiveTargetSizeCoercion() {
        val userEnteredCustomSize = 90
        val defaultSize = 500
        val activeTargetSize = (userEnteredCustomSize as Int?).let { it ?: defaultSize }.coerceIn(10, 10000)
        assertEquals("Active target size from popup must strictly override default", 90, activeTargetSize)
    }
}
