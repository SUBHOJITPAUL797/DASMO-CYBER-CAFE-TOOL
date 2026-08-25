package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.example.data.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchCameraScanScreen(
    onDismiss: () -> Unit,
    initialPagesPerDoc: Int = 2,
    onFinishBatch: (List<Uri>, Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // Enforce locked portrait orientation so phone rotation is OFF during scanning
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Camera permission required for batch scanning", Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var isCapturing by remember { mutableStateOf(false) }
    var showFlashEffect by remember { mutableStateOf(false) }
    var showReviewSheet by remember { mutableStateOf(false) }
    var showGridOverlay by remember { mutableStateOf(true) }

    var pagesPerDoc by remember { mutableIntStateOf(initialPagesPerDoc.coerceAtLeast(1)) }
    var showCustomPagesDialog by remember { mutableStateOf(false) }
    var customInputText by remember { mutableStateOf(pagesPerDoc.toString()) }

    val capturedPageUris = remember { mutableStateListOf<Uri>() }
    val capturedPageFiles = remember { mutableStateListOf<File>() }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var camera: Camera? by remember { mutableStateOf(null) }

    fun triggerVibration() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(40)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun takeContinuousPageSnap() {
        val capture = imageCapture ?: return
        if (isCapturing) return

        isCapturing = true
        showFlashEffect = true
        triggerVibration()

        val photoFile = File(context.cacheDir, "batch_scan_${UUID.randomUUID()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    coroutineScope.launch(Dispatchers.IO) {
                        // Fix orientation and normalize to upright portrait
                        val fixedFile = ImageProcessor.fixFileOrientation(photoFile)
                        val uri = Uri.fromFile(fixedFile)
                        withContext(Dispatchers.Main) {
                            capturedPageFiles.add(fixedFile)
                            capturedPageUris.add(uri)
                            isCapturing = false
                            showFlashEffect = false
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    showFlashEffect = false
                    Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(android.view.Surface.ROTATION_0) // Fixed portrait capture
                        .setFlashMode(flashMode)
                        .build()

                    imageCapture = capture

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            capture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Shutter Flash animation effect
        if (showFlashEffect) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.5f))
            )
        }

        // Document framing grid guide overlay
        if (showGridOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 96.dp)
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                // Corner accents for document positioning
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.TopStart)
                        .border(3.dp, Color.Cyan, RoundedCornerShape(topStart = 16.dp))
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.TopEnd)
                        .border(3.dp, Color.Cyan, RoundedCornerShape(topEnd = 16.dp))
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.BottomStart)
                        .border(3.dp, Color.Cyan, RoundedCornerShape(bottomStart = 16.dp))
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.BottomEnd)
                        .border(3.dp, Color.Cyan, RoundedCornerShape(bottomEnd = 16.dp))
                )

                Text(
                    text = "LOCKED PORTRAIT • TAP SHUTTER CONTINUOUSLY",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Top Header Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close Button
            IconButton(
                onClick = {
                    if (capturedPageUris.isNotEmpty()) {
                        // Dismiss warning
                        onDismiss()
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close Scanner",
                    tint = Color.White
                )
            }

            // Status Badge
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color.Green, CircleShape)
                    )
                    Text(
                        text = "Batch Camera: ${capturedPageUris.size} Pages",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Controls Row (Flash & Grid)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Grid Toggle
                IconButton(
                    onClick = { showGridOverlay = !showGridOverlay },
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (showGridOverlay) Color(0xFF00E676).copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.5f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.GridOn,
                        contentDescription = "Toggle Grid",
                        tint = if (showGridOverlay) Color(0xFF00E676) else Color.White
                    )
                }

                // Flash Toggle
                IconButton(
                    onClick = {
                        val nextFlash = when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                        flashMode = nextFlash
                        imageCapture?.flashMode = nextFlash
                        if (nextFlash == ImageCapture.FLASH_MODE_ON) {
                            camera?.cameraControl?.enableTorch(true)
                        } else {
                            camera?.cameraControl?.enableTorch(false)
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        },
                        contentDescription = "Flash Mode",
                        tint = if (flashMode != ImageCapture.FLASH_MODE_OFF) Color.Yellow else Color.White
                    )
                }
            }
        }

        // Bottom Controls Bar (Grouping Selector, Shutter & Batch Queue Finish)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Grouping Mode Selector Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Calculation Badge
                val docCount = if (capturedPageUris.isEmpty()) 0 else (capturedPageUris.size + pagesPerDoc - 1) / pagesPerDoc
                val groupingSummary = when {
                    capturedPageUris.isEmpty() -> if (pagesPerDoc == 1) "1 Page / Doc" else if (pagesPerDoc == 2) "Pair (2 Pages / Doc)" else "$pagesPerDoc Pages / Doc"
                    pagesPerDoc == 1 -> "${capturedPageUris.size} Pages ➔ ${capturedPageUris.size} Single Docs"
                    pagesPerDoc == 2 -> {
                        val fullPairs = capturedPageUris.size / 2
                        val remainder = capturedPageUris.size % 2
                        if (remainder == 0) "${capturedPageUris.size} Pages ➔ $fullPairs Pair Docs"
                        else "${capturedPageUris.size} Pages ➔ $docCount Docs (${fullPairs}x2 + 1)"
                    }
                    else -> {
                        val fullGroups = capturedPageUris.size / pagesPerDoc
                        val remainder = capturedPageUris.size % pagesPerDoc
                        if (remainder == 0) "${capturedPageUris.size} Pages ➔ $fullGroups Docs"
                        else "${capturedPageUris.size} Pages ➔ $docCount Docs (${fullGroups}x$pagesPerDoc + $remainder)"
                    }
                }
                
                Surface(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "📄 $groupingSummary",
                        color = Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Quick selector buttons
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1 to "1", 2 to "2", 3 to "3").forEach { (num, label) ->
                        val isSelected = pagesPerDoc == num
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
                                .clickable { pagesPerDoc = num }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal
                            )
                        }
                    }

                    // Custom pill
                    val isCustom = pagesPerDoc > 3
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCustom) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
                            .clickable {
                                customInputText = pagesPerDoc.toString()
                                showCustomPagesDialog = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isCustom) "$pagesPerDoc" else "N...",
                            color = if (isCustom) Color.White else Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontWeight = if (isCustom) FontWeight.ExtraBold else FontWeight.Normal
                        )
                    }
                }
            }

            // Live captured thumbnails strip
            if (capturedPageUris.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(capturedPageUris) { index, uri ->
                        Box(
                            modifier = Modifier
                                .size(56.dp, 72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.5.dp, Color.White, RoundedCornerShape(8.dp))
                                .clickable { showReviewSheet = true }
                        ) {
                            Image(
                                painter = rememberAsyncImagePainter(uri),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left thumbnail stack / review trigger
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable(enabled = capturedPageUris.isNotEmpty()) {
                            showReviewSheet = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (capturedPageUris.isNotEmpty()) {
                        Image(
                            painter = rememberAsyncImagePainter(capturedPageUris.last()),
                            contentDescription = "Review Pages",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f))
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ) {
                            Text(
                                text = "${capturedPageUris.size}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = "No pages yet",
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }

                // Rapid Shutter Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(Color.White.copy(alpha = 0.25f), CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isCapturing) Color.LightGray else Color.White)
                        .clickable(enabled = !isCapturing) {
                            takeContinuousPageSnap()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .border(3.dp, Color.Black.copy(alpha = 0.2f), CircleShape)
                    )
                }

                // Right Finish Button
                Button(
                    onClick = {
                        if (capturedPageUris.isNotEmpty()) {
                            onFinishBatch(capturedPageUris.toList(), pagesPerDoc)
                        } else {
                            Toast.makeText(context, "Snap at least 1 page first", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = capturedPageUris.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676),
                        contentColor = Color.Black,
                        disabledContainerColor = Color.White.copy(alpha = 0.15f),
                        disabledContentColor = Color.White.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Finish Batch",
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Finish (${capturedPageUris.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Custom Pages Per Document Input Dialog
        if (showCustomPagesDialog) {
            AlertDialog(
                onDismissRequest = { showCustomPagesDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Pages Per Document") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Enter how many scanned pages to combine into each PDF/Document file:", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = customInputText,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() } && input.length <= 3) {
                                    customInputText = input
                                }
                            },
                            label = { Text("Pages Per File") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val num = customInputText.toIntOrNull()?.coerceIn(1, 100) ?: 2
                        pagesPerDoc = num
                        showCustomPagesDialog = false
                    }) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomPagesDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Review Pages Sheet / Modal
        if (showReviewSheet) {
            AlertDialog(
                onDismissRequest = { showReviewSheet = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Review Batch (${capturedPageUris.size} Pages)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showReviewSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close review")
                        }
                    }
                },
                text = {
                    Box(modifier = Modifier.height(360.dp)) {
                        if (capturedPageUris.isEmpty()) {
                            Text("No pages snapped yet.", modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(capturedPageUris) { index, uri ->
                                    Box(
                                        modifier = Modifier
                                            .aspectRatio(0.75f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    ) {
                                        Image(
                                            painter = rememberAsyncImagePainter(uri),
                                            contentDescription = "Page ${index + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "P${index + 1}",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                capturedPageUris.removeAt(index)
                                                if (index < capturedPageFiles.size) {
                                                    capturedPageFiles.removeAt(index)
                                                }
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(2.dp)
                                                .size(24.dp)
                                                .background(Color.Red, CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Delete Page",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showReviewSheet = false },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Resume Continuous Snap")
                    }
                }
            )
        }
    }
}

// Helper extension to find Activity from Context
internal fun Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) {
            return ctx
        }
        ctx = ctx.baseContext
    }
    return null
}
