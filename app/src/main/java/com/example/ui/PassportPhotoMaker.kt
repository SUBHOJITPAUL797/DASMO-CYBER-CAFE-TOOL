package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ImageProcessor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private data class BgColorOption(val name: String, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportPhotoScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val bgPalette = remember {
        listOf(
            BgColorOption("Royal Blue", Color(0xFF0047AB)),
            BgColorOption("Sky Blue", Color(0xFF38B6FF)),
            BgColorOption("Pure White", Color(0xFFFFFFFF)),
            BgColorOption("Light Gray", Color(0xFFE0E4E8)),
            BgColorOption("Navy Blue", Color(0xFF0A1931)),
            BgColorOption("Crimson", Color(0xFFC62828))
        )
    }

    var selectedColor by remember { mutableStateOf(bgPalette[0].color) }
    var selectedDimension by remember { mutableStateOf("3.5 x 4.5 cm (Standard)") }
    var showGuideOverlay by remember { mutableStateOf(true) }

    var cropScale by remember { mutableFloatStateOf(1f) }
    var cropOffsetX by remember { mutableFloatStateOf(0f) }
    var cropOffsetY by remember { mutableFloatStateOf(0f) }

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Stores the transparent foreground cutout so changing background color is instant!
    var cutoutForegroundBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmapFromUri(context, it, 2048)
                }
                originalBitmap = bitmap
                cutoutForegroundBitmap = null
                cropScale = 1f
                cropOffsetX = 0f
                cropOffsetY = 0f
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Passport Photo Studio", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("AI Background Cutout & Print Grid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (originalBitmap != null) {
                        IconButton(onClick = { showGuideOverlay = !showGuideOverlay }) {
                            Icon(
                                imageVector = Icons.Default.GridOn,
                                contentDescription = "Toggle Alignment Guide",
                                tint = if (showGuideOverlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (originalBitmap == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clickable { launcher.launch("image/*") },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AddAPhoto,
                                contentDescription = "Select Photo",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Select Portrait Photo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Import from gallery to remove background, change studio backdrop color, and generate single or 8-photo printable sheets.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                // Interactive Studio Canvas Card
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .height(290.dp)
                                .aspectRatio(
                                    when {
                                        selectedDimension.contains("2 x 2") || selectedDimension.contains("600 x 600") -> 1f
                                        else -> 413f / 531f
                                    }
                                )
                                .clip(RoundedCornerShape(8.dp))
                                .background(selectedColor)
                                .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .clipToBounds()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        cropScale = (cropScale * zoom).coerceIn(0.5f, 5f)
                                        cropOffsetX += pan.x / size.width
                                        cropOffsetY += pan.y / size.height
                                    }
                                }
                        ) {
                            val displayBmp = cutoutForegroundBitmap ?: originalBitmap!!
                            Image(
                                bitmap = displayBmp.asImageBitmap(),
                                contentDescription = "Passport Portrait Preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = cropScale
                                        scaleY = cropScale
                                        translationX = cropOffsetX * size.width
                                        translationY = cropOffsetY * size.height
                                    },
                                contentScale = ContentScale.Crop
                            )

                            // Head & Shoulders Alignment Guide
                            if (showGuideOverlay) {
                                ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                    val dashStroke = Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                                    )
                                    val guideColor = Color.Cyan.copy(alpha = 0.7f)
                                    // Center vertical line
                                    drawLine(
                                        color = guideColor,
                                        start = Offset(size.width / 2f, 0f),
                                        end = Offset(size.width / 2f, size.height),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                    )
                                    // Head oval
                                    val ovalW = size.width * 0.54f
                                    val ovalH = size.height * 0.50f
                                    drawOval(
                                        color = guideColor,
                                        topLeft = Offset((size.width - ovalW) / 2f, size.height * 0.12f),
                                        size = Size(ovalW, ovalH),
                                        style = dashStroke
                                    )
                                }
                            }

                            if (isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Removing Background...", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Pinch to zoom • Drag to align face inside oval",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Action Toolbar (Change Photo, Remove BG, Reset Zoom)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { launcher.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Change", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    if (!isProcessing && originalBitmap != null) {
                                        if (cutoutForegroundBitmap != null) {
                                            // Toggle back to original if already removed
                                            cutoutForegroundBitmap = null
                                        } else {
                                            isProcessing = true
                                            extractForegroundCutout(originalBitmap!!) { resultBmp ->
                                                cutoutForegroundBitmap = resultBmp
                                                isProcessing = false
                                                if (resultBmp == null) {
                                                    Toast.makeText(context, "Could not isolate subject", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !isProcessing,
                                modifier = Modifier.weight(1.3f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (cutoutForegroundBitmap != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                                    contentColor = if (cutoutForegroundBitmap != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = if (cutoutForegroundBitmap != null) Icons.Default.Undo else Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isProcessing) "Cutout..." else if (cutoutForegroundBitmap != null) "Restore BG" else "Remove BG",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    cropScale = 1f
                                    cropOffsetX = 0f
                                    cropOffsetY = 0f
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Reset Crop", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Studio Backdrop Color Swatches Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Backdrop Studio Color", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (cutoutForegroundBitmap != null) "LIVE CUTOUT ACTIVE" else "TAP 'REMOVE BG' FIRST",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (cutoutForegroundBitmap != null) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            bgPalette.forEach { option ->
                                val isSelected = selectedColor == option.color
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { selectedColor = option.color }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(option.color)
                                            .border(
                                                width = if (isSelected) 3.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = option.name,
                                                tint = if (option.color == Color.White || option.color == Color(0xFFE0E4E8)) Color.Black else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = option.name.substringBefore(" "),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                // Dimension Selector Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Official Size Standard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val dimensions = listOf(
                            "3.5 x 4.5 cm (Standard)" to "413 × 531 px • India / UK / EU",
                            "2 x 2 inch (US)" to "600 × 600 px • US Visa & Passport",
                            "35 x 45 mm" to "413 × 531 px • ICAO Standard",
                            "600 x 600 px" to "1:1 Square • Online Portals"
                        )
                        dimensions.forEach { (dim, subtitle) ->
                            val isSelected = selectedDimension == dim
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedDimension = dim }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedDimension = dim },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(dim, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                // Export Buttons (Single Photo + 8-Photo Printable Sheet)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (!isSaving) {
                                scope.launch {
                                    isSaving = true
                                    try {
                                        val bmpToUse = cutoutForegroundBitmap ?: originalBitmap
                                        if (bmpToUse != null) {
                                            val jpgFile = generatePassportJpeg(
                                                context, bmpToUse, selectedDimension, selectedColor,
                                                cropScale, cropOffsetX, cropOffsetY
                                            )
                                            if (jpgFile != null) {
                                                ImageProcessor.exportToPublicDocuments(context, jpgFile, jpgFile.name, "image/jpeg")
                                                Toast.makeText(context, "Saved Single Photo to Documents/Dasmo Scan/${jpgFile.name}", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Failed to save photo", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSaving) "Saving..." else "Save Single Passport Photo (JPEG)", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (!isSaving) {
                                scope.launch {
                                    isSaving = true
                                    try {
                                        val bmpToUse = cutoutForegroundBitmap ?: originalBitmap
                                        if (bmpToUse != null) {
                                            val sheetFile = generatePassportSheetJpeg(
                                                context, bmpToUse, selectedDimension, selectedColor,
                                                cropScale, cropOffsetX, cropOffsetY
                                            )
                                            if (sheetFile != null) {
                                                ImageProcessor.exportToPublicDocuments(context, sheetFile, sheetFile.name, "image/jpeg")
                                                Toast.makeText(context, "Saved 8-Photo Print Sheet to Documents/Dasmo Scan/${sheetFile.name}", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Failed to generate print sheet", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            }
                        },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save 8-Photo 4×6 Print Sheet (JPEG)", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

private fun extractForegroundCutout(
    bitmap: Bitmap,
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

                // 1. Build alpha mask bitmap from confidence buffer
                val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                val maskPixels = IntArray(maskWidth * maskHeight)
                mask.rewind()
                for (i in 0 until (maskWidth * maskHeight)) {
                    val confidence = mask.float
                    val alpha = if (confidence > 0.45f) {
                        ((confidence - 0.45f) / 0.55f * 255f).toInt().coerceIn(0, 255)
                    } else {
                        0
                    }
                    maskPixels[i] = (alpha shl 24) or 0x00FFFFFF
                }
                maskBitmap.setPixels(maskPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                // 2. Upscale mask to original photo dimensions for smooth edges
                val scaledMask = Bitmap.createScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)
                maskBitmap.recycle()

                // 3. Cutout foreground with transparent alpha so background color can be changed live!
                val fgBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val fgCanvas = Canvas(fgBitmap)
                fgCanvas.drawBitmap(bitmap, 0f, 0f, null)

                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                }
                fgCanvas.drawBitmap(scaledMask, 0f, 0f, maskPaint)
                scaledMask.recycle()

                onComplete(fgBitmap)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(null)
            } finally {
                try { segmenter.close() } catch (_: Exception) {}
            }
        }
        .addOnFailureListener {
            it.printStackTrace()
            try { segmenter.close() } catch (_: Exception) {}
            onComplete(null)
        }
}

private fun renderSinglePassportBitmap(
    bitmap: Bitmap,
    dimension: String,
    bgColor: Color,
    cropScale: Float,
    cropOffsetX: Float,
    cropOffsetY: Float
): Bitmap {
    val widthPixels: Int
    val heightPixels: Int
    when {
        dimension.contains("3.5 x 4.5 cm") || dimension.contains("35 x 45 mm") -> {
            widthPixels = 413
            heightPixels = 531
        }
        dimension.contains("2 x 2 inch") || dimension.contains("600 x 600 px") -> {
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

    val matrix = Matrix()
    matrix.postScale(baseScale, baseScale)
    matrix.postTranslate(cx, cy)

    matrix.postScale(cropScale, cropScale, widthPixels / 2f, heightPixels / 2f)
    matrix.postTranslate(cropOffsetX * widthPixels, cropOffsetY * heightPixels)

    canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return outputBitmap
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
        val outputBitmap = renderSinglePassportBitmap(bitmap, dimension, bgColor, cropScale, cropOffsetX, cropOffsetY)
        val dir = File(context.cacheDir, "passports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Passport_Single_${System.currentTimeMillis()}.jpg")

        FileOutputStream(file).use { out ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        outputBitmap.recycle()
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private suspend fun generatePassportSheetJpeg(
    context: Context,
    bitmap: Bitmap,
    dimension: String,
    bgColor: Color,
    cropScale: Float,
    cropOffsetX: Float,
    cropOffsetY: Float
): File? = withContext(Dispatchers.IO) {
    try {
        val singleBmp = renderSinglePassportBitmap(bitmap, dimension, bgColor, cropScale, cropOffsetX, cropOffsetY)
        // Standard 4x6 inch landscape print sheet at 300 DPI = 1800 x 1200 px (fits 8 standard passport photos in 4x2 grid)
        val sheetW = 1800
        val sheetH = 1200
        val sheetBmp = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheetBmp)
        canvas.drawColor(0xFFFFFFFF.toInt())

        val cols = 4
        val rows = 2
        val cellW = 390
        val cellH = (cellW.toFloat() * singleBmp.height.toFloat() / singleBmp.width.toFloat()).toInt().coerceAtMost(520)
        val scaledSingle = Bitmap.createScaledBitmap(singleBmp, cellW, cellH, true)
        singleBmp.recycle()

        val totalGridW = cols * cellW
        val totalGridH = rows * cellH
        val gapX = (sheetW - totalGridW) / (cols + 1)
        val gapY = (sheetH - totalGridH) / (rows + 1)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFCCCCCC.toInt()
            strokeWidth = 2f
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = gapX + c * (cellW + gapX)
                val top = gapY + r * (cellH + gapY)
                canvas.drawBitmap(scaledSingle, left.toFloat(), top.toFloat(), null)
                canvas.drawRect(left.toFloat(), top.toFloat(), (left + cellW).toFloat(), (top + cellH).toFloat(), borderPaint)
            }
        }
        scaledSingle.recycle()

        val dir = File(context.cacheDir, "passports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Passport_8Sheet_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            sheetBmp.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        sheetBmp.recycle()
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

        // Fix EXIF orientation so vertical portrait selfies from phone cameras don't display rotated 90°
        try {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (degrees != 0) {
                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated != bmp) {
                    bmp.recycle()
                    bmp = rotated
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

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
