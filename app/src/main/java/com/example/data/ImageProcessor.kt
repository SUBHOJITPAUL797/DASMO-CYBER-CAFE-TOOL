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

object ImageProcessor {
    suspend fun combineImages(paths: List<String>, outputFile: File): File? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        if (paths.size == 1) {
            val bmp = BitmapFactory.decodeFile(paths[0])
            if (bmp != null) {
                saveBitmap(bmp, outputFile)
                bmp.recycle()
            }
            return@withContext outputFile
        }

        val bitmaps = paths.mapNotNull { BitmapFactory.decodeFile(it) }
        if (bitmaps.isEmpty()) return@withContext null

        val maxWidth = bitmaps.maxOf { it.width }
        val totalHeight = bitmaps.sumOf { it.height }

        val combinedBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)
        
        var currentHeight = 0f
        for (bitmap in bitmaps) {
            canvas.drawBitmap(bitmap, 0f, currentHeight, null)
            currentHeight += bitmap.height
            bitmap.recycle() // Release original individual bitmap immediately
        }

        saveBitmap(combinedBitmap, outputFile)
        combinedBitmap.recycle() // Release combined bitmap canvas source
        outputFile
    }

    suspend fun combineImagesToA4(paths: List<String>, outputFile: File): File? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext null
        
        // Standard A4 aspect ratio is 1:1.414. We use 1240 x 1754 (excellent balance of size & high scan document definition)
        val a4Width = 1240
        val a4Height = 1754
        
        val a4Bitmap = Bitmap.createBitmap(a4Width, a4Height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(a4Bitmap)
        canvas.drawColor(0xFFFFFFFF.toInt()) // crisp white paper background

        val bitmaps = paths.mapNotNull { BitmapFactory.decodeFile(it) }
        if (bitmaps.isEmpty()) {
            a4Bitmap.recycle()
            return@withContext null
        }
        val margin = 50f

        if (bitmaps.size == 1) {
            // Draw single image centered on the A4 page
            val bmp = bitmaps[0]
            val maxDrawWidth = a4Width - (2 * margin)
            val maxDrawHeight = a4Height - (2 * margin)

            val scale = kotlin.math.min(maxDrawWidth / bmp.width.toFloat(), maxDrawHeight / bmp.height.toFloat())
            val drawWidth = (bmp.width * scale).toInt()
            val drawHeight = (bmp.height * scale).toInt()

            val left = (a4Width - drawWidth) / 2f
            val top = (a4Height - drawHeight) / 2f

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
                val maxDrawWidth = a4Width - (2 * margin)
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
        val maxTargetDimension = 1600
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

    suspend fun convertToPdf(imageFile: File, outputFile: File, targetSizeKb: Int): File? = withContext(Dispatchers.IO) {
        var bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null
        val targetSizeBytes = targetSizeKb * 1024

        // Fit bitmap to maximum dimension first (e.g. 1000px) to guarantee small starting size in PDF structures
        val maxDimension = 1000
        if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / kotlin.math.max(bitmap.width, bitmap.height)
            val targetW = (bitmap.width * scale).toInt()
            val targetH = (bitmap.height * scale).toInt()
            if (targetW > 0 && targetH > 0) {
                val prev = bitmap
                bitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (prev != bitmap) {
                    prev.recycle()
                }
            }
        }

        while (true) {
            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = document.startPage(pageInfo)
            
            val canvas = page.canvas
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            
            document.finishPage(page)
            
            val stream = ByteArrayOutputStream()
            document.writeTo(stream)
            document.close()

            if (stream.size() <= targetSizeBytes) {
                val fos = FileOutputStream(outputFile)
                fos.write(stream.toByteArray())
                fos.flush()
                fos.close()
                break
            } else {
                val width = (bitmap.width * 0.75).toInt()
                val height = (bitmap.height * 0.75).toInt()
                if (width <= 0 || height <= 0) {
                    val fos = FileOutputStream(outputFile)
                    fos.write(stream.toByteArray())
                    fos.flush()
                    fos.close()
                    break
                }
                val prev = bitmap
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (prev != bitmap) {
                    prev.recycle()
                }
            }
        }
        
        bitmap.recycle() // Release final source bitmap allocation
        outputFile
    }
}
