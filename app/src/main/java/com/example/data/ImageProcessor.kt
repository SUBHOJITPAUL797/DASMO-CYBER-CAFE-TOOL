package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
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
        return BitmapFactory.decodeFile(path, options)
    }

    suspend fun combineImages(paths: List<String>, outputFile: File): File? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        if (paths.size == 1) {
            val bmp = decodeSampledBitmap(paths[0], 2400, 2400) // Use higher resolution for single image
            if (bmp != null) {
                saveBitmap(bmp, outputFile)
                bmp.recycle()
            }
            return@withContext outputFile
        }

        // Higher resolution for professional quality
        val bitmaps = paths.mapNotNull { decodeSampledBitmap(it, 2000, 2000) }
        if (bitmaps.isEmpty()) return@withContext null

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
    }

    suspend fun combineImagesToA4(paths: List<String>, outputFile: File): File? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        
        // Standard A4 aspect ratio is 1:1.414. We use 1240 x 1754 (excellent balance of size & high scan document definition)
        val a4Width = 1654
        val a4Height = 2339
        
        val a4Bitmap = Bitmap.createBitmap(a4Width, a4Height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(a4Bitmap)
        canvas.drawColor(0xFFFFFFFF.toInt()) // crisp white paper background

        val bitmaps = paths.mapNotNull { decodeSampledBitmap(it, a4Width, a4Height) }
        if (bitmaps.isEmpty()) {
            a4Bitmap.recycle()
            return@withContext null
        }
        val margin = 50f

        if (bitmaps.size == 1) {
            // Draw single image on the A4 page
            val bmp = bitmaps[0]
            
            // For ID cards, we shouldn't stretch them to fill the entire A4 width.
            // A standard ID card should take up about 50-60% of the A4 width max.
            val maxAllowedWidth = a4Width * 0.65f
            
            val maxDrawWidth = kotlin.math.min(a4Width - (2 * margin), maxAllowedWidth)
            val maxDrawHeight = a4Height - (2 * margin)

            val scale = kotlin.math.min(maxDrawWidth / bmp.width.toFloat(), maxDrawHeight / bmp.height.toFloat())
            val drawWidth = (bmp.width * scale).toInt()
            val drawHeight = (bmp.height * scale).toInt()

            val left = (a4Width - drawWidth) / 2f
            
            // Position it towards the top center instead of dead center, for a more natural ID photocopy look
            // (e.g. 1/4th of the way down the page)
            val top = (a4Height / 4f) - (drawHeight / 2f)

            if (drawWidth > 0 && drawHeight > 0) {
                val scaledBmp = Bitmap.createScaledBitmap(bmp, drawWidth, drawHeight, true)
                canvas.drawBitmap(scaledBmp, left, top, null)
                scaledBmp.recycle()
            }
            bmp.recycle()
        } else {
            // Stack up to 2 images (e.g., front & back scan) centered in top & bottom halves of A4
            val halfHeight = a4Height / 2
            for (index in 0 until kotlin.math.min(2, bitmaps.size)) {
                val bmp = bitmaps[index]
                
                // For ID cards, we shouldn't stretch them to fill the entire A4 width. 
                // A standard ID card should take up about 50-60% of the A4 width max.
                val maxAllowedWidth = a4Width * 0.65f
                
                val maxDrawWidth = kotlin.math.min(a4Width - (2 * margin), maxAllowedWidth)
                val maxDrawHeight = halfHeight - (2 * margin)

                val scale = kotlin.math.min(maxDrawWidth / bmp.width.toFloat(), maxDrawHeight / bmp.height.toFloat())
                
                val drawWidth = (bmp.width * scale).toInt()
                val drawHeight = (bmp.height * scale).toInt()

                val left = (a4Width - drawWidth) / 2f
                val topY = if (index == 0) {
                    (halfHeight - drawHeight) / 2f
                } else {
                    halfHeight + (halfHeight - drawHeight) / 2f
                }

                if (drawWidth > 0 && drawHeight > 0) {
                    val scaledBmp = Bitmap.createScaledBitmap(bmp, drawWidth, drawHeight, true)
                    canvas.drawBitmap(scaledBmp, left, topY, null)
                    scaledBmp.recycle()
                }
                bmp.recycle()
            }
            // If extra unused bitmaps were loaded, recycle them too
            if (bitmaps.size > 2) {
                for (i in 2 until bitmaps.size) {
                    bitmaps[i].recycle()
                }
            }
        }

        saveBitmap(a4Bitmap, outputFile)
        a4Bitmap.recycle()
        outputFile
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        val fos = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        fos.flush()
        fos.close()
    }

    suspend fun compressImage(file: File, targetSizeKb: Int): File = withContext(Dispatchers.IO) {
        var bmp = BitmapFactory.decodeFile(file.absolutePath) ?: throw Exception("Failed to decode image file structure")
        val targetSizeBytes = targetSizeKb * 1024

        // 1. Smart Resolution Optimization
        // Massive camera images (e.g. 4000px+) suffer severe low-quality degradation to fit under small KB targets.
        // Scaling down to 1600px Max-Edge preserves gorgeous 1080p-equivalent textual sharpness, and reduces byte payload by ~70%.
        val maxTargetDimension = 2400
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

        // 2. High-Fidelity Quality Search (Binary Search for optimized compression ratio)
        var stream = ByteArrayOutputStream()
        var lowQuality = 70
        var highQuality = 95
        var bestQuality = 85
        
        while (lowQuality <= highQuality) {
            val midQuality = (lowQuality + highQuality) / 2
            val tempStream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, midQuality, tempStream)
            
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
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            
            while (stream.size() > targetSizeBytes && quality > 45) {
                quality -= 5
                stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, stream)
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
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
        }

        val compressedFile = File(file.parent, "compressed_${file.name}")
        val fos = FileOutputStream(compressedFile)
        fos.write(stream.toByteArray())
        fos.flush()
        fos.close()
        
        bmp.recycle() // Release decoder bitmap allocation
        compressedFile
    }

    suspend fun convertToMultiPagePdf(imageFiles: List<File>, outputFile: File, targetSizeKb: Int): File? = withContext(Dispatchers.IO) {
        if (imageFiles.isEmpty()) return@withContext null
        val targetSizeBytes = targetSizeKb * 1024

        var bestStream = ByteArrayOutputStream()
        var bestScale = 0f
        var lowScale = 0.1f
        var highScale = 1.0f

        var iterations = 0
        while (lowScale <= highScale && iterations < 20) {
            iterations++
            val midScale = (lowScale + highScale) / 2
            val document = PdfDocument()
            var canProcess = true

            for ((index, imageFile) in imageFiles.withIndex()) {
                var originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                if (originalBitmap == null) {
                    canProcess = false
                    break
                }
                
                // Convert to RGB_565 to save memory and PDF bytes significantly
                val configBmp = originalBitmap.copy(Bitmap.Config.RGB_565, false)
                if (configBmp != null && configBmp != originalBitmap) {
                    originalBitmap.recycle()
                    originalBitmap = configBmp
                }

                val width = (originalBitmap.width * midScale).toInt()
                val height = (originalBitmap.height * midScale).toInt()

                if (width <= 0 || height <= 0) {
                    originalBitmap.recycle()
                    canProcess = false
                    break
                }

                val scaledBitmap = if (midScale < 1.0f) {
                    Bitmap.createScaledBitmap(originalBitmap, width, height, true)
                } else {
                    originalBitmap
                }

                if (scaledBitmap != originalBitmap) {
                    originalBitmap.recycle()
                }

                val pdfWidth = 595
                val pdfHeight = (595f * (scaledBitmap.height.toFloat() / scaledBitmap.width.toFloat())).toInt()

                val pageInfo = PdfDocument.PageInfo.Builder(pdfWidth, pdfHeight, index + 1).create()
                val page = document.startPage(pageInfo)

                val destRect = android.graphics.RectF(0f, 0f, pdfWidth.toFloat(), pdfHeight.toFloat())
                page.canvas.drawBitmap(scaledBitmap, null, destRect, null)

                document.finishPage(page)
                scaledBitmap.recycle()
            }

            if (!canProcess) {
                document.close()
                break
            }

            val tempStream = ByteArrayOutputStream()
            document.writeTo(tempStream)
            document.close()

            val size = tempStream.size()
            if (size <= targetSizeBytes) {
                bestStream = tempStream
                bestScale = midScale
                // If it fits, try pushing the quality higher with fine granularity
                lowScale = midScale + 0.005f 
            } else {
                // If it's too big, we must scale down with fine granularity
                highScale = midScale - 0.005f
            }
        }

        if (bestStream.size() > 0) {
            val fos = FileOutputStream(outputFile)
            fos.write(bestStream.toByteArray())
            fos.flush()
            fos.close()
            return@withContext outputFile
        }
        
        return@withContext null
    }

    suspend fun convertToPdf(imageFile: File, outputFile: File, targetSizeKb: Int): File? = withContext(Dispatchers.IO) {
        var originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null
        val targetSizeBytes = targetSizeKb * 1024
        
        // Convert to RGB_565 to save memory and PDF bytes significantly, allowing a higher resolution for the same file size
        val configBmp = originalBitmap.copy(Bitmap.Config.RGB_565, false)
        if (configBmp != null && configBmp != originalBitmap) {
            originalBitmap.recycle()
            originalBitmap = configBmp
        }

        var bestStream = ByteArrayOutputStream()
        var bestScale = 0f
        var lowScale = 0.1f
        var highScale = 1.0f
        
        var iterations = 0
        while (lowScale <= highScale && iterations < 20) {
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
            
            val size = tempStream.size()
            if (size <= targetSizeBytes) {
                bestStream = tempStream
                bestScale = midScale
                lowScale = midScale + 0.005f
            } else {
                highScale = midScale - 0.005f
            }
        }
        
        originalBitmap.recycle()

        if (bestStream.size() > 0) {
            val fos = FileOutputStream(outputFile)
            fos.write(bestStream.toByteArray())
            fos.flush()
            fos.close()
            return@withContext outputFile
        }
        
        return@withContext null
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
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "dasmo scanner")
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
                    totalSize = getFolderSizeRecursively(dir)
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
