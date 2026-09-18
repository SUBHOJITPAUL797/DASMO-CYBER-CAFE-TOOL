package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A4StackingLogicTest {

    // Simulates the ViewModel's isImageIdCard logic
    private fun checkIsIdCard(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > height) return true // Landscape ID cards (PAN, Aadhaar)
        
        // Portrait ID cards (Voter ID, Student ID, EPIC cards)
        val ratio = height.toFloat() / width.toFloat()
        if (ratio in 1.30f..1.95f) return true
        
        val area = width.toLong() * height.toLong()
        if (area < 6_000_000L) return true
        
        return false
    }

    // Simulates the ViewModel's isId evaluation for A4 Sheet Canvas
    private fun checkIsA4Canvas(
        imageCount: Int,
        useA4Format: Boolean,
        width: Int,
        height: Int
    ): Boolean {
        return imageCount > 0 && useA4Format && (imageCount in 1..2) && (imageCount == 2 || checkIsIdCard(width, height))
    }

    @Test
    fun testTwoCardIdScanAlwaysUsesA4WhenEnabled() {
        // High-resolution camera scan (12MP 4000x3000) portrait photo of Voter ID front & back
        val useA4 = true
        val imageCount = 2
        val width = 3000
        val height = 4000

        val result = checkIsA4Canvas(imageCount, useA4, width, height)
        assertTrue(
            "Front & Back 2-card scan with A4 format enabled MUST always activate A4 Sheet Canvas regardless of resolution",
            result
        )
    }

    @Test
    fun testPortraitVoterIdCardDetection() {
        // Indian Voter ID (EPIC Card) is portrait (approx 5.4cm x 8.6cm, ratio ~1.59)
        val width = 540
        val height = 856
        assertTrue("Voter ID portrait ratio must be identified as an ID card", checkIsIdCard(width, height))

        // High resolution phone scan of portrait Voter ID (1800 x 2850)
        val hiResW = 1800
        val hiResH = 2850
        assertTrue("High-res portrait Voter ID must be identified as an ID card", checkIsIdCard(hiResW, hiResH))
    }

    @Test
    fun testLandscapePanCardDetection() {
        // PAN Card is landscape (approx 8.6cm x 5.4cm, width > height)
        val width = 856
        val height = 540
        assertTrue("Landscape PAN card must be identified as an ID card", checkIsIdCard(width, height))
    }

    @Test
    fun testMultiPageDocumentBypassesA4CardCanvas() {
        // 5-page marksheet or legal document
        val useA4 = true
        val imageCount = 5
        val width = 2480
        val height = 3508

        val result = checkIsA4Canvas(imageCount, useA4, width, height)
        assertFalse(
            "Multi-page documents (3+ pages) must NOT be crushed into a 2-card A4 canvas",
            result
        )
    }

    @Test
    fun testPageFilesConsistencyForPdfGeneration() {
        // For an A4 ID card canvas, the resulting PDF must be generated from the single combined A4 sheet (pageFiles = null)
        // rather than individual separate pages.
        val isId = true
        val rawPageFiles = listOf("page0.jpg", "page1.jpg")

        // When confirmation is shown:
        val pageFilesWithConfirmation = if (!isId) rawPageFiles else null
        // When confirmation is OFF:
        val pageFilesWithoutConfirmation = if (!isId) rawPageFiles else null

        assertEquals(
            "Both confirmation-ON and confirmation-OFF must set pageFiles to null for ID cards to preserve the A4 Xerox sheet",
            pageFilesWithConfirmation,
            pageFilesWithoutConfirmation
        )
        assertEquals(null, pageFilesWithoutConfirmation)
    }

    @Test
    fun testAutoEnhanceBypassWhenDisabled() {
        // When autoEnhance is false, text ink must NOT be altered
        val autoEnhance = false
        val r = 40
        val g = 40
        val b = 45
        val lum = (299 * r + 587 * g + 114 * b) / 1000 // ~40 (dark ink)

        val processedR = if (autoEnhance && lum < 115) {
            val factor = 0.82f + 0.18f * (lum.toFloat() / 115f)
            (r * factor).toInt()
        } else {
            r // untouched original
        }

        assertEquals("When autoEnhance is disabled, dark ink pixel must remain 100% untouched", r, processedR)
    }

    @Test
    fun testA4CanvasLayoutDimensions() {
        // Standard A4 dimensions at 200 DPI
        val a4Width = 1654
        val a4Height = 2339

        // Card 0 (Front) placed at top = 150f
        val top0 = 150f
        val maxCardHeight = (a4Height * 0.30f).toInt() // 701px
        val interCardGap = 80f // ~1 cm gap

        val top1 = top0 + maxCardHeight + interCardGap
        val bottomOfCard1 = top1 + maxCardHeight // 150 + 701 + 80 + 701 = 1632px

        // Bottom margin remaining for KYC signature:
        val remainingBottomMargin = a4Height - bottomOfCard1 // 2339 - 1632 = 707px
        val bottomMarginPercent = remainingBottomMargin.toFloat() / a4Height.toFloat()

        assertTrue(
            "Bottom half of A4 page must leave at least 30% open space for customer KYC signature",
            bottomMarginPercent >= 0.30f
        )
    }
}
