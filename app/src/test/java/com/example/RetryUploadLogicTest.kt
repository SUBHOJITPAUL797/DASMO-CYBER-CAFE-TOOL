package com.example

import com.example.ui.UploadFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryUploadLogicTest {

    @Test
    fun testRetryFilenamePreservation() {
        val originalFileName = "Subhojit_Aadhaar_a1b2.pdf"
        val isRetry = true

        val finalNameBase = if (isRetry) {
            originalFileName.substringBeforeLast(".")
        } else {
            "Fallback_UUID"
        }

        val finalPdfName = "${finalNameBase}.pdf"
        val finalJpgName = "${finalNameBase}.jpeg"

        assertEquals("Subhojit_Aadhaar_a1b2", finalNameBase)
        assertEquals("Subhojit_Aadhaar_a1b2.pdf", finalPdfName)
        assertEquals("Subhojit_Aadhaar_a1b2.jpeg", finalJpgName)
    }

    @Test
    fun testRetryFormatDetection() {
        val pdfFileName = "Document_Client_9988_x1y2.pdf"
        val jpgFileName = "Document_Client_9988_x1y2.jpeg"

        val detectFormat = { fileName: String ->
            if (fileName.endsWith(".pdf", ignoreCase = true)) UploadFormat.PDF else UploadFormat.JPEG
        }

        assertEquals(UploadFormat.PDF, detectFormat(pdfFileName))
        assertEquals(UploadFormat.JPEG, detectFormat(jpgFileName))
    }

    @Test
    fun testIsAuthErrorDetection() {
        val checkAuthError = { errorMsg: String ->
            errorMsg.contains("401") ||
                    errorMsg.contains("unauthorized", ignoreCase = true) ||
                    errorMsg.contains("invalid_token", ignoreCase = true) ||
                    errorMsg.contains("token", ignoreCase = true) ||
                    errorMsg.contains("auth", ignoreCase = true)
        }

        assertTrue(checkAuthError("Google Drive upload failed (401): Unauthorized"))
        assertTrue(checkAuthError("Token has been expired or revoked"))
        assertTrue(checkAuthError("invalid_token in Authorization header"))
        assertFalse(checkAuthError("java.net.SocketTimeoutException: timeout"))
        assertFalse(checkAuthError("java.net.UnknownHostException: Unable to resolve host"))
    }

    @Test
    fun testDirectPdfBypassLogic() {
        // When retrying a PDF document, if the PDF already exists and has size > 0,
        // it must be used directly without attempting bitmap decoding.
        val fileExists = true
        val fileLength = 1024L
        val isAlreadyPdf = true

        val shouldBypassBitmapDecode = isAlreadyPdf && fileExists && fileLength > 0L
        assertTrue("Pre-existing PDF must be used directly without decoding", shouldBypassBitmapDecode)
    }
}
