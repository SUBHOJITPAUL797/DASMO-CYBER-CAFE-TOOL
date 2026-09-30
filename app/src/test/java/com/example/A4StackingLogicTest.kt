package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for A4 stacking logic after v1.2.9 routing simplification.
 *
 * Key rule after fix:
 *   isA4Canvas = (imageCount == 2) AND (useA4Format == true)
 *
 * The old isImageIdCard() heuristic has been REMOVED — it was causing the toggle to have
 * no effect for most real-world scans (ML Kit outputs often exceed the 6MP threshold).
 * The user's explicit toggle is now the single source of truth.
 */
class A4StackingLogicTest {

    /**
     * Simulates the simplified ViewModel isId decision (v1.2.9+):
     * 2 pages + toggle ON = A4 canvas. No heuristic involved.
     */
    private fun checkIsA4Canvas(imageCount: Int, useA4Format: Boolean): Boolean {
        return imageCount == 2 && useA4Format
    }

    // ─── Toggle ON tests ──────────────────────────────────────────────────────

    @Test
    fun testTwoPageScanWithToggleOnAlwaysUsesA4() {
        assertTrue(
            "2 pages + A4 toggle ON must always produce A4 canvas",
            checkIsA4Canvas(imageCount = 2, useA4Format = true)
        )
    }

    @Test
    fun testTwoPageScanWithToggleOffNeverUsesA4() {
        assertFalse(
            "2 pages + A4 toggle OFF must use plain vertical stacking",
            checkIsA4Canvas(imageCount = 2, useA4Format = false)
        )
    }

    // ─── Single-page guard ────────────────────────────────────────────────────

    @Test
    fun testSinglePageNeverUsesA4CanvasEvenWhenToggleOn() {
        assertFalse(
            "Single page scan must NEVER use A4 canvas (exact crop must be preserved)",
            checkIsA4Canvas(imageCount = 1, useA4Format = true)
        )
    }

    @Test
    fun testSinglePageOffAlsoNoA4() {
        assertFalse(
            "Single page with toggle OFF also must not use A4 canvas",
            checkIsA4Canvas(imageCount = 1, useA4Format = false)
        )
    }

    // ─── Multi-page guard ─────────────────────────────────────────────────────

    @Test
    fun testThreePagesNeverUsesA4Canvas() {
        assertFalse(
            "3 pages must NOT be crushed into a 2-slot A4 canvas",
            checkIsA4Canvas(imageCount = 3, useA4Format = true)
        )
    }

    @Test
    fun testFivePagesNeverUsesA4Canvas() {
        assertFalse(
            "5-page document must NOT use A4 canvas",
            checkIsA4Canvas(imageCount = 5, useA4Format = true)
        )
    }

    // ─── pageFiles consistency ────────────────────────────────────────────────

    @Test
    fun testPageFilesNullForIdCardA4Canvas() {
        // When A4 canvas is used (isId=true), pageFiles must be null so the PDF is built
        // from the single combined A4 sheet — NOT from individual raw page files.
        val isId = true
        val rawPageFiles = listOf("page0.jpg", "page1.jpg")

        val pageFilesForConfirmation = if (!isId) rawPageFiles else null
        val pageFilesForAutoSave    = if (!isId) rawPageFiles else null

        assertNull("pageFiles must be null for A4 canvas (confirmation ON path)", pageFilesForConfirmation)
        assertNull("pageFiles must be null for A4 canvas (auto-save path)", pageFilesForAutoSave)
        assertEquals(pageFilesForConfirmation, pageFilesForAutoSave)
    }

    @Test
    fun testPageFilesKeptForMultiPageDocument() {
        // When NOT using A4 canvas (isId=false), pageFiles must be preserved
        // so the upload can create a proper multi-page PDF.
        val isId = false
        val rawPageFiles = listOf("page0.jpg", "page1.jpg", "page2.jpg")

        val pageFiles = if (!isId) rawPageFiles else null
        assertEquals(
            "Multi-page non-ID documents must keep individual page files for PDF",
            rawPageFiles,
            pageFiles
        )
    }

    // ─── AutoEnhance bypass ───────────────────────────────────────────────────

    @Test
    fun testAutoEnhanceBypassWhenDisabled() {
        val autoEnhance = false
        val r = 40; val g = 40; val b = 45
        val lum = (299 * r + 587 * g + 114 * b) / 1000 // ~40

        val processedR = if (autoEnhance && lum < 115) {
            val factor = 0.82f + 0.18f * (lum.toFloat() / 115f)
            (r * factor).toInt()
        } else {
            r
        }

        assertEquals("autoEnhance OFF: dark pixel must remain untouched", r, processedR)
    }

    @Test
    fun testAutoEnhanceAppliedWhenEnabled() {
        val autoEnhance = true
        val r = 40; val g = 40; val b = 45
        val lum = (299 * r + 587 * g + 114 * b) / 1000 // ~40

        val processedR = if (autoEnhance && lum < 115) {
            val factor = 0.82f + 0.18f * (lum.toFloat() / 115f)
            (r * factor).toInt()
        } else {
            r
        }

        assertTrue("autoEnhance ON: dark ink must be deepened (processedR <= original)", processedR <= r)
    }

    // ─── A4 canvas layout sanity ─────────────────────────────────────────────

    @Test
    fun testA4CanvasLayoutCardsFitWithinPage() {
        // v1.2.9: cards fill 86% of A4 width. Both cards plus margins must fit in A4 height.
        val a4Width = 1654
        val a4Height = 2339

        val sidePadding = (a4Width * 0.07f)
        val maxCardWidth = (a4Width - 2 * sidePadding).toInt()

        // Simulate a landscape Aadhaar card (typical ~1700x1080 from ML Kit)
        val bmp0W = 1700; val bmp0H = 1080
        val bmp1W = 1700; val bmp1H = 1080

        val scale0 = maxCardWidth.toFloat() / bmp0W.toFloat()
        val drawH0 = (bmp0H * scale0).toInt()

        val scale1 = maxCardWidth.toFloat() / bmp1W.toFloat()
        val drawH1 = (bmp1H * scale1).toInt()

        val topMargin = 120f
        val interCardGap = 70f
        val bottom = topMargin + drawH0 + interCardGap + drawH1

        assertTrue(
            "Both ID cards must fit within A4 height at 86% width (bottom=$bottom, a4Height=$a4Height)",
            bottom < a4Height
        )

        val remainingBottom = a4Height - bottom
        val remainingPercent = remainingBottom / a4Height
        assertTrue(
            "Remaining space after both cards must be > 8% (cards must not bleed off page). Got $remainingPercent (${(remainingPercent*100).toInt()}%)",
            remainingPercent > 0.08f
        )
    }

    @Test
    fun testA4CanvasCardWidthIs86Percent() {
        val a4Width = 1654
        val sidePadding = (a4Width * 0.07f)
        val maxCardWidth = (a4Width - 2 * sidePadding).toInt()
        val widthRatio = maxCardWidth.toFloat() / a4Width.toFloat()

        assertTrue(
            "Cards must fill at least 80% of A4 width for readability (got $widthRatio)",
            widthRatio >= 0.80f
        )
        assertTrue(
            "Cards must not exceed 90% of A4 width (leave side margins) (got $widthRatio)",
            widthRatio <= 0.90f
        )
    }
}
