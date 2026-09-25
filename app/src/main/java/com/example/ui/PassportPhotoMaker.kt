package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import com.example.data.ImageProcessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportPhotoScreen(onBack: () -> Unit) {
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedColor by remember { mutableStateOf(Color.Blue) }
    var selectedDimension by remember { mutableStateOf("3.5 x 4.5 cm (Standard)") }
    
    var cropScale by remember { mutableFloatStateOf(1f) }
    var cropOffsetX by remember { mutableFloatStateOf(0f) }
    var cropOffsetY by remember { mutableFloatStateOf(0f) }
    
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUri = uri
        uri?.let {
            val bitmap = decodeSampledBitmapFromUri(context, it, 2048)
            originalBitmap = bitmap
            processedBitmap = null
            cropScale = 1f
            cropOffsetX = 0f
            cropOffsetY = 0f
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passport Photo Maker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (originalBitmap == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clickable { launcher.launch("image/*") },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "Select Photo", modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Select Photo from Gallery")
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .height(300.dp)
                        .aspectRatio(
                            when {
                                selectedDimension.contains("2 x 2") || selectedDimension.contains("600 x 600") -> 1f
                                else -> 413f / 531f
                            }
                        )
                        .background(selectedColor)
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                cropScale = (cropScale * zoom).coerceIn(0.5f, 5f)
                                cropOffsetX += pan.x / size.width
                                cropOffsetY += pan.y / size.height
                            }
                        }
                ) {
                    if (processedBitmap != null) {
                        Image(
                            bitmap = processedBitmap!!.asImageBitmap(),
                            contentDescription = "Selected Image",
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = cropScale
                                scaleY = cropScale
                                translationX = cropOffsetX * size.width
                                translationY = cropOffsetY * size.height
                            },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            bitmap = originalBitmap!!.asImageBitmap(),
                            contentDescription = "Original Image",
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = cropScale
                                scaleY = cropScale
                                translationX = cropOffsetX * size.width
                                translationY = cropOffsetY * size.height
                            },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                
                Text(
                    text = "Pinch to zoom, drag to pan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Row {
                    Button(onClick = { launcher.launch("image/*") }) {
                        Text("Change Photo")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { 
                        if (!isProcessing && originalBitmap != null) {
                            isProcessing = true
                            removeBackground(originalBitmap!!, selectedColor, context) { resultBmp ->
                                processedBitmap = resultBmp
                                isProcessing = false
                            }
                        }
                    }) {
                        Text(if (isProcessing) "Processing..." else "Remove Background")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Dimension Options
                Text("Select Dimensions", style = MaterialTheme.typography.titleMedium)
                val dimensions = listOf(
                    "3.5 x 4.5 cm (Standard)",
                    "2 x 2 inch (US)",
                    "35 x 45 mm",
                    "600 x 600 px"
                )
                dimensions.forEach { dim ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDimension = dim }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedDimension == dim,
                            onClick = { selectedDimension = dim }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(dim)
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Background Color Options
                Text("Background Color", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val colors = listOf(Color.Blue, Color.White, Color.Red, Color.LightGray)
                    colors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(color, shape = RoundedCornerShape(8.dp))
                                .clickable { selectedColor = color }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = { 
                        scope.launch {
                            val bmpToUse = processedBitmap ?: originalBitmap
                            if (bmpToUse != null) {
                                val jpgFile = generatePassportJpeg(
                                    context, bmpToUse, selectedDimension, selectedColor,
                                    cropScale, cropOffsetX, cropOffsetY
                                )
                                if (jpgFile != null) {
                                    ImageProcessor.exportToPublicDocuments(context, jpgFile, jpgFile.name, "image/jpeg")
                                    Toast.makeText(context, "Saved to Documents/Dasmo Scan/${jpgFile.name}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Failed to save", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save as JPEG")
                }
            }
        }
    }
}

private fun removeBackground(
    bitmap: Bitmap,
    bgColor: Color,
    context: Context,
    onComplete: (Bitmap?) -> Unit
) {
    val options = SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .build()
    val segmenter = Segmentation.getClient(options)
    val image = InputImage.fromBitmap(bitmap, 0)
    segmenter.process(image)
        .addOnSuccessListener { segmentationMask ->
            try {
                val mask = segmentationMask.buffer
                val maskWidth = segmentationMask.width
                val maskHeight = segmentationMask.height

                // 1. Build mask bitmap from confidence buffer
                val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                val maskPixels = IntArray(maskWidth * maskHeight)
                mask.rewind()
                for (i in 0 until (maskWidth * maskHeight)) {
                    val confidence = mask.float
                    val alpha = if (confidence > 0.5f) {
                        ((confidence - 0.5f) * 2f * 255f).toInt().coerceIn(0, 255)
                    } else {
                        0
                    }
                    maskPixels[i] = (alpha shl 24) or 0x00FFFFFF
                }
                maskBitmap.setPixels(maskPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                // 2. Upscale mask to original photo dimensions for maximum crispness
                val scaledMask = Bitmap.createScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)
                maskBitmap.recycle()

                // 3. Cutout foreground with alpha
                val fgBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val fgCanvas = Canvas(fgBitmap)
                fgCanvas.drawBitmap(bitmap, 0f, 0f, null)

                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                }
                fgCanvas.drawBitmap(scaledMask, 0f, 0f, maskPaint)
                scaledMask.recycle()

                // 4. Composite high-resolution background color with crisp foreground person
                val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outputBitmap)
                canvas.drawColor(bgColor.toArgb())
                canvas.drawBitmap(fgBitmap, 0f, 0f, null)
                fgBitmap.recycle()

                onComplete(outputBitmap)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(null)
            } finally {
                try { segmenter.close() } catch (ex: Exception) {}
            }
        }
        .addOnFailureListener {
            it.printStackTrace()
            try { segmenter.close() } catch (ex: Exception) {}
            onComplete(null)
        }
}

private suspend fun generatePassportJpeg(
    context: Context,
    bitmap: Bitmap,
    dimension: String,
    bgColor: Color,
    cropScale: Float,
    cropOffsetX: Float,
    cropOffsetY: Float
): File? = withContext(Dispatchers.IO) {
    try {
        val widthPixels: Int
        val heightPixels: Int
        when {
            dimension.contains("3.5 x 4.5 cm") || dimension.contains("35 x 45 mm") -> {
                widthPixels = 413
                heightPixels = 531
            }
            dimension.contains("2 x 2 inch") -> {
                widthPixels = 600
                heightPixels = 600
            }
            dimension.contains("600 x 600 px") -> {
                widthPixels = 600
                heightPixels = 600
            }
            else -> {
                widthPixels = 413
                heightPixels = 531
            }
        }
        
        val outputBitmap = Bitmap.createBitmap(widthPixels, heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(bgColor.toArgb())
        
        val baseScale = maxOf(
            widthPixels.toFloat() / bitmap.width.toFloat(),
            heightPixels.toFloat() / bitmap.height.toFloat()
        )
        
        val cx = (widthPixels - bitmap.width * baseScale) / 2f
        val cy = (heightPixels - bitmap.height * baseScale) / 2f
        
        val matrix = android.graphics.Matrix()
        matrix.postScale(baseScale, baseScale)
        matrix.postTranslate(cx, cy)
        
        matrix.postScale(cropScale, cropScale, widthPixels / 2f, heightPixels / 2f)
        matrix.postTranslate(cropOffsetX * widthPixels, cropOffsetY * heightPixels)
        
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        
        val dir = File(context.cacheDir, "passports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "passport_${System.currentTimeMillis()}.jpg")
        
        FileOutputStream(file).use { out ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
        outputBitmap.recycle()
        
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        var inSampleSize = 1
        val maxDim = maxOf(options.outWidth, options.outHeight)
        while (maxDim / (inSampleSize * 2) >= maxDimension) {
            inSampleSize *= 2
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        var bmp = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        if (bmp.width > maxDimension || bmp.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(bmp.width, bmp.height)
            val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
            val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            if (scaled != bmp) {
                bmp.recycle()
                bmp = scaled
            }
        }
        bmp
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    }
}

