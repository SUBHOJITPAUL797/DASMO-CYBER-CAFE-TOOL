package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PassportDriveTarget(
    val folderId: String?,
    val folderName: String
)

private data class BgColorOption(val name: String, val color: Color)

private enum class PassportSizeStandard(
    val title: String,
    val subtitle: String,
    val widthPx: Int,
    val heightPx: Int
) {
    INDIA_UK_EU_CM("3.5 x 4.5 cm (Standard)", "413 × 531 px • India / UK / EU", 413, 531),
    US_2X2_INCH("2 x 2 inch (US)", "600 × 600 px • US Visa & Passport", 600, 600),
    ICAO_35X45_MM("35 x 45 mm", "413 × 531 px • ICAO Standard", 413, 531),
    ONLINE_SQUARE_600("600 x 600 px", "1:1 Square • Online Portals", 600, 600);

    val aspectRatio: Float
        get() = widthPx.toFloat() / heightPx.toFloat()
}

private enum class AlignmentGuideState {
    VISIBLE,
    HIDDEN
}

private enum class EdgeHaloCleanMode(
    val label: String,
    val description: String,
    val lowThreshold: Float,
    val highThreshold: Float,
    val erosionPasses: Int,
    val seedInwardErosionPasses: Int,
    val decontaminatePasses: Int,
    val chromaPenaltyStrength: Float,
    val bgColorRadius: Float
) {
    SOFT(
        label = "Soft Edge",
        description = "Gentle hair feathering",
        lowThreshold = 0.58f,
        highThreshold = 0.89f,
        erosionPasses = 1,
        seedInwardErosionPasses = 2,
        decontaminatePasses = 8,
        chromaPenaltyStrength = 0.42f,
        bgColorRadius = 54f
    ),
    DEEP_CLEAN(
        label = "Deep Defringe",
        description = "Erases old background halo",
        lowThreshold = 0.68f,
        highThreshold = 0.94f,
        erosionPasses = 2,
        seedInwardErosionPasses = 3,
        decontaminatePasses = 12,
        chromaPenaltyStrength = 0.62f,
        bgColorRadius = 66f
    ),
    ULTRA_TIGHT(
        label = "Ultra Tight",
        description = "Maximum contour trim",
        lowThreshold = 0.76f,
        highThreshold = 0.96f,
        erosionPasses = 3,
        seedInwardErosionPasses = 4,
        decontaminatePasses = 15,
        chromaPenaltyStrength = 0.78f,
        bgColorRadius = 76f
    )
}

private enum class PassportCameraLens {
    FRONT,
    BACK
}

private enum class PassportCameraFlash(val mode: Int, val label: String) {
    OFF(ImageCapture.FLASH_MODE_OFF, "Flash Off"),
    AUTO(ImageCapture.FLASH_MODE_AUTO, "Flash Auto"),
    ON(ImageCapture.FLASH_MODE_ON, "Flash On");

    fun next(): PassportCameraFlash = when (this) {
        OFF -> AUTO
        AUTO -> ON
        ON -> OFF
    }
}

private sealed interface PassportModalState {
    data object None : PassportModalState
    data class LiveCamera(
        val lens: PassportCameraLens = PassportCameraLens.BACK,
        val flash: PassportCameraFlash = PassportCameraFlash.OFF,
        val shutterState: ShutterCaptureState = ShutterCaptureState.IDLE
    ) : PassportModalState
}

private sealed interface PassportStudioOperation {
    data object Idle : PassportStudioOperation
    data object LoadingPhoto : PassportStudioOperation
    data object SegmentingBackground : PassportStudioOperation
    data object RefiningEdges : PassportStudioOperation
    data object SavingLocalSingle : PassportStudioOperation
    data object SavingLocalSheet : PassportStudioOperation
    data class UploadingDriveSingle(val folderName: String) : PassportStudioOperation
    data class UploadingDriveSheet(val folderName: String) : PassportStudioOperation
}

private sealed interface PassportExportBannerState {
    data object Hidden : PassportExportBannerState
    data class Visible(
        val message: String,
        val isSuccess: Boolean,
        val isCloudSynced: Boolean
    ) : PassportExportBannerState
}

private class RawSegmentationMask(
    val width: Int,
    val height: Int,
    val confidence: FloatArray
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportPhotoScreen(
    onBack: () -> Unit,
    googleEmail: String? = null,
    driveTarget: PassportDriveTarget = PassportDriveTarget(null, "My Drive"),
    onChooseDriveFolder: () -> Unit = {},
    onSaveAndUploadPassport: (
        tempFile: File,
        clientName: String,
        isPrintSheet: Boolean,
        uploadToDrive: Boolean,
        onResult: (Boolean, String, Boolean) -> Unit
    ) -> Unit = { tempFile, _, _, _, onResult ->
        onResult(true, "Saved ${tempFile.name}", false)
    }
) {
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
    var selectedDimension by remember { mutableStateOf(PassportSizeStandard.INDIA_UK_EU_CM) }
    var guideState by remember { mutableStateOf(AlignmentGuideState.VISIBLE) }
    var edgeCleanMode by remember { mutableStateOf(EdgeHaloCleanMode.DEEP_CLEAN) }
    var clientNameInput by remember { mutableStateOf("") }

    var cropScale by remember { mutableFloatStateOf(1f) }
    var cropOffsetX by remember { mutableFloatStateOf(0f) }
    var cropOffsetY by remember { mutableFloatStateOf(0f) }

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rawSegmentationMask by remember { mutableStateOf<RawSegmentationMask?>(null) }
    var cutoutForegroundBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var studioOperation by remember { mutableStateOf<PassportStudioOperation>(PassportStudioOperation.Idle) }
    var modalState by remember { mutableStateOf<PassportModalState>(PassportModalState.None) }
    var exportBannerState by remember { mutableStateOf<PassportExportBannerState>(PassportExportBannerState.Hidden) }
    var photoSessionGeneration by remember { mutableIntStateOf(0) }

    val isProcessingOverlay = studioOperation is PassportStudioOperation.SegmentingBackground ||
            studioOperation is PassportStudioOperation.RefiningEdges ||
            studioOperation is PassportStudioOperation.LoadingPhoto
    val isBusy = studioOperation != PassportStudioOperation.Idle

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun loadNewBitmapIntoStudio(bitmap: Bitmap?, autoRemoveBackground: Boolean = true) {
        if (bitmap == null) {
            studioOperation = PassportStudioOperation.Idle
            Toast.makeText(context, "Unable to decode portrait photo", Toast.LENGTH_SHORT).show()
            return
        }
        val activeGen = photoSessionGeneration
        originalBitmap = bitmap
        rawSegmentationMask = null
        cutoutForegroundBitmap = null
        cropScale = 1f
        cropOffsetX = 0f
        cropOffsetY = 0f
        exportBannerState = PassportExportBannerState.Hidden

        if (autoRemoveBackground) {
            studioOperation = PassportStudioOperation.SegmentingBackground
            scope.launch {
                val (mask, cutout) = withContext(Dispatchers.Default) {
                    val computedMask = runMlKitSegmentationMask(bitmap)
                    val computedCutout = if (computedMask != null) {
                        buildHaloFreeForegroundCutout(bitmap, computedMask, edgeCleanMode)
                    } else null
                    computedMask to computedCutout
                }
                if (photoSessionGeneration == activeGen) {
                    rawSegmentationMask = mask
                    cutoutForegroundBitmap = cutout
                    studioOperation = PassportStudioOperation.Idle
                    if (cutout == null) {
                        Toast.makeText(context, "Could not isolate subject automatically", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            studioOperation = PassportStudioOperation.Idle
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val nextGen = photoSessionGeneration + 1
            photoSessionGeneration = nextGen
            studioOperation = PassportStudioOperation.LoadingPhoto
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmapFromUri(context, it, 2048)
                }
                if (photoSessionGeneration == nextGen) {
                    loadNewBitmapIntoStudio(bitmap, autoRemoveBackground = true)
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            modalState = PassportModalState.LiveCamera()
        } else {
            Toast.makeText(context, "Camera permission is required to capture live passport photos", Toast.LENGTH_LONG).show()
        }
    }

    fun openLiveCameraStudio() {
        if (isBusy) return
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            modalState = PassportModalState.LiveCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun applyEdgeCleanMode(newMode: EdgeHaloCleanMode) {
        edgeCleanMode = newMode
        val srcBmp = originalBitmap ?: return
        val cachedMask = rawSegmentationMask ?: return
        if (cutoutForegroundBitmap == null || isBusy) return

        val activeGen = photoSessionGeneration
        studioOperation = PassportStudioOperation.RefiningEdges
        scope.launch {
            val refined = withContext(Dispatchers.Default) {
                buildHaloFreeForegroundCutout(srcBmp, cachedMask, newMode)
            }
            if (photoSessionGeneration == activeGen) {
                cutoutForegroundBitmap = refined
                studioOperation = PassportStudioOperation.Idle
            }
        }
    }

    fun triggerExport(isPrintSheet: Boolean, uploadToDrive: Boolean) {
        if (isBusy) return
        val bmpToUse = cutoutForegroundBitmap ?: originalBitmap ?: return
        scope.launch {
            studioOperation = when {
                uploadToDrive && isPrintSheet -> PassportStudioOperation.UploadingDriveSheet(driveTarget.folderName)
                uploadToDrive && !isPrintSheet -> PassportStudioOperation.UploadingDriveSingle(driveTarget.folderName)
                isPrintSheet -> PassportStudioOperation.SavingLocalSheet
                else -> PassportStudioOperation.SavingLocalSingle
            }
            exportBannerState = PassportExportBannerState.Hidden

            val generatedFile = if (isPrintSheet) {
                generatePassportSheetJpeg(
                    context, bmpToUse, selectedDimension, selectedColor,
                    cropScale, cropOffsetX, cropOffsetY
                )
            } else {
                generatePassportJpeg(
                    context, bmpToUse, selectedDimension, selectedColor,
                    cropScale, cropOffsetX, cropOffsetY
                )
            }

            if (generatedFile == null) {
                studioOperation = PassportStudioOperation.Idle
                exportBannerState = PassportExportBannerState.Visible(
                    message = "Failed to render passport JPEG.",
                    isSuccess = false,
                    isCloudSynced = false
                )
                Toast.makeText(context, "Failed to render passport image", Toast.LENGTH_SHORT).show()
                return@launch
            }

            onSaveAndUploadPassport(
                generatedFile,
                clientNameInput,
                isPrintSheet,
                uploadToDrive
            ) { success, message, syncedToCloud ->
                studioOperation = PassportStudioOperation.Idle
                exportBannerState = PassportExportBannerState.Visible(
                    message = message,
                    isSuccess = success,
                    isCloudSynced = syncedToCloud
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val liveCameraModal = modalState as? PassportModalState.LiveCamera
    if (liveCameraModal != null) {
        PassportLiveCameraDialog(
            cameraState = liveCameraModal,
            aspectRatio = selectedDimension.aspectRatio,
            onUpdateState = { updated -> modalState = updated },
            onDismiss = { modalState = PassportModalState.None },
            onPhotoCaptured = { capturedBitmap ->
                modalState = PassportModalState.None
                val nextGen = photoSessionGeneration + 1
                photoSessionGeneration = nextGen
                loadNewBitmapIntoStudio(capturedBitmap, autoRemoveBackground = true)
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text("Passport Photo Studio", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("AI Halo-Free Cutout • Camera & Drive Sync", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("passport_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (originalBitmap != null) {
                        IconButton(
                            onClick = {
                                guideState = if (guideState == AlignmentGuideState.VISIBLE) {
                                    AlignmentGuideState.HIDDEN
                                } else {
                                    AlignmentGuideState.VISIBLE
                                }
                            },
                            modifier = Modifier.testTag("passport_toggle_guide_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridOn,
                                contentDescription = "Toggle Alignment Guide",
                                tint = if (guideState == AlignmentGuideState.VISIBLE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                // Hero Launchpad Card with Both Real-Time Camera & Gallery Options
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Badge,
                                contentDescription = "Passport Studio",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Create Official Passport Portrait",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Snap a live portrait with our guided camera or import from gallery. Automatically removes old background halos, applies studio colors, and syncs to Google Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 1. Live Camera Option Card
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { openLiveCameraStudio() }
                                    .testTag("passport_take_camera_button"),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 18.dp, horizontal = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.CameraAlt,
                                            contentDescription = "Take Live Photo",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Text(
                                        "Take Photo",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "Live Guided Camera",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // 2. Choose from Gallery Option Card
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { galleryLauncher.launch("image/*") }
                                    .testTag("passport_pick_gallery_button"),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 18.dp, horizontal = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.secondary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PhotoLibrary,
                                            contentDescription = "Choose from Gallery",
                                            tint = MaterialTheme.colorScheme.onSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Text(
                                        "From Gallery",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        "Pick Existing Photo",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
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
                        val animatedBackdropColor by animateColorAsState(
                            targetValue = selectedColor,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "backdrop_color_anim"
                        )

                        Box(
                            modifier = Modifier
                                .height(290.dp)
                                .aspectRatio(selectedDimension.aspectRatio)
                                .clip(RoundedCornerShape(10.dp))
                                .background(animatedBackdropColor)
                                .border(2.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
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
                            if (guideState == AlignmentGuideState.VISIBLE) {
                                ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                    val dashStroke = Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                                    )
                                    val guideColor = Color.Cyan.copy(alpha = 0.72f)
                                    drawLine(
                                        color = guideColor,
                                        start = Offset(size.width / 2f, 0f),
                                        end = Offset(size.width / 2f, size.height),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                    )
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

                            if (isProcessingOverlay) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = when (studioOperation) {
                                                is PassportStudioOperation.RefiningEdges -> "Defringing Edge Halo..."
                                                is PassportStudioOperation.LoadingPhoto -> "Loading Portrait..."
                                                else -> "Removing Background & Halo..."
                                            },
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
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

                        // Action Toolbar (Camera, Gallery, Remove/Restore BG, Reset Crop)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { openLiveCameraStudio() },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(0.95f)
                                    .heightIn(min = 48.dp)
                                    .testTag("passport_toolbar_camera_button"),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Camera", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { if (!isBusy) galleryLauncher.launch("image/*") },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(0.95f)
                                    .heightIn(min = 48.dp)
                                    .testTag("passport_toolbar_gallery_button"),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick Gallery", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Gallery", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            Button(
                                onClick = {
                                    val srcBmp = originalBitmap
                                    if (!isBusy && srcBmp != null) {
                                        if (cutoutForegroundBitmap != null) {
                                            cutoutForegroundBitmap = null
                                        } else {
                                            val activeGen = photoSessionGeneration
                                            val existingMask = rawSegmentationMask
                                            if (existingMask != null) {
                                                studioOperation = PassportStudioOperation.RefiningEdges
                                                scope.launch {
                                                    val cutout = withContext(Dispatchers.Default) {
                                                        buildHaloFreeForegroundCutout(srcBmp, existingMask, edgeCleanMode)
                                                    }
                                                    if (photoSessionGeneration == activeGen) {
                                                        cutoutForegroundBitmap = cutout
                                                        studioOperation = PassportStudioOperation.Idle
                                                    }
                                                }
                                            } else {
                                                studioOperation = PassportStudioOperation.SegmentingBackground
                                                scope.launch {
                                                    val (mask, cutout) = withContext(Dispatchers.Default) {
                                                        val m = runMlKitSegmentationMask(srcBmp)
                                                        val c = if (m != null) buildHaloFreeForegroundCutout(srcBmp, m, edgeCleanMode) else null
                                                        m to c
                                                    }
                                                    if (photoSessionGeneration == activeGen) {
                                                        rawSegmentationMask = mask
                                                        cutoutForegroundBitmap = cutout
                                                        studioOperation = PassportStudioOperation.Idle
                                                        if (cutout == null) {
                                                            Toast.makeText(context, "Could not isolate subject", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(1.25f)
                                    .heightIn(min = 48.dp)
                                    .testTag("passport_toggle_bg_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (cutoutForegroundBitmap != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                                    contentColor = if (cutoutForegroundBitmap != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = if (cutoutForegroundBitmap != null) Icons.AutoMirrored.Filled.Undo else Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isProcessingOverlay) "Working..." else if (cutoutForegroundBitmap != null) "Restore BG" else "Remove BG",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    cropScale = 1f
                                    cropOffsetX = 0f
                                    cropOffsetY = 0f
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Reset Crop", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Studio Backdrop Color Swatches + Edge Halo Defringe Control Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Backdrop Studio Color", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (cutoutForegroundBitmap != null) "HALO-FREE CUTOUT ACTIVE" else "TAP 'REMOVE BG' FIRST",
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
                                val swatchScale by animateFloatAsState(
                                    targetValue = if (isSelected) 1.08f else 1f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                    label = "swatch_scale"
                                )
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { selectedColor = option.color }
                                        .padding(horizontal = 2.dp, vertical = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .scale(swatchScale)
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

                        // Edge Halo Defringe Mode Selector (Live Decontamination Tuning)
                        AnimatedVisibility(
                            visible = cutoutForegroundBitmap != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            "Edge Halo Removal Strength",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = edgeCleanMode.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    EdgeHaloCleanMode.entries.forEach { mode ->
                                        val isModeSelected = edgeCleanMode == mode
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isModeSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                            border = if (isModeSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 42.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable(enabled = !isBusy) { applyEdgeCleanMode(mode) }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
                                            ) {
                                                Text(
                                                    text = mode.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (isModeSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                                    color = if (isModeSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
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
                        PassportSizeStandard.entries.forEach { standard ->
                            val isSelected = selectedDimension == standard
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedDimension = standard }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedDimension = standard },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(standard.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(standard.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                // Targeted Google Drive Folder & Client Naming Studio Card
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text("Cloud Sync & Destination", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                                    Text(
                                        text = if (!googleEmail.isNullOrBlank()) "Connected: $googleEmail" else "Sign in on Home/Settings to enable Drive upload",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!googleEmail.isNullOrBlank()) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Client / Person Name Input for Organized Drive & Local Filenames
                        OutlinedTextField(
                            value = clientNameInput,
                            onValueChange = { clientNameInput = it },
                            label = { Text("Customer / File Name (Optional)") },
                            placeholder = { Text("e.g. Subhojit Paul") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (clientNameInput.isNotEmpty()) {
                                    IconButton(onClick = { clientNameInput = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("passport_client_name_input")
                        )

                        // Interactive Target Google Drive Folder Picker Card
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !isBusy) { onChooseDriveFolder() }
                                .testTag("passport_choose_drive_folder_button")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderSpecial,
                                        contentDescription = "Google Drive Folder",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Target Google Drive Folder",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = driveTarget.folderName.ifBlank { "My Drive" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            "Change Folder",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }

                        // Animated Live Feedback Banner after Save / Drive Upload
                        val currentBanner = exportBannerState as? PassportExportBannerState.Visible
                        AnimatedVisibility(
                            visible = currentBanner != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            if (currentBanner != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (currentBanner.isSuccess) {
                                        Color(0xFF1B5E20).copy(alpha = 0.25f)
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (currentBanner.isSuccess) Color(0xFF00E676) else MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (currentBanner.isCloudSynced) Icons.Default.CloudDone
                                            else if (currentBanner.isSuccess) Icons.Default.CheckCircle
                                            else Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (currentBanner.isSuccess) Color(0xFF00E676) else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = currentBanner.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (currentBanner.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Dual Export Actions: Local Storage + Direct Google Drive Upload
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Export & Cloud Upload",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold
                        )

                        // 1. Single Passport Photo Row (Save Phone + Upload Drive)
                        Text(
                            "Single Passport Photo (${selectedDimension.widthPx}×${selectedDimension.heightPx} px)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { triggerExport(isPrintSheet = false, uploadToDrive = false) },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("passport_save_single_local_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (studioOperation is PassportStudioOperation.SavingLocalSingle) "Saving..." else "Save Phone",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = { triggerExport(isPrintSheet = false, uploadToDrive = true) },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .weight(1.25f)
                                    .height(50.dp)
                                    .testTag("passport_upload_single_drive_button")
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (studioOperation is PassportStudioOperation.UploadingDriveSingle) "Uploading..." else "Upload to Drive",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // 2. 8-Photo 4x6 Print Sheet Row (Save Phone + Upload Drive)
                        Text(
                            "8-Photo 4×6 Printable Sheet (300 DPI Lab Grid)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { triggerExport(isPrintSheet = true, uploadToDrive = false) },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("passport_save_sheet_local_button")
                            ) {
                                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (studioOperation is PassportStudioOperation.SavingLocalSheet) "Saving..." else "Save 4×6 Sheet",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = { triggerExport(isPrintSheet = true, uploadToDrive = true) },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00C853),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1.25f)
                                    .height(50.dp)
                                    .testTag("passport_upload_sheet_drive_button")
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (studioOperation is PassportStudioOperation.UploadingDriveSheet) "Uploading..." else "4×6 Sheet to Drive",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PassportLiveCameraDialog(
    cameraState: PassportModalState.LiveCamera,
    aspectRatio: Float,
    onUpdateState: (PassportModalState.LiveCamera) -> Unit,
    onDismiss: () -> Unit,
    onPhotoCaptured: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}
        }
    }

    fun triggerHaptic() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(35)
                }
            }
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            key(cameraState.lens) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
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
                            try {
                                val provider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                    .setTargetRotation(android.view.Surface.ROTATION_0)
                                    .setFlashMode(cameraState.flash.mode)
                                    .build()
                                imageCapture = capture

                                val selector = if (cameraState.lens == PassportCameraLens.FRONT) {
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                } else {
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    update = {
                        imageCapture?.flashMode = cameraState.flash.mode
                    }
                )
            }

            // Live Passport Framing & Head-Oval Overlay on Camera Viewfinder
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val frameW = size.width * 0.78f
                val frameH = (frameW / aspectRatio).coerceAtMost(size.height * 0.64f)
                val left = (size.width - frameW) / 2f
                val top = (size.height - frameH) / 2f - 24.dp.toPx()

                drawRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(left, top),
                    size = Size(frameW, frameH),
                    style = Stroke(width = 2.dp.toPx())
                )

                val guideStroke = Stroke(
                    width = 1.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )
                val ovalW = frameW * 0.56f
                val ovalH = frameH * 0.52f
                drawOval(
                    color = Color.Cyan.copy(alpha = 0.85f),
                    topLeft = Offset(left + (frameW - ovalW) / 2f, top + frameH * 0.12f),
                    size = Size(ovalW, ovalH),
                    style = guideStroke
                )
                drawLine(
                    color = Color.Cyan.copy(alpha = 0.65f),
                    start = Offset(size.width / 2f, top),
                    end = Offset(size.width / 2f, top + frameH),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }

            // Top Camera Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close Camera", tint = Color.White)
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "Align Head Inside Oval",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                IconButton(
                    onClick = {
                        val nextFlash = cameraState.flash.next()
                        imageCapture?.flashMode = nextFlash.mode
                        onUpdateState(cameraState.copy(flash = nextFlash))
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = when (cameraState.flash) {
                            PassportCameraFlash.OFF -> Icons.Default.FlashOff
                            PassportCameraFlash.AUTO -> Icons.Default.FlashAuto
                            PassportCameraFlash.ON -> Icons.Default.FlashOn
                        },
                        contentDescription = cameraState.flash.label,
                        tint = if (cameraState.flash == PassportCameraFlash.OFF) Color.White else Color(0xFFFFD600)
                    )
                }
            }

            // Bottom Shutter & Camera Flip Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 28.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(52.dp))

                // Shutter Button
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (cameraState.shutterState == ShutterCaptureState.CAPTURING) Color.Gray else Color.White
                        )
                        .clickable(enabled = cameraState.shutterState == ShutterCaptureState.IDLE) {
                            val capture = imageCapture ?: return@clickable
                            triggerHaptic()
                            onUpdateState(cameraState.copy(shutterState = ShutterCaptureState.CAPTURING))
                            val photoFile = File(context.cacheDir, "passport_cam_${UUID.randomUUID()}.jpg")
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                            capture.takePicture(
                                outputOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        scope.launch {
                                            val decoded = withContext(Dispatchers.IO) {
                                                val bmp = decodeSampledBitmapFromUri(context, Uri.fromFile(photoFile), 2048)
                                                try { photoFile.delete() } catch (_: Exception) {}
                                                bmp
                                            }
                                            if (decoded != null) {
                                                onPhotoCaptured(decoded)
                                            } else {
                                                onUpdateState(cameraState.copy(shutterState = ShutterCaptureState.IDLE))
                                                Toast.makeText(context, "Failed to process captured photo", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        try { photoFile.delete() } catch (_: Exception) {}
                                        onUpdateState(cameraState.copy(shutterState = ShutterCaptureState.IDLE))
                                        Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (cameraState.shutterState == ShutterCaptureState.CAPTURING) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Camera,
                            contentDescription = "Capture Passport Photo",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Flip Front/Back Camera Button
                IconButton(
                    onClick = {
                        val nextLens = if (cameraState.lens == PassportCameraLens.BACK) {
                            PassportCameraLens.FRONT
                        } else {
                            PassportCameraLens.BACK
                        }
                        onUpdateState(cameraState.copy(lens = nextLens))
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Switch Front/Back Camera",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * Runs ML Kit Selfie Segmentation once and returns the raw confidence float array
 * so we can apply edge-guided chroma decontamination & defringing in real time.
 */
private suspend fun runMlKitSegmentationMask(bitmap: Bitmap): RawSegmentationMask? {
    val options = SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .enableRawSizeMask()
        .build()
    val segmenter = Segmentation.getClient(options)
    return try {
        val image = InputImage.fromBitmap(bitmap, 0)
        val mask = segmenter.process(image).await()
        val buffer = mask.buffer
        val mw = mask.width
        val mh = mask.height
        val floats = FloatArray(mw * mh)
        buffer.rewind()
        for (i in 0 until (mw * mh)) {
            floats[i] = buffer.float
        }
        RawSegmentationMask(mw, mh, floats)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        try { segmenter.close() } catch (_: Exception) {}
    }
}

/**
 * Multi-Stage Halo-Free Foreground Cutout & Color Decontamination Engine:
 *
 * Fixes the blue/white old-background edge fringe visible when placing a cutout onto a new
 * studio backdrop color:
 * 1. Samples original background RGB anchors across all certain-background regions (conf < 0.08).
 * 2. Bilinearly interpolates the raw 256x256 confidence mask to full portrait dimensions,
 *    and computes local neighborhood minimum confidence so high-res edge pixels sitting just
 *    inside the coarse mask boundary are identified as part of the transition band.
 * 3. Suppresses transition-band pixels whose RGB matches the sampled original background
 *    (edge-guided chroma matting) so background spill along hair, ears, cheeks, and shoulders is rejected.
 * 4. Applies configurable morphological inward trim + smooth anti-aliased ramp.
 * 5. Erodes the pure-interior seed mask inward by 2-4 pixels (and excludes any background-tinted pixel)
 *    before performing 8-15 passes of Inward-to-Outward Foreground Color Decontamination (Despill),
 *    guaranteeing edge pixels carry 100% authentic hair/skin/clothing color and 0% old background halo.
 */
private fun buildHaloFreeForegroundCutout(
    bitmap: Bitmap,
    rawMask: RawSegmentationMask,
    mode: EdgeHaloCleanMode
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val totalPixels = w * h
    val srcPixels = IntArray(totalPixels)
    bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)

    val mw = rawMask.width
    val mh = rawMask.height
    val mConf = rawMask.confidence

    // 1. Sample old background color anchors across all certain-background cells (conf < 0.08)
    // so local background lighting/gradients near the head & shoulders are captured accurately
    val maxBgSamples = 96
    val bgRList = IntArray(maxBgSamples)
    val bgGList = IntArray(maxBgSamples)
    val bgBList = IntArray(maxBgSamples)
    var bgCount = 0

    val stepY = max(1, mh / 12)
    val stepX = max(1, mw / 12)
    for (my in 0 until mh step stepY) {
        for (mx in 0 until mw step stepX) {
            val conf = mConf[my * mw + mx]
            if (conf < 0.08f && bgCount < maxBgSamples) {
                val px = ((mx + 0.5f) / mw * w).toInt().coerceIn(0, w - 1)
                val py = ((my + 0.5f) / mh * h).toInt().coerceIn(0, h - 1)
                val rgb = srcPixels[py * w + px]
                bgRList[bgCount] = (rgb shr 16) and 0xFF
                bgGList[bgCount] = (rgb shr 8) and 0xFF
                bgBList[bgCount] = rgb and 0xFF
                bgCount++
            }
        }
    }

    // Fallback if no background cells matched
    if (bgCount == 0) {
        val corners = intArrayOf(0, w - 1, (h / 4) * w, (h / 4) * w + w - 1)
        for (idx in corners) {
            if (idx in 0 until totalPixels && bgCount < maxBgSamples) {
                val rgb = srcPixels[idx]
                bgRList[bgCount] = (rgb shr 16) and 0xFF
                bgGList[bgCount] = (rgb shr 8) and 0xFF
                bgBList[bgCount] = rgb and 0xFF
                bgCount++
            }
        }
    }

    val bgRadius = mode.bgColorRadius
    val bgRadiusSq = (bgRadius * bgRadius).toInt()

    // 2. Bilinearly upsample confidence to full resolution + apply chroma-similarity suppression
    // on boundary pixels (including pixels where any immediate mask neighbor is < 0.92f)
    var fullConf = FloatArray(totalPixels)
    val bgSimilarityMap = FloatArray(totalPixels)
    val scaleX = (mw - 1).toFloat() / max(1, w - 1).toFloat()
    val scaleY = (mh - 1).toFloat() / max(1, h - 1).toFloat()

    for (y in 0 until h) {
        val fy = y * scaleY
        val y0 = fy.toInt().coerceIn(0, mh - 1)
        val y1 = min(y0 + 1, mh - 1)
        val dy = fy - y0
        val rowOffset0 = y0 * mw
        val rowOffset1 = y1 * mw
        val yPrevMask = max(0, y0 - 1) * mw
        val yNextMask = min(mh - 1, y1 + 1) * mw
        val outRow = y * w

        for (x in 0 until w) {
            val fx = x * scaleX
            val x0 = fx.toInt().coerceIn(0, mw - 1)
            val x1 = min(x0 + 1, mw - 1)
            val dx = fx - x0

            val c00 = mConf[rowOffset0 + x0]
            val c10 = mConf[rowOffset0 + x1]
            val c01 = mConf[rowOffset1 + x0]
            val c11 = mConf[rowOffset1 + x1]

            val c0 = c00 + (c10 - c00) * dx
            val c1 = c01 + (c11 - c01) * dx
            var conf = c0 + (c1 - c0) * dy

            // Check minimum confidence in the surrounding 3x3 mask neighborhood
            // so high-res edge pixels just inside the 256x256 mask contour are treated as boundary
            val xPrevMask = max(0, x0 - 1)
            val xNextMask = min(mw - 1, x1 + 1)
            val localMinMaskConf = minOf(
                c00, c10, c01, c11,
                mConf[yPrevMask + x0], mConf[yNextMask + x0],
                mConf[rowOffset0 + xPrevMask], mConf[rowOffset0 + xNextMask]
            )

            val idx = outRow + x
            if (conf > 0.05f && (conf < 0.985f || localMinMaskConf < 0.90f) && bgCount > 0) {
                val rgb = srcPixels[idx]
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                var minBgDistSq = Int.MAX_VALUE
                for (k in 0 until bgCount) {
                    val dr = r - bgRList[k]
                    val dg = g - bgGList[k]
                    val db = b - bgBList[k]
                    val dSq = dr * dr + dg * dg + db * db
                    if (dSq < minBgDistSq) minBgDistSq = dSq
                }

                if (minBgDistSq < bgRadiusSq) {
                    val similarity = (1f - (sqrt(minBgDistSq.toFloat()) / bgRadius)).coerceIn(0f, 1f)
                    bgSimilarityMap[idx] = similarity
                    // Stronger penalty near the silhouette edge where localMinMaskConf is low
                    val edgeProximity = (1f - (localMinMaskConf / 0.95f).coerceIn(0f, 1f)).coerceIn(0.35f, 1f)
                    conf = (conf - similarity * mode.chromaPenaltyStrength * edgeProximity).coerceAtLeast(0f)
                }
            }
            fullConf[idx] = conf
        }
    }

    // 3. Morphological 3x3 minimum erosion on the transition band to trim outer halo fringe
    repeat(mode.erosionPasses) {
        val eroded = FloatArray(totalPixels)
        for (y in 0 until h) {
            val yPrev = max(0, y - 1) * w
            val yCurr = y * w
            val yNext = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val idx = yCurr + x
                val c = fullConf[idx]
                if (c in 0.03f..0.975f) {
                    val xPrev = max(0, x - 1)
                    val xNext = min(w - 1, x + 1)
                    var minNeighbor = c
                    minNeighbor = min(minNeighbor, fullConf[yPrev + x])
                    minNeighbor = min(minNeighbor, fullConf[yNext + x])
                    minNeighbor = min(minNeighbor, fullConf[yCurr + xPrev])
                    minNeighbor = min(minNeighbor, fullConf[yCurr + xNext])
                    minNeighbor = min(minNeighbor, fullConf[yPrev + xPrev])
                    minNeighbor = min(minNeighbor, fullConf[yPrev + xNext])
                    minNeighbor = min(minNeighbor, fullConf[yNext + xPrev])
                    minNeighbor = min(minNeighbor, fullConf[yNext + xNext])
                    // Erode harder if pixel resembles the old background color
                    val bgBoost = bgSimilarityMap[idx] * 0.25f
                    val erodeWeight = (0.68f + bgBoost).coerceAtMost(0.92f)
                    eroded[idx] = c * (1f - erodeWeight) + minNeighbor * erodeWeight
                } else {
                    eroded[idx] = c
                }
            }
        }
        fullConf = eroded
    }

    // 4. Convert refined confidence to smooth anti-aliased alpha [0..255]
    val alphaChannel = IntArray(totalPixels)
    var isPureInterior = BooleanArray(totalPixels)
    val lowT = mode.lowThreshold
    val highT = mode.highThreshold
    val span = (highT - lowT).coerceAtLeast(0.05f)

    for (i in 0 until totalPixels) {
        val c = fullConf[i]
        var a = when {
            c <= lowT -> 0
            c >= highT -> 255
            else -> {
                val t = ((c - lowT) / span).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                (smooth * 255f).toInt().coerceIn(0, 255)
            }
        }
        // Attenuate residual alpha on semi-transparent boundary pixels that still carry old background chroma
        val bgSim = bgSimilarityMap[i]
        if (a in 1..250 && bgSim > 0.15f) {
            a = (a * (1f - bgSim * 0.65f)).toInt().coerceIn(0, 255)
        }
        alphaChannel[i] = a
        // Seed pure interior ONLY from deep foreground pixels that have zero background color similarity
        isPureInterior[i] = (c >= 0.96f && a == 255 && bgSim < 0.08f)
    }

    // Erode the pure interior seed mask inward by 2-4 pixels so the color propagation source
    // is guaranteed to be inside true hair/skin/shirt and never touches the outer edge blur
    repeat(mode.seedInwardErosionPasses) {
        val nextSeed = BooleanArray(totalPixels)
        for (y in 1 until h - 1) {
            val row = y * w
            val rowUp = (y - 1) * w
            val rowDown = (y + 1) * w
            for (x in 1 until w - 1) {
                val idx = row + x
                if (isPureInterior[idx] &&
                    isPureInterior[row + x - 1] && isPureInterior[row + x + 1] &&
                    isPureInterior[rowUp + x] && isPureInterior[rowDown + x] &&
                    isPureInterior[rowUp + x - 1] && isPureInterior[rowUp + x + 1] &&
                    isPureInterior[rowDown + x - 1] && isPureInterior[rowDown + x + 1]
                ) {
                    nextSeed[idx] = true
                }
            }
        }
        isPureInterior = nextSeed
    }

    // 5. Multi-Pass Inward-to-Outward Foreground Color Decontamination (Despill):
    // Propagate pure interior foreground RGB outward across the entire boundary zone so hair,
    // ear, cheek, and shoulder edges take their true foreground color instead of the old background halo.
    val cleanRgb = srcPixels.copyOf()
    val hasCleanColor = isPureInterior.copyOf()

    repeat(mode.decontaminatePasses) {
        for (y in 1 until h - 1) {
            val row = y * w
            val rowUp = (y - 1) * w
            val rowDown = (y + 1) * w
            for (x in 1 until w - 1) {
                val idx = row + x
                if (!hasCleanColor[idx] && alphaChannel[idx] > 0) {
                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    var count = 0

                    val neighbors = intArrayOf(
                        row + x - 1, row + x + 1,
                        rowUp + x, rowDown + x,
                        rowUp + x - 1, rowUp + x + 1,
                        rowDown + x - 1, rowDown + x + 1
                    )
                    for (nIdx in neighbors) {
                        if (hasCleanColor[nIdx]) {
                            val nRgb = cleanRgb[nIdx]
                            sumR += (nRgb shr 16) and 0xFF
                            sumG += (nRgb shr 8) and 0xFF
                            sumB += nRgb and 0xFF
                            count++
                        }
                    }

                    if (count > 0) {
                        val avgR = sumR / count
                        val avgG = sumG / count
                        val avgB = sumB / count

                        val origRgb = srcPixels[idx]
                        val origR = (origRgb shr 16) and 0xFF
                        val origG = (origRgb shr 8) and 0xFF
                        val origB = origRgb and 0xFF

                        // Replace up to 98% of the pixel's RGB with pure propagated interior foreground color
                        // whenever the pixel is on the outer edge or has any old-background color similarity
                        val bgSim = bgSimilarityMap[idx]
                        val baseBlend = 1f - ((fullConf[idx] - lowT) / (0.96f - lowT)).coerceIn(0f, 1f) * 0.45f
                        val interiorBlend = maxOf(baseBlend, 0.72f + bgSim * 0.26f).coerceIn(0.65f, 0.98f)
                        val keepOrig = 1f - interiorBlend

                        val finalR = (avgR * interiorBlend + origR * keepOrig).toInt().coerceIn(0, 255)
                        val finalG = (avgG * interiorBlend + origG * keepOrig).toInt().coerceIn(0, 255)
                        val finalB = (avgB * interiorBlend + origB * keepOrig).toInt().coerceIn(0, 255)

                        cleanRgb[idx] = (finalR shl 16) or (finalG shl 8) or finalB
                        hasCleanColor[idx] = true
                    }
                }
            }
        }
    }

    // 6. Compose final ARGB cutout bitmap
    val outPixels = IntArray(totalPixels)
    for (i in 0 until totalPixels) {
        val a = alphaChannel[i]
        if (a == 0) {
            outPixels[i] = 0x00000000
        } else {
            val rgb = cleanRgb[i] and 0x00FFFFFF
            outPixels[i] = (a shl 24) or rgb
        }
    }

    val fgBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    fgBitmap.setPixels(outPixels, 0, w, 0, 0, w, h)
    return fgBitmap
}

private fun renderSinglePassportBitmap(
    bitmap: Bitmap,
    dimension: PassportSizeStandard,
    bgColor: Color,
    cropScale: Float,
    cropOffsetX: Float,
    cropOffsetY: Float
): Bitmap {
    val widthPixels = dimension.widthPx
    val heightPixels = dimension.heightPx

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
    dimension: PassportSizeStandard,
    bgColor: Color,
    cropScale: Float,
    cropOffsetX: Float,
    cropOffsetY: Float
): File? = withContext(Dispatchers.IO) {
    try {
        val outputBitmap = renderSinglePassportBitmap(bitmap, dimension, bgColor, cropScale, cropOffsetX, cropOffsetY)
        val dir = File(context.cacheDir, "passports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Passport_Single_${System.currentTimeMillis()}.jpeg")

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
    dimension: PassportSizeStandard,
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
        val file = File(dir, "Passport_8Sheet_${System.currentTimeMillis()}.jpeg")
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

        // Fix EXIF orientation (including front-camera horizontal mirror flags)
        try {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            var needsTransform = false
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> {
                    matrix.postRotate(90f)
                    needsTransform = true
                }
                ExifInterface.ORIENTATION_ROTATE_180 -> {
                    matrix.postRotate(180f)
                    needsTransform = true
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> {
                    matrix.postRotate(270f)
                    needsTransform = true
                }
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                    matrix.preScale(-1f, 1f)
                    needsTransform = true
                }
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    matrix.preScale(1f, -1f)
                    needsTransform = true
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                    needsTransform = true
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                    needsTransform = true
                }
            }
            if (needsTransform) {
                val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (transformed != bmp) {
                    bmp.recycle()
                    bmp = transformed
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
