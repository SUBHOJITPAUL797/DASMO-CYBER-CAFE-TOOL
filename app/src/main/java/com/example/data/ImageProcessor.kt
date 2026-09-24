package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object ImageProcessor {
    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)

        var inSampleSize = 1
        val height = options.outHeight
        val width = options.outWidth

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        options.inSampleSize = inSampleSize
        options.inJustDecodeBounds = false
        var bitmap = BitmapFactory.decodeFile(path, options) ?: return null

        try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            if (degrees != 0) {
                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return bitmap
    }

    fun fixImageOrientation(context: Context, uri: Uri, outputFile: File): File {
        try {
            var inputStream = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.use { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            // KEY FIX: If no rotation is needed, do a raw byte copy.
            // This is critical for ML Kit scanner URIs where the crop IS the URI content —
            // decoding through BitmapFactory and re-encoding would read the full original photo
            // instead of the cropped output, causing the full-page upload bug.
            if (degrees == 0) {
                return copyRaw(context, uri, outputFile)
            }

            inputStream = context.contentResolver.openInputStream(uri)
            var bitmap = inputStream?.use { BitmapFactory.decodeStream(it) } ?: return copyRaw(context, uri, outputFile)

            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }

            saveBitmap(bitmap, outputFile)
            bitmap.recycle()
            return outputFile
        } catch (e: Throwable) {
            e.printStackTrace()
            return copyRaw(context, uri, outputFile)
        }
    }

    fun fixFileOrientation(file: File): File {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return file

            var modified = false
            if (degrees != 0) {
                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
                modified = true
            }

            if (modified) {
                saveBitmap(bitmap, file)
            }
            bitmap.recycle()
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return file
        }
    }

    private fun copyRaw(context: Context, uri: Uri, outputFile: File): File {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return outputFile
    }

    suspend fun combineImages(paths: List<String>, outputFile: File): File? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        if (paths.size == 1) {
            try {
                File(paths[0]).copyTo(outputFile, overwrite = true)
                return@withContext outputFile
            } catch (e: Exception) {
                val bmp = decodeSampledBitmap(paths[0], 2400, 2400) // Fallback if copy fails
                if (bmp != null) {
                    saveBitmap(bmp, outputFile)
                    bmp.recycle()
                }
                return@withContext outputFile
            }
        }

        // Higher resolution for professional quality
        val bitmaps = paths.mapNotNull { decodeSampledBitmap(it, 2000, 2000) }
        if (bitmaps.isEmpty()) return@withContext null

        try {
            val margin = 40
            val maxWidth = bitmaps.maxOf { it.width } + (margin * 2)
            val totalHeight = bitmaps.sumOf { it.height } + (margin * (bitmaps.size + 1))

            val combinedBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combinedBitmap)
            canvas.drawColor(0xFFFFFFFF.toInt()) // Crisp white background
            
            var currentHeight = margin.toFloat()
            for (bitmap in bitmaps) {
                val left = (maxWidth - bitmap.width) / 2f
                canvas.drawBitmap(bitmap, left, currentHeight, null)
                currentHeight += bitmap.height + margin
                bitmap.recycle() // Release original individual bitmap immediately
            }

            saveBitmap(combinedBitmap, outputFile)
            combinedBitmap.recycle() // Release combined bitmap canvas source
            outputFile
        } catch (e: Throwable) {
            e.printStackTrace()
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    suspend fun combineImagesToA4(paths: List<String>, outputFile: File): File? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        if (paths.size < 2) {
            // A single image must never be shrunken onto an A4 page with blank margins.
            return@withContext combineImages(paths, outputFile)
        }
        
        var a4Bitmap: Bitmap? = null
        var bitmaps: List<Bitmap> = emptyList()
        try {
            // Standard A4 aspect ratio is 1:1.414. We use 1654 x 2339 (excellent balance of size & high scan document definition)
            val a4Width = 1654
            val a4Height = 2339
            
            a4Bitmap = Bitmap.createBitmap(a4Width, a4Height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(a4Bitmap)
            canvas.drawColor(0xFFFFFFFF.toInt()) // crisp white paper background

            bitmaps = paths.mapNotNull { decodeSampledBitmap(it, a4Width, a4Height) }
            if (bitmaps.size < 2) {
                bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                a4Bitmap.recycle()
                // Fallback to regular combine if one of the images failed decoding
                return@withContext combineImages(paths, outputFile)
            }
            val margin = 50f
            // Professional Cyber Cafe A4 ID Card Xerox Placement:
            // Front & Back are placed together in the UPPER HALF of the A4 page with uniform widths,
            // neatly centered horizontally, separated by a clean 1 cm (~80 px) gap.
            // The entire lower half is left clean and open (standard for self-attestation signatures & bank KYC).
            val bmp0 = bitmaps[0]
            val bmp1 = bitmaps[1]

            val isPortrait0 = bmp0.height > bmp0.width
            val isPortrait1 = bmp1.height > bmp1.width
            val isPortrait = isPortrait0 || isPortrait1

            // Target uniform width for ID cards on A4: ~50% of A4 width for landscape, ~38% for portrait
            val targetCardWidth = if (isPortrait) (a4Width * 0.38f).toInt() else (a4Width * 0.52f).toInt()
            val maxCardHeight = (a4Height * 0.30f).toInt()

            // Card 0 (Front)
            val scale0 = kotlin.math.min(
                targetCardWidth / bmp0.width.toFloat(),
                maxCardHeight / bmp0.height.toFloat()
            )
            val drawW0 = (bmp0.width * scale0).toInt()
            val drawH0 = (bmp0.height * scale0).toInt()
            val left0 = (a4Width - drawW0) / 2f
            val top0 = 150f // Standard top margin on A4 sheet

            if (drawW0 > 0 && drawH0 > 0) {
                val scaledBmp = Bitmap.createScaledBitmap(bmp0, drawW0, drawH0, true)
                canvas.drawBitmap(scaledBmp, left0, top0, null)
                scaledBmp.recycle()
            }
            bmp0.recycle()

            // Card 1 (Back)
            val scale1 = kotlin.math.min(
                targetCardWidth / bmp1.width.toFloat(),
                maxCardHeight / bmp1.height.toFloat()
            )
            val drawW1 = (bmp1.width * scale1).toInt()
            val drawH1 = (bmp1.height * scale1).toInt()
            val left1 = (a4Width - drawW1) / 2f
            val interCardGap = 80f // Clean ~1 cm gap between Front and Back
            val top1 = top0 + drawH0 + interCardGap

            if (drawW1 > 0 && drawH1 > 0) {
                val scaledBmp = Bitmap.createScaledBitmap(bmp1, drawW1, drawH1, true)
                canvas.drawBitmap(scaledBmp, left1, top1, null)
                scaledBmp.recycle()
            }
            bmp1.recycle()

            // If extra unused bitmaps were loaded, recycle them too
            if (bitmaps.size > 2) {
                for (i in 2 until bitmaps.size) {
                    if (!bitmaps[i].isRecycled) bitmaps[i].recycle()
                }
            }

            saveBitmap(a4Bitmap, outputFile)
            a4Bitmap.recycle()
            outputFile
        } catch (e: Throwable) {
            e.printStackTrace()
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            if (a4Bitmap != null && !a4Bitmap.isRecycled) a4Bitmap.recycle()
            combineImages(paths, outputFile)
        }
    }

    private fun saveBitmap(bitmap: Bitmap, file: File, format: String = "JPEG") {
        val compressFormat = if (format == "WEBP") {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        } else {
            Bitmap.CompressFormat.JPEG
        }
        // BUG FIX: use{} ensures stream is ALWAYS closed, even if compress() throws (OOM etc.)
        FileOutputStream(file).use { fos ->
            bitmap.compress(compressFormat, 95, fos)
        }
    }

    suspend fun compressImage(
        file: File,
        targetSizeKb: Int,
        format: String = "JPEG",
        autoEnhance: Boolean = true
    ): File = withContext(Dispatchers.IO) {
        var bmp = BitmapFactory.decodeFile(file.absolutePath) ?: throw Exception("Failed to decode image file structure")
        val targetSizeBytes = targetSizeKb * 1024

        val compressFormat = if (format == "WEBP") {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        } else {
            Bitmap.CompressFormat.JPEG
        }

        // 1. Smart Resolution Optimization
        // Massive camera images (e.g. 4000px+) suffer severe low-quality degradation to fit under small KB targets.
        // For large target size (>=1000KB), keep up to 3200px; otherwise 2400px preserves 1080p textual sharpness.
        val maxTargetDimension = if (targetSizeKb >= 1000) 3200 else 2400
        if (bmp.width > maxTargetDimension || bmp.height > maxTargetDimension) {
            val scale = maxTargetDimension.toFloat() / kotlin.math.max(bmp.width, bmp.height)
            val scaledW = (bmp.width * scale).toInt()
            val scaledH = (bmp.height * scale).toInt()
            if (scaledW > 0 && scaledH > 0) {
                val prevBmp = bmp
                bmp = Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
                if (prevBmp != bmp) {
                    prevBmp.recycle()
                }
            }
        }

        // Apply Smart Text Enhancement only when autoEnhance is enabled
        if (autoEnhance) {
            val enhancedBmp = applySmartTextEnhancement(bmp)
            if (enhancedBmp != bmp) {
                bmp.recycle()
                bmp = enhancedBmp
            }
        }

        // 2. High-Fidelity Quality Search (Binary Search for optimized compression ratio)
        // Search up to 98% quality to ensure maximum crispness without unnecessary degradation
        var stream = ByteArrayOutputStream()
        var lowQuality = 70
        var highQuality = 98
        var bestQuality = 88
        
        while (lowQuality <= highQuality) {
            val midQuality = (lowQuality + highQuality) / 2
            val tempStream = ByteArrayOutputStream()
            bmp.compress(compressFormat, midQuality, tempStream)
            
            if (tempStream.size() <= targetSizeBytes) {
                bestQuality = midQuality
                stream = tempStream
                lowQuality = midQuality + 1 // try higher quality if possible
            } else {
                highQuality = midQuality - 1 // must compress more
            }
        }

        // 3. Fallback downscaling loop (only if targetSize is extremely low like <100kb and we need to fit)
        if (stream.size() == 0 || stream.size() > targetSizeBytes) {
            var quality = bestQuality
            stream = ByteArrayOutputStream()
            bmp.compress(compressFormat, quality, stream)
            
            while (stream.size() > targetSizeBytes && quality > 45) {
                quality -= 5
                stream = ByteArrayOutputStream()
                bmp.compress(compressFormat, quality, stream)
            }

            // Extreme dimensional downscaling is the absolute last resort to keep text legible
            while (stream.size() > targetSizeBytes) {
                val width = (bmp.width * 0.9).toInt()
                val height = (bmp.height * 0.9).toInt()
                if (width <= 0 || height <= 0) break
                val prevBmp = bmp
                bmp = Bitmap.createScaledBitmap(bmp, width, height, true)
                if (prevBmp != bmp) {
                    prevBmp.recycle()
                }
                stream = ByteArrayOutputStream()
                bmp.compress(compressFormat, quality, stream)
            }
        }

        val compressedFile = File(file.parentFile ?: File("."), "compressed_${file.name}")
        // BUG FIX: use{} ensures stream is ALWAYS closed even if write() throws
        FileOutputStream(compressedFile).use { fos ->
            fos.write(stream.toByteArray())
        }
        
        bmp.recycle() // Release decoder bitmap allocation
        compressedFile
    }

    /**
     * Authentic Document Contrast & Text Enhancement Engine:
     * 1. Preserves 100% of authentic document and ID card colors (PAN card sky-blue, Voter ID green/pink,
     *    marksheet cream, and security watermarks).
     * 2. Zero White Bleaching: NEVER replaces background pixels with synthetic #FFFFFF, ensuring
     *    documents look 100% authentic and are fully accepted by government verification portals and KYC officers.
     * 3. Ink Deepening: Only deepens dark text/pen ink strokes (lum < 115) for razor-sharp readability.
     * 4. Photo & Stamp Protection: Facial photos, official seals, and colorful graphics are left completely natural.
     */
    fun applySmartTextEnhancement(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height

        // Downscale massive camera images (e.g. 12MP-24MP) before pixel enhancement to bound memory
        val maxDim = 2400
        val workingBitmap = if (width > maxDim || height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(width, height)
            val sw = (width * scale).toInt()
            val sh = (height * scale).toInt()
            Bitmap.createScaledBitmap(src, sw, sh, true)
        } else {
            src
        }

        val w = workingBitmap.width
        val h = workingBitmap.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // Process row by row with a single row buffer instead of allocating a 48MB array at once!
        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            workingBitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = rowPixels[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val maxC = maxOf(r, maxOf(g, b))
                val minC = minOf(r, minOf(g, b))
                val saturation = maxC - minC

                if (saturation <= 20) {
                    val lum = (299 * r + 587 * g + 114 * b) / 1000
                    if (lum < 115) {
                        val factor = 0.82f + 0.18f * (lum.toFloat() / 115f)
                        val newR = (r * factor).toInt().coerceIn(0, 255)
                        val newG = (g * factor).toInt().coerceIn(0, 255)
                        val newB = (b * factor).toInt().coerceIn(0, 255)
                        rowPixels[x] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
                    }
                }
            }
            result.setPixels(rowPixels, 0, w, 0, y, w, 1)
        }

        if (workingBitmap != src) {
            workingBitmap.recycle()
        }
        return result
    }

    suspend fun convertToMultiPagePdf(
        imageFiles: List<File>,
        outputFile: File,
        targetSizeKb: Int,
        autoEnhance: Boolean = true
    ): File? = withContext(Dispatchers.IO) {
        if (imageFiles.isEmpty()) return@withContext null
        val targetSizeBytes = targetSizeKb * 1024

        // 1. Pre-process and optimize each page sequentially to disk to keep RAM usage bounded to ONE page at a time
        val tempDir = File(outputFile.parentFile ?: File("."), "temp_pdf_pages_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val processedPageFiles = mutableListOf<File>()

        try {
            for ((idx, imageFile) in imageFiles.withIndex()) {
                val bmp = decodeSampledBitmap(imageFile.absolutePath, 1654, 2339) ?: continue
                val enhanced = if (autoEnhance) applySmartTextEnhancement(bmp) else bmp
                if (enhanced != bmp) bmp.recycle()

                val tempPageFile = File(tempDir, "page_$idx.jpg")
                FileOutputStream(tempPageFile).use { fos ->
                    enhanced.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                }
                enhanced.recycle()
                processedPageFiles.add(tempPageFile)
            }

            if (processedPageFiles.isEmpty()) return@withContext null

            // 2. Binary search for scale to achieve target size
            var bestStream: ByteArrayOutputStream? = null
            var smallestStream: ByteArrayOutputStream? = null
            var smallestSize = Long.MAX_VALUE
            var lowScale = 0.15f
            var highScale = 1.0f
            var iterations = 0

            while (lowScale <= highScale && iterations < 15) {
                iterations++
                val midScale = (lowScale + highScale) / 2
                val document = PdfDocument()
                var canProcess = true

                for ((index, pageFile) in processedPageFiles.withIndex()) {
                    val pageBmp = BitmapFactory.decodeFile(pageFile.absolutePath)
                    if (pageBmp == null) {
                        canProcess = false
                        break
                    }

                    val width = (pageBmp.width * midScale).toInt()
                    val height = (pageBmp.height * midScale).toInt()
                    if (width <= 0 || height <= 0) {
                        pageBmp.recycle()
                        canProcess = false
                        break
                    }

                    val scaledBitmap = if (midScale < 1.0f) {
                        Bitmap.createScaledBitmap(pageBmp, width, height, true)
                    } else {
                        pageBmp
                    }

                    val pdfWidth = 595
                    val pdfHeight = (595f * (scaledBitmap.height.toFloat() / scaledBitmap.width.toFloat())).toInt()

                    val pageInfo = PdfDocument.PageInfo.Builder(pdfWidth, pdfHeight, index + 1).create()
                    val page = document.startPage(pageInfo)

                    val destRect = android.graphics.RectF(0f, 0f, pdfWidth.toFloat(), pdfHeight.toFloat())
                    page.canvas.drawBitmap(scaledBitmap, null, destRect, null)
                    document.finishPage(page)

                    if (scaledBitmap != pageBmp) {
                        scaledBitmap.recycle()
                    }
                    pageBmp.recycle()
                }

                if (!canProcess) {
                    document.close()
                    break
                }

                val tempStream = ByteArrayOutputStream()
                document.writeTo(tempStream)
                document.close()

                val size = tempStream.size().toLong()
                if (size < smallestSize) {
                    smallestSize = size
                    smallestStream = tempStream
                }

                if (size <= targetSizeBytes) {
                    bestStream = tempStream
                    lowScale = midScale + 0.05f
                } else {
                    highScale = midScale - 0.05f
                }
            }

            // Fallback to smallestStream if no iteration strictly reached <= targetSizeBytes
            val finalStream = bestStream ?: smallestStream
            if (finalStream != null && finalStream.size() > 0) {
                FileOutputStream(outputFile).use { fos ->
                    fos.write(finalStream.toByteArray())
                }
                return@withContext outputFile
            }

            return@withContext null
        } finally {
            try { tempDir.deleteRecursively() } catch (e: Exception) {}
        }
    }

    suspend fun convertToPdf(
        imageFile: File,
        outputFile: File,
        targetSizeKb: Int,
        autoEnhance: Boolean = true
    ): File? = withContext(Dispatchers.IO) {
        var originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null
        val targetSizeBytes = targetSizeKb * 1024
        
        if (autoEnhance) {
            val enhancedBmp = applySmartTextEnhancement(originalBitmap)
            if (enhancedBmp != originalBitmap) {
                originalBitmap.recycle()
                originalBitmap = enhancedBmp
            }
        }

        val configBmp = originalBitmap.copy(Bitmap.Config.RGB_565, false)
        if (configBmp != null && configBmp != originalBitmap) {
            originalBitmap.recycle()
            originalBitmap = configBmp
        }

        var bestStream: ByteArrayOutputStream? = null
        var smallestStream: ByteArrayOutputStream? = null
        var smallestSize = Long.MAX_VALUE
        var lowScale = 0.1f
        var highScale = 1.0f
        var iterations = 0
        
        while (lowScale <= highScale && iterations < 15) {
            iterations++
            val midScale = (lowScale + highScale) / 2
            
            val width = (originalBitmap.width * midScale).toInt()
            val height = (originalBitmap.height * midScale).toInt()
            
            if (width <= 0 || height <= 0) break
            
            val scaledBitmap = if (midScale < 1.0f) {
                Bitmap.createScaledBitmap(originalBitmap, width, height, true)
            } else {
                originalBitmap
            }
            
            val document = PdfDocument()
            val pdfWidth = 595
            val pdfHeight = (595f * (scaledBitmap.height.toFloat() / scaledBitmap.width.toFloat())).toInt()
            
            val pageInfo = PdfDocument.PageInfo.Builder(pdfWidth, pdfHeight, 1).create()
            val page = document.startPage(pageInfo)
            
            val destRect = android.graphics.RectF(0f, 0f, pdfWidth.toFloat(), pdfHeight.toFloat())
            page.canvas.drawBitmap(scaledBitmap, null, destRect, null)
            
            document.finishPage(page)
            
            val tempStream = ByteArrayOutputStream()
            document.writeTo(tempStream)
            document.close()
            
            if (scaledBitmap != originalBitmap) {
                scaledBitmap.recycle()
            }
            
            val size = tempStream.size().toLong()
            if (size < smallestSize) {
                smallestSize = size
                smallestStream = tempStream
            }

            if (size <= targetSizeBytes) {
                bestStream = tempStream
                lowScale = midScale + 0.05f
            } else {
                highScale = midScale - 0.05f
            }
        }
        
        originalBitmap.recycle()

        val finalStream = bestStream ?: smallestStream
        if (finalStream != null && finalStream.size() > 0) {
            FileOutputStream(outputFile).use { fos ->
                fos.write(finalStream.toByteArray())
            }
            return@withContext outputFile
        }
        
        return@withContext null
    }

    fun extractPagesFromPdf(context: Context, pdfFile: File, tempDir: File): List<File> {
        val extractedFiles = mutableListOf<File>()
        try {
            val fileDescriptor = android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(fileDescriptor)
            try {
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    try {
                        val renderScale = 2.0f
                        val width = (page.width * renderScale).toInt().coerceIn(600, 2480)
                        val height = (page.height * renderScale).toInt().coerceIn(800, 3508)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(0xFFFFFFFF.toInt())
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        val pageFile = File(tempDir, "pdf_extracted_${System.currentTimeMillis()}_$i.jpeg")
                        FileOutputStream(pageFile).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        }
                        bitmap.recycle()
                        extractedFiles.add(pageFile)
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
                fileDescriptor.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return extractedFiles
    }

    fun exportToPublicDocuments(context: Context, sourceFile: File, fileName: String, mimeType: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Dasmo Scan")
                }
                val collection = MediaStore.Files.getContentUri("external")
                val uri = resolver.insert(collection, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    true
                } else {
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Dasmo Scan")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val destFile = File(dir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun clearPublicDocuments(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${Environment.DIRECTORY_DOCUMENTS}/Dasmo Scan%")
                resolver.delete(collection, selection, selectionArgs)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Dasmo Scan")
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getPublicFolderSize(context: Context): Long {
        var totalSize = 0L
        try {
            // 1. App internal cache directory (temporary crop files, bitmap buffers)
            context.cacheDir?.let { cache ->
                if (cache.exists()) totalSize += getFolderSizeRecursively(cache)
            }
            // 2. App external cache directory (camera provider outputs)
            context.externalCacheDir?.let { extCache ->
                if (extCache.exists()) totalSize += getFolderSizeRecursively(extCache)
            }
            // 3. App internal files directory (locally saved document PDFs / JPEGs)
            context.filesDir?.let { files ->
                if (files.exists()) totalSize += getFolderSizeRecursively(files)
            }

            // 4. Public device documents folder (Documents/Dasmo Scan)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(MediaStore.MediaColumns.SIZE)
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${Environment.DIRECTORY_DOCUMENTS}/Dasmo Scan%")
                resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex != -1) {
                        while (cursor.moveToNext()) {
                            totalSize += cursor.getLong(sizeIndex)
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Dasmo Scan")
                if (dir.exists()) {
                    totalSize += getFolderSizeRecursively(dir)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return totalSize
    }

    private fun getFolderSizeRecursively(file: File): Long {
        var size = 0L
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                size += getFolderSizeRecursively(child)
            }
        } else {
            size += file.length()
        }
        return size
    }
}
