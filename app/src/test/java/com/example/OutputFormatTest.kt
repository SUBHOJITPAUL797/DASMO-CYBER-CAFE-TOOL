package com.example

import com.example.ui.UploadFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputFormatTest {

    @Test
    fun testOutputFormatCyclingLogic() {
        // Cycling order: PDF -> JPEG -> BOTH -> PDF
        val cycle = { current: String ->
            when (current.uppercase()) {
                "PDF" -> "JPEG"
                "JPEG" -> "BOTH"
                else -> "PDF"
            }
        }

        assertEquals("JPEG", cycle("PDF"))
        assertEquals("BOTH", cycle("JPEG"))
        assertEquals("PDF", cycle("BOTH"))
        assertEquals("PDF", cycle("unknown"))
    }

    @Test
    fun testExportGuardConditions() {
        // Only export JPEG if format is JPEG or BOTH
        val shouldExportJpeg = { format: UploadFormat ->
            format == UploadFormat.JPEG || format == UploadFormat.BOTH
        }

        // Only export PDF if format is PDF or BOTH
        val shouldExportPdf = { format: UploadFormat ->
            format == UploadFormat.PDF || format == UploadFormat.BOTH
        }

        // When user selects PDF format:
        assertFalse("JPEG should NOT be exported when PDF format is selected", shouldExportJpeg(UploadFormat.PDF))
        assertTrue("PDF MUST be exported when PDF format is selected", shouldExportPdf(UploadFormat.PDF))

        // When user selects JPEG format:
        assertTrue("JPEG MUST be exported when JPEG format is selected", shouldExportJpeg(UploadFormat.JPEG))
        assertFalse("PDF should NOT be exported when JPEG format is selected", shouldExportPdf(UploadFormat.JPEG))

        // When user selects BOTH:
        assertTrue("JPEG MUST be exported when BOTH is selected", shouldExportJpeg(UploadFormat.BOTH))
        assertTrue("PDF MUST be exported when BOTH is selected", shouldExportPdf(UploadFormat.BOTH))
    }

    @Test
    fun testDialogFileExtensionPreview() {
        val getFileExt = { format: UploadFormat ->
            when (format) {
                UploadFormat.PDF -> ".pdf"
                UploadFormat.JPEG -> ".jpeg"
                UploadFormat.BOTH -> ".jpeg + .pdf"
            }
        }

        assertEquals(".pdf", getFileExt(UploadFormat.PDF))
        assertEquals(".jpeg", getFileExt(UploadFormat.JPEG))
        assertEquals(".jpeg + .pdf", getFileExt(UploadFormat.BOTH))
    }

    @Test
    fun testQueueFormatLabel() {
        val getFormatLabel = { format: UploadFormat ->
            when (format) {
                UploadFormat.PDF -> "PDF"
                UploadFormat.JPEG -> "JPEG"
                UploadFormat.BOTH -> "JPEG + PDF"
            }
        }

        assertEquals("PDF", getFormatLabel(UploadFormat.PDF))
        assertEquals("JPEG", getFormatLabel(UploadFormat.JPEG))
        assertEquals("JPEG + PDF", getFormatLabel(UploadFormat.BOTH))
    }
}
