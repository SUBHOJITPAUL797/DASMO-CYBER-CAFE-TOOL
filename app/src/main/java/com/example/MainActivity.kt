package com.example

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Build
import android.net.Uri
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Checkbox
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import coil.compose.rememberAsyncImagePainter
import com.example.data.AppDatabase
import com.example.data.DocumentEntity
import com.example.data.SettingsRepository
import com.example.ui.HomeViewModel
import com.example.ui.AuthGuardScreen
import com.example.ui.AdminDashboardScreen
import com.example.ui.AppUser
import com.example.ui.UploadFormat
import com.example.ui.theme.MyApplicationTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import com.example.network.GoogleDriveFolder
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "dasmo_scanner.db"
        ).fallbackToDestructiveMigration().build()

        settingsRepository = SettingsRepository(applicationContext)

        setContent {
            MyApplicationTheme {
                val viewModel: HomeViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return HomeViewModel(database, settingsRepository, applicationContext) as T
                        }
                    }
                )
                MainScreen(viewModel, database, settingsRepository)
            }
        }
    }
}

enum class AppScreenTab {
    DASHBOARD,
    FILES,
    CAPTURES,
    SYNC,
    APPROVALS,
    SETTINGS,
    PASSPORT
}

sealed interface BottomNavEntry {
    data object FabSpacer : BottomNavEntry
    data class TabItem(
        val tab: AppScreenTab,
        val label: String,
        val icon: androidx.compose.ui.graphics.vector.ImageVector
    ) : BottomNavEntry
}

enum class CapturesFilterMode {
    ALL,
    SYNCED,
    PENDING
}

enum class ScanActionType {
    SINGLE,
    MULTI,
    BATCH
}

enum class FabMenuState {
    COLLAPSED,
    EXPANDED
}

sealed interface UpdatePromptState {
    data object Idle : UpdatePromptState
    data object Checking : UpdatePromptState
    data class Available(val info: com.example.util.UpdateChecker.UpdateInfo) : UpdatePromptState
}

sealed interface ScanFlowModalState {
    data object None : ScanFlowModalState
    data class TargetSizeWarning(val pendingAction: ScanActionType) : ScanFlowModalState
    data object BatchSetup : ScanFlowModalState
    data class ContinuousBatchCamera(val initialPagesPerDoc: Int) : ScanFlowModalState
}

sealed interface BatchPageSelection {
    data class Preset(val count: Int) : BatchPageSelection
    data class Custom(val inputText: String) : BatchPageSelection

    fun resolvedCount(fallback: Int = 2): Int = when (this) {
        is Preset -> count.coerceIn(1, 100)
        is Custom -> inputText.toIntOrNull()?.coerceIn(1, 100) ?: fallback.coerceIn(1, 100)
    }
}

sealed interface CustomRegroupModalState {
    data object Hidden : CustomRegroupModalState
    data class Visible(val inputText: String = "2") : CustomRegroupModalState
}

enum class FolderPickerPurpose {
    DEFAULT_SYNC_FOLDER,
    MERGE_DESTINATION_FOLDER,
    PASSPORT_DESTINATION_FOLDER
}

sealed interface PassportDriveSelection {
    data object DefaultSyncFolder : PassportDriveSelection
    data class CustomFolder(val folderId: String?, val folderName: String) : PassportDriveSelection
}

sealed interface NewFolderInputState {
    data object Collapsed : NewFolderInputState
    data class Expanded(val name: String = "") : NewFolderInputState
}

sealed interface ActiveFolderPickerState {
    data object Closed : ActiveFolderPickerState
    data class Open(
        val purpose: FolderPickerPurpose,
        val newFolderInput: NewFolderInputState = NewFolderInputState.Collapsed,
        val searchQuery: String = ""
    ) : ActiveFolderPickerState
}

data class MergeDialogState(
    val fileName: String,
    val folderName: String,
    val folderId: String?,
    val targetSizeKb: String
)

sealed interface MultiScanSessionState {
    data object Idle : MultiScanSessionState
    data class LaunchingInitialScan(val targetEditDocId: Int?) : MultiScanSessionState
    data class ReviewingPages(val targetEditDocId: Int?) : MultiScanSessionState
    data class LaunchingAdditionalScan(val targetEditDocId: Int?) : MultiScanSessionState

    val targetEditDocIdOrNull: Int?
        get() = when (this) {
            Idle -> null
            is LaunchingInitialScan -> targetEditDocId
            is ReviewingPages -> targetEditDocId
            is LaunchingAdditionalScan -> targetEditDocId
        }
}

data class DocumentEditDialogState(
    val doc: com.example.data.DocumentEntity,
    val personName: String,
    val docType: String,
    val format: com.example.ui.UploadFormat,
    val replacementPageUris: List<Uri>? = null
)

sealed interface DashboardOverlayModal {
    data object None : DashboardOverlayModal
    data class SizeDialog(val sizeInput: String) : DashboardOverlayModal
    data class BatchPagesSetting(val pagesInput: String) : DashboardOverlayModal
    data class ClearJunkWarning(val alsoDeletePublicFiles: Boolean = false) : DashboardOverlayModal
    data class DocumentPreview(val doc: com.example.data.DocumentEntity) : DashboardOverlayModal
    data class DocumentEdit(val editState: DocumentEditDialogState) : DashboardOverlayModal
    data class MergeToPdf(val mergeState: MergeDialogState) : DashboardOverlayModal
}

@Composable
fun MainScreen(
    viewModel: HomeViewModel,
    database: AppDatabase,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val targetSizeKb by viewModel.targetSizeKb.collectAsStateWithLifecycle()
    val imageFormat by viewModel.imageFormat.collectAsStateWithLifecycle()
    val compressionCodec by viewModel.compressionCodec.collectAsStateWithLifecycle()
    val enableAiAnalysis by viewModel.enableAiAnalysis.collectAsStateWithLifecycle()
    val showConfirmation by viewModel.showConfirmation.collectAsStateWithLifecycle()
    val statusBannerState by viewModel.statusBannerState.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val pendingDoc by viewModel.pendingDocument.collectAsStateWithLifecycle()

    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    val allUsers by viewModel.allUsers.collectAsStateWithLifecycle()
    val newRequestNotification by viewModel.newRequestNotification.collectAsStateWithLifecycle()

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )

    LaunchedEffect(isAdmin) {
        if (isAdmin && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val deviceId = remember {
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }

    LaunchedEffect(deviceId) {
        viewModel.startAuthListening(deviceId)
    }

    val googleEmail by viewModel.googleEmail.collectAsStateWithLifecycle()
    val driveFolderId by viewModel.driveFolderId.collectAsStateWithLifecycle()
    val driveFolderName by viewModel.driveFolderName.collectAsStateWithLifecycle()
    val targetSubfolder by viewModel.targetSubfolder.collectAsStateWithLifecycle()
    val subfolderAtLastSizeChange by viewModel.subfolderAtLastSizeChange.collectAsStateWithLifecycle()

    val currentFolderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
    val currentFolderName by viewModel.currentFolderName.collectAsStateWithLifecycle()
    val folderPathStack by viewModel.folderPathStack.collectAsStateWithLifecycle()
    val subfolders by viewModel.subfolders.collectAsStateWithLifecycle()
    val isFolderLoading by viewModel.isFolderLoading.collectAsStateWithLifecycle()
    val folderError by viewModel.folderError.collectAsStateWithLifecycle()
    val folderDiagnostic by viewModel.folderDiagnostic.collectAsStateWithLifecycle()

    val driveFiles by viewModel.driveFiles.collectAsStateWithLifecycle()
    val isDriveFilesLoading by viewModel.isDriveFilesLoading.collectAsStateWithLifecycle()
    val driveFilesError by viewModel.driveFilesError.collectAsStateWithLifecycle()

    var activeOverlayModal by remember { mutableStateOf<DashboardOverlayModal>(DashboardOverlayModal.None) }
    var activeFolderPickerState by remember { mutableStateOf<ActiveFolderPickerState>(ActiveFolderPickerState.Closed) }
    var passportDriveSelection by remember {
        mutableStateOf<PassportDriveSelection>(PassportDriveSelection.DefaultSyncFolder)
    }
    val passportDriveTarget = when (val selection = passportDriveSelection) {
        is PassportDriveSelection.DefaultSyncFolder -> com.example.ui.PassportDriveTarget(
            folderId = driveFolderId,
            folderName = driveFolderName.ifBlank { "My Drive" }
        )
        is PassportDriveSelection.CustomFolder -> com.example.ui.PassportDriveTarget(
            folderId = selection.folderId,
            folderName = selection.folderName.ifBlank { "My Drive" }
        )
    }
    var scanFlowModalState by remember { mutableStateOf<ScanFlowModalState>(ScanFlowModalState.None) }
    var fabMenuState by remember { mutableStateOf(FabMenuState.COLLAPSED) }
    var activeTab by remember { mutableStateOf(AppScreenTab.DASHBOARD) }
    var searchQuery by remember { mutableStateOf("") }
    var capturesSearchQuery by remember { mutableStateOf("") }
    var capturesFilterMode by remember { mutableStateOf(CapturesFilterMode.ALL) }
    var selectedDocIds by remember { mutableStateOf(setOf<Int>()) }

    val nameBeforeType by viewModel.nameBeforeType.collectAsStateWithLifecycle()
    val activeQueue by viewModel.activeQueue.collectAsStateWithLifecycle()
    val publicFolderSize by viewModel.publicFolderSize.collectAsStateWithLifecycle()
    val storageLimitMb by viewModel.storageLimitMb.collectAsStateWithLifecycle()
    val batchPagesPerDoc by viewModel.batchPagesPerDoc.collectAsStateWithLifecycle()
    var sessionBatchPagesPerDoc by remember(batchPagesPerDoc) { mutableIntStateOf(batchPagesPerDoc) }
    val scope = rememberCoroutineScope()
    val batchVerificationGroups by viewModel.batchVerificationGroups.collectAsStateWithLifecycle()

    BackHandler(enabled = selectedDocIds.isNotEmpty() || fabMenuState == FabMenuState.EXPANDED || activeTab != AppScreenTab.DASHBOARD) {
        when {
            selectedDocIds.isNotEmpty() -> selectedDocIds = emptySet()
            fabMenuState == FabMenuState.EXPANDED -> fabMenuState = FabMenuState.COLLAPSED
            activeTab != AppScreenTab.DASHBOARD -> activeTab = AppScreenTab.DASHBOARD
        }
    }

    // Google Sign-In setup
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive"))
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    LaunchedEffect(Unit) {
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        if (lastAccount != null && !lastAccount.email.isNullOrBlank()) {
            if (lastAccount.idToken != null && com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
                val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(lastAccount.idToken, null)
                com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(credential)
                    .addOnFailureListener { e ->
                        android.util.Log.e("MainActivity", "Silent Firebase Auth sign-in failed", e)
                    }
            }
        }
    }

    val recoveryIntent by viewModel.recoveryIntent.collectAsStateWithLifecycle()

    val recoveryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.clearRecoveryIntent()
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "Permissions granted! Retrying operation...", Toast.LENGTH_SHORT).show()
            if (googleEmail != null) {
                viewModel.fetchSubfolders(context, currentFolderId)
            }
        } else {
            Toast.makeText(context, "Google Drive authorization was denied or cancelled.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(recoveryIntent) {
        val intent = recoveryIntent
        if (intent != null) {
            recoveryLauncher.launch(intent)
        }
    }

    var updatePromptState by remember { mutableStateOf<UpdatePromptState>(UpdatePromptState.Idle) }

    LaunchedEffect(Unit) {
        try {
            val info = com.example.util.UpdateChecker.checkForUpdates(context)
            if (info.hasUpdate) {
                updatePromptState = UpdatePromptState.Available(info)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(activeTab, googleEmail) {
        if (activeTab == AppScreenTab.FILES && googleEmail != null) {
            viewModel.fetchDriveFiles(context)
        }
        viewModel.updatePublicFolderSize()
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null && !account.email.isNullOrBlank()) {
                    viewModel.loginUser(account.email!!, deviceId)
                    Toast.makeText(context, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
                    if (account.idToken != null) {
                        val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(account.idToken, null)
                        com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(credential)
                            .addOnFailureListener { e ->
                                android.util.Log.e("MainActivity", "Firebase Auth credential sign-in error", e)
                            }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Connection failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } else {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                task.getResult(ApiException::class.java)
            } catch (e: ApiException) {
                e.printStackTrace()
                val extraMsg = when (e.statusCode) {
                    10 -> "\n\nError Code 10 (DEVELOPER_ERROR):\nThis usually means the app's signing certificate SHA-1 fingerprint (of your installed APK) does not match the custom signing SHA-1 registered in your Google Cloud / Firebase Console. Please add your signing certificate's SHA-1 to your GCP Credentials."
                    else -> ""
                }
                Toast.makeText(context, "Google Sign-In failed [Status Code ${e.statusCode}]: ${e.localizedMessage}$extraMsg", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Google Sign-In failed/cancelled [Result Code: ${result.resultCode}]", Toast.LENGTH_LONG).show()
            }
        }
    }

    val autoEnhanceEnabled by viewModel.autoEnhanceEnabled.collectAsStateWithLifecycle()
    val useA4Format by viewModel.useA4Format.collectAsStateWithLifecycle()

    val accumulatedPageUris = remember { mutableStateListOf<Uri>() }
    var multiScanSessionState by remember { mutableStateOf<MultiScanSessionState>(MultiScanSessionState.Idle) }

    val scanner = remember(autoEnhanceEnabled) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(2)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(if (autoEnhanceEnabled) GmsDocumentScannerOptions.SCANNER_MODE_FULL else GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .build()
        GmsDocumentScanning.getClient(options)
    }
    
    val batchScanner = remember(autoEnhanceEnabled) {
        val batchOptions = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(if (autoEnhanceEnabled) GmsDocumentScannerOptions.SCANNER_MODE_FULL else GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .build()
        GmsDocumentScanning.getClient(batchOptions)
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val currentTargetEditDocId = multiScanSessionState.targetEditDocIdOrNull
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages
            if (!pages.isNullOrEmpty()) {
                val imageUris = pages.map { it.imageUri }
                accumulatedPageUris.addAll(imageUris)
                multiScanSessionState = MultiScanSessionState.ReviewingPages(currentTargetEditDocId)
            } else if (accumulatedPageUris.isNotEmpty()) {
                multiScanSessionState = MultiScanSessionState.ReviewingPages(currentTargetEditDocId)
            } else {
                multiScanSessionState = MultiScanSessionState.Idle
            }
        } else {
            if (multiScanSessionState is MultiScanSessionState.LaunchingAdditionalScan && accumulatedPageUris.isNotEmpty()) {
                multiScanSessionState = MultiScanSessionState.ReviewingPages(currentTargetEditDocId)
            } else {
                accumulatedPageUris.clear()
                multiScanSessionState = MultiScanSessionState.Idle
            }
        }
    }

    val batchScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                val imageUris = pages.map { it.imageUri }
                viewModel.processBatchScannedImages(imageUris, sessionBatchPagesPerDoc)
            }
        }
    }

    val multiScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                val imageUris = pages.map { it.imageUri }
                viewModel.processMultiScannedImages(imageUris)
            }
        }
    }

    val launchSingleScan: () -> Unit = {
        accumulatedPageUris.clear()
        multiScanSessionState = MultiScanSessionState.LaunchingInitialScan(targetEditDocId = null)
        val activity = context.findActivity()
        if (activity != null) {
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener {
                    multiScanSessionState = MultiScanSessionState.Idle
                    Toast.makeText(context, "Failed to start scanner: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
        } else {
            multiScanSessionState = MultiScanSessionState.Idle
        }
    }

    val launchAdditionalScan: () -> Unit = {
        val currentTargetDocId = multiScanSessionState.targetEditDocIdOrNull
        multiScanSessionState = MultiScanSessionState.LaunchingAdditionalScan(currentTargetDocId)
        val activity = context.findActivity()
        if (activity != null) {
            scanner.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.addOnFailureListener { e ->
                if (accumulatedPageUris.isNotEmpty()) {
                    multiScanSessionState = MultiScanSessionState.ReviewingPages(currentTargetDocId)
                } else {
                    multiScanSessionState = MultiScanSessionState.Idle
                }
                Toast.makeText(context, "Failed to launch scanner: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            if (accumulatedPageUris.isNotEmpty()) {
                multiScanSessionState = MultiScanSessionState.ReviewingPages(currentTargetDocId)
            } else {
                multiScanSessionState = MultiScanSessionState.Idle
            }
            Toast.makeText(context, "Activity context not found", Toast.LENGTH_SHORT).show()
        }
    }

    val launchEditScan: (Int) -> Unit = { docId ->
        accumulatedPageUris.clear()
        multiScanSessionState = MultiScanSessionState.LaunchingInitialScan(targetEditDocId = docId)
        val activity = context.findActivity()
        if (activity != null) {
            scanner.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.addOnFailureListener { e ->
                Toast.makeText(context, "Failed to launch scanner: ${e.message}", Toast.LENGTH_LONG).show()
                multiScanSessionState = MultiScanSessionState.Idle
            }
        } else {
            Toast.makeText(context, "Activity context not found", Toast.LENGTH_SHORT).show()
            multiScanSessionState = MultiScanSessionState.Idle
        }
    }

    val launchMultiScan: () -> Unit = {
        val activity = context.findActivity()
        if (activity != null) {
            val multiOptions = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(if (autoEnhanceEnabled) GmsDocumentScannerOptions.SCANNER_MODE_FULL else GmsDocumentScannerOptions.SCANNER_MODE_BASE)
                .build()
            val multiClient = GmsDocumentScanning.getClient(multiOptions)
            multiClient.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    multiScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Failed to start multi scanner: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    val launchBatchScan: () -> Unit = {
        val activity = context.findActivity()
        if (activity != null) {
            batchScanner.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    batchScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Failed to start batch scanner: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    val launchContinuousBatchCamera: (Int) -> Unit = { initialPages ->
        scanFlowModalState = ScanFlowModalState.ContinuousBatchCamera(initialPages)
    }

    val executeScanAction: (ScanActionType) -> Unit = { actionType ->
        when (actionType) {
            ScanActionType.SINGLE -> launchSingleScan()
            ScanActionType.MULTI -> launchMultiScan()
            ScanActionType.BATCH -> scanFlowModalState = ScanFlowModalState.BatchSetup
        }
    }

    val triggerScanActionWithSizeCheck: (ScanActionType) -> Unit = { actionType ->
        val lastSizeSub = subfolderAtLastSizeChange
        val shouldWarn = targetSubfolder.trim().isNotEmpty() &&
                lastSizeSub != null &&
                targetSubfolder.trim() != lastSizeSub.trim()
        if (shouldWarn) {
            scanFlowModalState = ScanFlowModalState.TargetSizeWarning(actionType)
        } else {
            executeScanAction(actionType)
        }
    }

    if (authState != com.example.ui.AuthState.APPROVED) {
        val dbLogs by viewModel.dbLogs.collectAsStateWithLifecycle()
        AuthGuardScreen(
            authState = authState,
            googleSignInClient = googleSignInClient,
            googleSignInLauncher = googleSignInLauncher,
            onSignOut = {
                googleSignInClient.signOut().addOnCompleteListener {
                    viewModel.setGoogleEmail(null)
                    viewModel.clearDbLogs()
                }
            },
            userEmail = googleEmail,
            dbLogs = dbLogs,
            onClearLogs = { viewModel.clearDbLogs() },
            onRunDiagnostics = { viewModel.runDiagnostics() }
        )
        return
    }

    val activeUpdatePrompt = updatePromptState as? UpdatePromptState.Available
    if (activeUpdatePrompt != null) {
        com.example.ui.InAppUpdateDialog(
            updateInfo = activeUpdatePrompt.info,
            onDismiss = { updatePromptState = UpdatePromptState.Idle }
        )
    }

    if (isAdmin && newRequestNotification != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNewRequestNotification() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Alert Icon",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Access Request")
                }
            },
            text = {
                Text(
                    text = "A new user ($newRequestNotification) has just requested access to the application in Firestore.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
                    confirmButton = {
                Button(
                    onClick = {
                        activeTab = AppScreenTab.APPROVALS
                        viewModel.dismissNewRequestNotification()
                    }
                ) {
                    Text("Go to Approvals")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissNewRequestNotification() }
                ) {
                    Text("Dismiss")
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(
        topBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (activeTab == AppScreenTab.SETTINGS) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { activeTab = AppScreenTab.DASHBOARD },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Home",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "Settings & Tools",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .claySurface(
                                        baseColor = MaterialTheme.colorScheme.primaryContainer,
                                        accentColor = MaterialTheme.colorScheme.primary,
                                        cornerRadius = 14.dp,
                                        bevelDepth = 3.dp
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                                    contentDescription = "App Logo",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Dasmo Cyber Tool",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.clickable { activeTab = AppScreenTab.SYNC }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (googleEmail != null) Color(0xFF00E676) else Color(0xFFFFB300))
                                    )
                                    Text(
                                        text = if (googleEmail != null) "DRIVE SYNC ON" else "LOCAL MODE • TAP TO LINK",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (googleEmail != null) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .claySurface(
                                        baseColor = MaterialTheme.colorScheme.primaryContainer,
                                        accentColor = MaterialTheme.colorScheme.primary,
                                        cornerRadius = 12.dp,
                                        bevelDepth = 2.5.dp
                                    )
                                    .clickable { activeOverlayModal = DashboardOverlayModal.SizeDialog(targetSizeKb.toString()) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${imageFormat.uppercase()} • ${targetSizeKb}KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { activeTab = AppScreenTab.SETTINGS },
                                modifier = Modifier
                                    .size(40.dp)
                                    .claySurface(
                                        baseColor = MaterialTheme.colorScheme.surfaceVariant,
                                        accentColor = MaterialTheme.colorScheme.primary,
                                        cornerRadius = 20.dp,
                                        bevelDepth = 2.5.dp
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val navItems = buildList {
                        add(BottomNavEntry.TabItem(AppScreenTab.DASHBOARD, "HOME", Icons.Default.Home))
                        add(BottomNavEntry.TabItem(AppScreenTab.FILES, "DRIVE", Icons.Default.CloudDone))
                        add(BottomNavEntry.FabSpacer)
                        add(BottomNavEntry.TabItem(AppScreenTab.CAPTURES, "CAPTURES", Icons.Default.FolderSpecial))
                        add(BottomNavEntry.TabItem(AppScreenTab.SYNC, "SYNC", Icons.Default.Sync))
                        if (isAdmin) {
                            add(BottomNavEntry.TabItem(AppScreenTab.APPROVALS, "ADMIN", Icons.Default.AdminPanelSettings))
                        }
                    }

                    navItems.forEach { entry ->
                        when (entry) {
                            BottomNavEntry.FabSpacer -> {
                                if (activeTab == AppScreenTab.DASHBOARD) {
                                    Spacer(modifier = Modifier.weight(0.9f))
                                }
                            }
                            is BottomNavEntry.TabItem -> {
                                val isSelected = activeTab == entry.tab
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            fabMenuState = FabMenuState.COLLAPSED
                                            activeTab = entry.tab
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Box(
                                        modifier = if (isSelected) {
                                            Modifier
                                                .claySurface(
                                                    baseColor = MaterialTheme.colorScheme.primaryContainer,
                                                    accentColor = MaterialTheme.colorScheme.primary,
                                                    cornerRadius = 16.dp,
                                                    bevelDepth = 2.5.dp
                                                )
                                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                        } else {
                                            Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = entry.icon,
                                            contentDescription = entry.label,
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = entry.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (activeTab == AppScreenTab.DASHBOARD) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset(y = 28.dp)
                ) {
                    val isFabExpanded = fabMenuState == FabMenuState.EXPANDED
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isFabExpanded,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it / 2 })
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.SmallFloatingActionButton(
                                    onClick = {
                                        fabMenuState = FabMenuState.COLLAPSED
                                        activeTab = AppScreenTab.PASSPORT
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person, 
                                        contentDescription = "Passport Photo"
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Passport", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.SmallFloatingActionButton(
                                    onClick = {
                                        fabMenuState = FabMenuState.COLLAPSED
                                        triggerScanActionWithSizeCheck(ScanActionType.BATCH)
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List, 
                                        contentDescription = "Auto-Batch Scan"
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Auto-Batch", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FloatingActionButton(
                                    onClick = {
                                        fabMenuState = FabMenuState.COLLAPSED
                                        triggerScanActionWithSizeCheck(ScanActionType.MULTI)
                                    },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add, 
                                        contentDescription = "Multi-Page Scan",
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Multi-Page", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.SmallFloatingActionButton(
                                    onClick = {
                                        fabMenuState = FabMenuState.COLLAPSED
                                        triggerScanActionWithSizeCheck(ScanActionType.SINGLE)
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit, 
                                        contentDescription = "Single Scan"
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Single", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = {
                            fabMenuState = if (isFabExpanded) FabMenuState.COLLAPSED else FabMenuState.EXPANDED
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.size(64.dp)
                    ) {
                        val rotation by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isFabExpanded) 45f else 0f
                        )
                        Icon(
                            imageVector = Icons.Default.Add, 
                            contentDescription = "Scan Options",
                            modifier = Modifier.size(36.dp).rotate(rotation)
                        )
                    }
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (activeTab) {
                AppScreenTab.DASHBOARD -> {
                    val syncedCount = remember(documents) { documents.count { it.isUploaded } }
                    val pendingSyncCount = remember(documents) { documents.count { !it.isUploaded } }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Quick Scan & Live Metrics Hero Card (3D Claymorphic Command Center)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // CLAY ELEMENT TYPE 2: 3D Puffed Clay Metric Capsules
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ClayMetricCapsule(
                                        valueText = "${documents.size}",
                                        labelText = "TOTAL SCANS",
                                        accentColor = MaterialTheme.colorScheme.primary,
                                        icon = Icons.Default.Description,
                                        onClick = {
                                            capturesFilterMode = CapturesFilterMode.ALL
                                            activeTab = AppScreenTab.CAPTURES
                                        },
                                        modifier = Modifier.weight(1f)
                                    )

                                    ClayMetricCapsule(
                                        valueText = "$syncedCount",
                                        labelText = "DRIVE SYNCED",
                                        accentColor = Color(0xFF00C853),
                                        icon = Icons.Default.CloudDone,
                                        onClick = {
                                            capturesFilterMode = CapturesFilterMode.SYNCED
                                            activeTab = AppScreenTab.CAPTURES
                                        },
                                        modifier = Modifier.weight(1f)
                                    )

                                    ClayMetricCapsule(
                                        valueText = "$pendingSyncCount",
                                        labelText = "LOCAL / PENDING",
                                        accentColor = if (pendingSyncCount > 0) Color(0xFFFF5252) else Color(0xFF64B5F6),
                                        icon = if (pendingSyncCount > 0) Icons.Default.Schedule else Icons.Default.Verified,
                                        onClick = {
                                            capturesFilterMode = if (pendingSyncCount > 0) CapturesFilterMode.PENDING else CapturesFilterMode.ALL
                                            activeTab = AppScreenTab.CAPTURES
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                // CLAY ELEMENT TYPE 1: Swiggy / Blinkit 3D Clay Action Category Tiles
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "INSTANT SCAN STUDIO",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "1-Tap 3D Quick Launch",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 1. Single Scan (Swiggy Coral-Orange Clay)
                                    SwiggyClayActionTile(
                                        title = "Single",
                                        subtitle = "1-PAGE",
                                        icon = Icons.Default.DocumentScanner,
                                        accentColor = Color(0xFFFF6D3A),
                                        isHeroFeatured = false,
                                        onClick = { triggerScanActionWithSizeCheck(ScanActionType.SINGLE) },
                                        modifier = Modifier.weight(1f)
                                    )

                                    // 2. Multi-Page Scan (Hero Primary Clay)
                                    SwiggyClayActionTile(
                                        title = "Multi-Page",
                                        subtitle = "ID / DOCS",
                                        icon = Icons.Default.LibraryAdd,
                                        accentColor = MaterialTheme.colorScheme.primary,
                                        isHeroFeatured = true,
                                        onClick = { triggerScanActionWithSizeCheck(ScanActionType.MULTI) },
                                        modifier = Modifier.weight(1f)
                                    )

                                    // 3. Auto-Batch (Blinkit Emerald Clay)
                                    SwiggyClayActionTile(
                                        title = "Auto-Batch",
                                        subtitle = "BULK AI",
                                        icon = Icons.Default.Layers,
                                        accentColor = Color(0xFF00C853),
                                        isHeroFeatured = false,
                                        onClick = { triggerScanActionWithSizeCheck(ScanActionType.BATCH) },
                                        modifier = Modifier.weight(1f)
                                    )

                                    // 4. Passport Studio (Royal Violet Clay)
                                    SwiggyClayActionTile(
                                        title = "Passport",
                                        subtitle = "STUDIO",
                                        icon = Icons.Default.AccountBox,
                                        accentColor = Color(0xFF7C4DFF),
                                        isHeroFeatured = false,
                                        onClick = { activeTab = AppScreenTab.PASSPORT },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Live Status & Active Session Queue (explicit StatusBannerState machine)
                        when (val banner = statusBannerState) {
                            com.example.ui.StatusBannerState.Hidden -> Unit
                            is com.example.ui.StatusBannerState.Processing -> {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = banner.message.ifEmpty { "Optimizing & processing document..." },
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            is com.example.ui.StatusBannerState.Visible -> {
                                val bannerContainerColor = when (banner.tone) {
                                    com.example.ui.StatusBannerTone.SUCCESS -> Color(0xFF00E676).copy(alpha = 0.12f)
                                    com.example.ui.StatusBannerTone.WARNING -> Color(0xFFFFB300).copy(alpha = 0.16f)
                                    com.example.ui.StatusBannerTone.INFO -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    com.example.ui.StatusBannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                }
                                val bannerTextColor = when (banner.tone) {
                                    com.example.ui.StatusBannerTone.SUCCESS -> Color(0xFF00E676)
                                    com.example.ui.StatusBannerTone.WARNING -> Color(0xFFFFB300)
                                    com.example.ui.StatusBannerTone.INFO -> MaterialTheme.colorScheme.primary
                                    com.example.ui.StatusBannerTone.ERROR -> MaterialTheme.colorScheme.error
                                }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = bannerContainerColor),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = banner.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = bannerTextColor,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = { viewModel.clearStatusMessage() }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }

                        if (activeQueue.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF00E676))
                                            )
                                            Text(
                                                "Active Session Queue (${activeQueue.size})",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        TextButton(
                                            onClick = { viewModel.clearActiveQueue() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Clear Done", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Column(
                                        modifier = Modifier
                                            .heightIn(max = 260.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        activeQueue.forEach { item ->
                                            val isDone = item.phase == com.example.ui.QueueItemPhase.COMPLETED
                                            val isLocalNeedsSync = item.phase == com.example.ui.QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC
                                            val isFailed = item.phase == com.example.ui.QueueItemPhase.FAILED
                                            val isCancelled = item.phase == com.example.ui.QueueItemPhase.CANCELLED
                                            val isItemProcessing = item.phase == com.example.ui.QueueItemPhase.PROCESSING
                                            val canRetryUpload = (isFailed || isLocalNeedsSync) && item.docId != null

                                            val phaseColor = when {
                                                isDone -> Color(0xFF00E676)
                                                isLocalNeedsSync -> Color(0xFFFFB300)
                                                isFailed || isCancelled -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.primary
                                            }
                                            val phaseBadgeBg = when {
                                                isDone -> Color(0xFF00E676).copy(alpha = 0.16f)
                                                isLocalNeedsSync -> Color(0xFFFFB300).copy(alpha = 0.18f)
                                                isFailed || isCancelled -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                            }
                                            val phaseIcon = when {
                                                isDone -> Icons.Default.CheckCircle
                                                isLocalNeedsSync -> Icons.Default.CloudOff
                                                isFailed || isCancelled -> Icons.Default.Warning
                                                else -> Icons.Default.Sync
                                            }

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                                                    .padding(10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Icon(
                                                            imageVector = phaseIcon,
                                                            contentDescription = "Status Icon",
                                                            tint = phaseColor,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                        Column {
                                                            Text(
                                                                text = "${item.personName}'s ${item.documentType}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                            val formatLabel = when (item.format) {
                                                                com.example.ui.UploadFormat.PDF -> "PDF"
                                                                com.example.ui.UploadFormat.JPEG -> "JPEG"
                                                                com.example.ui.UploadFormat.BOTH -> "JPEG + PDF"
                                                            }
                                                            Text(
                                                                text = "Format: $formatLabel",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(phaseBadgeBg)
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = item.status,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = phaseColor
                                                        )
                                                    }
                                                }

                                                if (canRetryUpload && item.docId != null) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.End
                                                    ) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                viewModel.retryUpload(context, setOf(item.docId))
                                                                Toast.makeText(context, "Resuming upload for ${item.personName}...", Toast.LENGTH_SHORT).show()
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                            border = BorderStroke(1.dp, phaseColor)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Refresh,
                                                                contentDescription = "Retry Upload",
                                                                tint = phaseColor,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(
                                                                text = "Retry Upload",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = phaseColor
                                                            )
                                                        }
                                                    }
                                                }

                                                if (isItemProcessing) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    LinearProgressIndicator(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(4.dp)
                                                            .clip(RoundedCornerShape(2.dp)),
                                                        color = MaterialTheme.colorScheme.primary,
                                                        trackColor = MaterialTheme.colorScheme.outlineVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Smart Subfolder Input Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Text(
                                            "Destination Subfolder",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "Root: ${driveFolderName ?: "My Drive"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            activeFolderPickerState = ActiveFolderPickerState.Open(FolderPickerPurpose.DEFAULT_SYNC_FOLDER)
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = targetSubfolder,
                                    onValueChange = { viewModel.setTargetSubfolder(it) },
                                    placeholder = { Text("Optional client / batch folder (e.g. Subhojit_KYC)") },
                                    singleLine = true,
                                    trailingIcon = {
                                        if (targetSubfolder.isNotEmpty()) {
                                            IconButton(onClick = { viewModel.setTargetSubfolder("") }) {
                                                Icon(Icons.Default.Close, contentDescription = "Clear Subfolder", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        cursorColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }

                        // Processing Engine & Presets Card (Complete 2x2 Bento Grid + Streamlined Workflow Toggles)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
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
                                    Column {
                                        Text(
                                            text = "Engine & Output Presets",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Adaptive DCT • Micro-Denoise • Zero White-Bleach",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .claySurface(
                                                baseColor = MaterialTheme.colorScheme.primary,
                                                accentColor = MaterialTheme.colorScheme.primary,
                                                cornerRadius = 10.dp,
                                                bevelDepth = 2.5.dp,
                                                isHeroSolid = true
                                            )
                                            .padding(horizontal = 9.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "PRO ENGINE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                    }
                                }

                                // CLAY ELEMENT TYPE 3: 2x2 Sculpted 3D Clay Bento Grid
                                // Row 1: Target Size & Output Format
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Bento Tile 1: Target Size (Swiggy Coral-Orange Clay)
                                    ClayBentoPresetTile(
                                        badgeLabel = "TARGET SIZE",
                                        valueTitle = "${targetSizeKb} KB",
                                        subtitle = "Tap to set KB limit",
                                        icon = Icons.Default.Tune,
                                        accentColor = Color(0xFFFF6D3A),
                                        onClick = { activeOverlayModal = DashboardOverlayModal.SizeDialog(targetSizeKb.toString()) },
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Bento Tile 2: Output Format (1-Tap Cycle PDF -> JPEG -> BOTH)
                                    ClayBentoPresetTile(
                                        badgeLabel = "OUTPUT FORMAT",
                                        valueTitle = when (imageFormat.uppercase()) {
                                            "PDF" -> "PDF DOC"
                                            "BOTH" -> "PDF + JPEG"
                                            else -> "JPEG IMG"
                                        },
                                        subtitle = "1-tap cycle format",
                                        icon = Icons.Default.PictureAsPdf,
                                        accentColor = Color(0xFF00C853),
                                        onClick = {
                                            val nextFmt = when (imageFormat.uppercase()) {
                                                "PDF" -> "JPEG"
                                                "JPEG" -> "BOTH"
                                                else -> "PDF"
                                            }
                                            viewModel.updateImageFormat(nextFmt)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                // Row 2: Parser Engine & Image Codec
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Bento Tile 3: Parser Engine
                                    ClayBentoPresetTile(
                                        badgeLabel = "PARSER ENGINE",
                                        valueTitle = if (enableAiAnalysis) "CLOUD AI" else "LOCAL OCR",
                                        subtitle = if (enableAiAnalysis) "Deep Gemini Vision" else "99% ID Name Parser",
                                        icon = if (enableAiAnalysis) Icons.Default.AutoAwesome else Icons.Default.Memory,
                                        accentColor = Color(0xFF7C4DFF),
                                        onClick = { viewModel.updateEnableAiAnalysis(!enableAiAnalysis) },
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Bento Tile 4: Smart Compression Codec
                                    ClayBentoPresetTile(
                                        badgeLabel = "IMAGE CODEC",
                                        valueTitle = if (compressionCodec.equals("WEBP", ignoreCase = true)) "WEBP LOSSY" else "ADAPTIVE DCT",
                                        subtitle = if (compressionCodec.equals("WEBP", ignoreCase = true)) "Ultra-compact text" else "Zero white-bleach",
                                        icon = Icons.Default.HighQuality,
                                        accentColor = Color(0xFF00B0FF),
                                        onClick = {
                                            val nextCodec = if (compressionCodec.equals("WEBP", ignoreCase = true)) "JPEG" else "WEBP"
                                            viewModel.updateCompressionCodec(nextCodec)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                // Streamlined Workflow Switches
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        // Switch 1: Confirmation Popup
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.updateShowConfirmation(!showConfirmation) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (showConfirmation) "Verify Details Before Sync" else "Instant Auto-Save & Sync",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = if (showConfirmation) "Review person name, doc type & KB size popup" else "Zero-prompt background queue processing",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = showConfirmation,
                                                onCheckedChange = { viewModel.updateShowConfirmation(it) }
                                            )
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 12.dp))

                                        // Switch 2: Smart Ink & Denoise Filter
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.updateAutoEnhanceEnabled(!autoEnhanceEnabled) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (autoEnhanceEnabled) "Smart Ink & Micro-Denoise ON" else "Raw Camera Capture (Filter OFF)",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = if (autoEnhanceEnabled) "Sharpens small text & strips grain without bleaching colors" else "Preserves raw camera lighting",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = autoEnhanceEnabled,
                                                onCheckedChange = { viewModel.updateAutoEnhanceEnabled(it) }
                                            )
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 12.dp))

                                        // Switch 3: A4 Xerox ID Sheet
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.updateUseA4Format(!useA4Format) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (useA4Format) "ID Card A4 Xerox Sheet Layout" else "Compact Edge-to-Edge Stack",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = if (useA4Format) "Places Front & Back on upper A4 canvas for direct printing" else "Stacks Front & Back tightly with minimal margin",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = useA4Format,
                                                onCheckedChange = { viewModel.updateUseA4Format(it) }
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { scanFlowModalState = ScanFlowModalState.BatchSetup }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Layers,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            "Batch Grouping:",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = if (batchPagesPerDoc == 1) "1 Page / Doc (Single)" else if (batchPagesPerDoc == 2) "2 Pages / Doc (Front & Back)" else "$batchPagesPerDoc Pages / Doc",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        "Configure ➔",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Visual Storage Usage Indicator Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = "Storage",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Dasmo Scan Storage",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "Documents/Dasmo Scan",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))

                                val limitBytes = storageLimitMb.toLong() * 1024 * 1024L
                                val progress = (publicFolderSize.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
                                val isNearLimit = publicFolderSize > limitBytes * 0.8
                                val limitLabel = if (storageLimitMb >= 1000) "${storageLimitMb / 1000} GB" else "$storageLimitMb MB"

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Column {
                                        Text(
                                            text = "Occupied Space",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = if (publicFolderSize == 0L) "0.00 KB" else {
                                                val kb = publicFolderSize / 1024.0
                                                val mb = kb / 1024.0
                                                val gb = mb / 1024.0
                                                when {
                                                    gb >= 1.0 -> "${String.format("%.2f", gb)} GB"
                                                    mb >= 1.0 -> "${String.format("%.2f", mb)} MB"
                                                    else -> "${String.format("%.2f", kb)} KB"
                                                }
                                            },
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "Quota: $limitLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isNearLimit) "⚠️ Storage near limit — cleanup advised" else "✓ Local storage healthy",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(
                                        onClick = { activeOverlayModal = DashboardOverlayModal.ClearJunkWarning() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Trash",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Clean Up Junk",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(120.dp))
                    }
                }
                AppScreenTab.CAPTURES -> {
                    val filteredCaptures = remember(documents, capturesSearchQuery, capturesFilterMode) {
                        documents.filter { doc ->
                            val matchesMode = when (capturesFilterMode) {
                                CapturesFilterMode.SYNCED -> doc.isUploaded
                                CapturesFilterMode.PENDING -> !doc.isUploaded
                                CapturesFilterMode.ALL -> true
                            }
                            val q = capturesSearchQuery.trim()
                            val matchesSearch = q.isEmpty() ||
                                    doc.personName.contains(q, ignoreCase = true) ||
                                    doc.documentType.contains(q, ignoreCase = true) ||
                                    doc.fileName.contains(q, ignoreCase = true)
                            matchesMode && matchesSearch
                        }
                    }

                    // Header & Selection Toolbar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (selectedDocIds.isNotEmpty()) "${selectedDocIds.size} Selected" else "Recent Captures (${filteredCaptures.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (selectedDocIds.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    val count = selectedDocIds.size
                                    viewModel.retryUpload(context, selectedDocIds)
                                    selectedDocIds = setOf()
                                    Toast.makeText(context, "Resuming upload for $count document(s)...", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = "Upload to Drive", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    val urisToShare = ArrayList<Uri>()
                                    val sharedFiles = mutableListOf<File>()
                                    for (docId in selectedDocIds) {
                                        val doc = documents.find { it.id == docId }
                                        if (doc != null) {
                                            val file = File(doc.localFilePath)
                                            if (file.exists()) {
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.provider",
                                                    file
                                                )
                                                urisToShare.add(uri)
                                                sharedFiles.add(file)
                                            }
                                        }
                                    }
                                    if (urisToShare.isNotEmpty()) {
                                        val allPdf = sharedFiles.all { it.name.endsWith(".pdf", ignoreCase = true) }
                                        val allImages = sharedFiles.none { it.name.endsWith(".pdf", ignoreCase = true) }
                                        val shareMime = when {
                                            allPdf -> "application/pdf"
                                            allImages -> "image/*"
                                            else -> "*/*"
                                        }
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = shareMime
                                            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, urisToShare)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share Documents"))
                                    } else {
                                        Toast.makeText(context, "No local files found to share.", Toast.LENGTH_SHORT).show()
                                    }
                                    selectedDocIds = setOf()
                                }) {
                                    Icon(imageVector = Icons.Default.Share, contentDescription = "Share Selected")
                                }
                                if (selectedDocIds.size >= 2) {
                                    IconButton(onClick = {
                                        activeOverlayModal = DashboardOverlayModal.MergeToPdf(
                                            MergeDialogState(
                                                fileName = "Merged_${System.currentTimeMillis()}",
                                                folderName = "",
                                                folderId = null,
                                                targetSizeKb = targetSizeKb.toString()
                                            )
                                        )
                                    }) {
                                        Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = "Merge to PDF", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                IconButton(onClick = {
                                    val count = selectedDocIds.size
                                    viewModel.deleteSelectedDocuments(selectedDocIds)
                                    selectedDocIds = setOf()
                                    Toast.makeText(context, "Deleted $count document(s)", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                                }
                                IconButton(onClick = { selectedDocIds = setOf() }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Selection")
                                }
                            }
                        } else if (documents.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    selectedDocIds = filteredCaptures.map { it.id }.toSet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Select All", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Search & Filter Bar
                    if (documents.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = capturesSearchQuery,
                                onValueChange = { capturesSearchQuery = it },
                                placeholder = { Text("Search by client name, ID type, or file...") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = "Search Captures")
                                },
                                trailingIcon = {
                                    if (capturesSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { capturesSearchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear Search")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    CapturesFilterMode.ALL to "All (${documents.size})",
                                    CapturesFilterMode.SYNCED to "Synced (${documents.count { it.isUploaded }})",
                                    CapturesFilterMode.PENDING to "Pending (${documents.count { !it.isUploaded }})"
                                ).forEach { (mode, label) ->
                                    val isSelected = capturesFilterMode == mode
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { capturesFilterMode = mode },
                                        label = {
                                            Text(
                                                text = label,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (filteredCaptures.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = if (documents.isEmpty()) "No documents scanned yet. Tap any scan action on Home to start."
                                           else "No captures match your current search or filter.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp, start = 16.dp, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            items(
                                count = filteredCaptures.size,
                                key = { index -> filteredCaptures[index].id }
                            ) { index ->
                                val doc = filteredCaptures[index]
                                val isSelected = selectedDocIds.contains(doc.id)
                                DocumentItem(
                                    doc = doc,
                                    isSelected = isSelected,
                                    onLongClick = {
                                        selectedDocIds = selectedDocIds + doc.id
                                    },
                                    onClick = {
                                        if (selectedDocIds.isNotEmpty()) {
                                            selectedDocIds = if (isSelected) {
                                                selectedDocIds - doc.id
                                            } else {
                                                selectedDocIds + doc.id
                                            }
                                        } else {
                                            activeOverlayModal = DashboardOverlayModal.DocumentPreview(doc)
                                        }
                                    },
                                    onRetryClick = if (!doc.isUploaded) {
                                        {
                                            viewModel.retryUpload(context, setOf(doc.id))
                                            Toast.makeText(context, "Resuming upload: ${doc.fileName}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }
                AppScreenTab.PASSPORT -> {
                    com.example.ui.PassportPhotoScreen(
                        onBack = { activeTab = AppScreenTab.DASHBOARD },
                        googleEmail = googleEmail,
                        driveTarget = passportDriveTarget,
                        onChooseDriveFolder = {
                            activeFolderPickerState = ActiveFolderPickerState.Open(FolderPickerPurpose.PASSPORT_DESTINATION_FOLDER)
                        },
                        onSaveAndUploadPassport = { tempFile, clientName, isPrintSheet, uploadToDrive, onResult ->
                            viewModel.saveAndUploadPassportPhoto(
                                context = context,
                                sourceTempFile = tempFile,
                                customClientName = clientName,
                                isPrintSheet = isPrintSheet,
                                targetFolderId = passportDriveTarget.folderId,
                                targetFolderName = passportDriveTarget.folderName,
                                uploadToDrive = uploadToDrive,
                                onResult = onResult
                            )
                        }
                    )
                }
                AppScreenTab.APPROVALS -> {
                    val dbLogs by viewModel.dbLogs.collectAsStateWithLifecycle()
                    AdminDashboardScreen(
                        allUsers = allUsers,
                        onApprove = { email -> viewModel.approveUser(email) },
                        onDecline = { email -> viewModel.declineUser(email) },
                        onToggleApproval = { email, isApproved -> viewModel.toggleUserApproval(email, isApproved) },
                        onResetDevice = { email -> viewModel.revokeUserDevice(email) },
                        onDeleteUser = { email -> viewModel.deleteUser(email) },
                        onUpdateExpiry = { email, timestamp -> viewModel.updateUserExpiry(email, timestamp) },
                        onCreateUser = { email, role, status, expiry -> viewModel.createUserManually(email, role, status, expiry) },
                        statusMessage = statusMessage,
                        onClearStatus = { viewModel.clearStatusMessage() },
                        onRunDiagnostics = { viewModel.runDiagnostics() },
                        dbLogs = dbLogs,
                        onClearLogs = { viewModel.clearDbLogs() }
                    )
                }
                AppScreenTab.FILES -> {
                    if (googleEmail == null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Not Connected",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Google Drive Unlinked",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Please go to the SYNC tab and link your Google Account first to view live captures from Google Drive sections.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        // Search bar & Refresh action
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search Drive files & folders...") },
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search"
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Text(
                                                "Clear",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Red
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    cursorColor = MaterialTheme.colorScheme.primary
                                )
                            )

                            IconButton(
                                onClick = { viewModel.fetchDriveFiles(context) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh list",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (isDriveFilesLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "Loading captures from Google Drive sections...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else {
                            if (driveFilesError != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = "Error", tint = Color.Red)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Sync Error: $driveFilesError",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Red
                                        )
                                    }
                                }
                            }

                            val filteredDriveFiles = remember(driveFiles, searchQuery) {
                                driveFiles.filter { file ->
                                    file.name.contains(searchQuery, ignoreCase = true) ||
                                    (file.folderName ?: "").contains(searchQuery, ignoreCase = true) ||
                                    file.mimeType.contains(searchQuery, ignoreCase = true)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (searchQuery.isEmpty()) "Google Drive Files (${driveFiles.size})" else "Matches Found (${filteredDriveFiles.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "LIVE SYNCED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF4CAF50),
                                    modifier = Modifier
                                        .background(Color(0xFF4CAF50).copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp))
                                        .border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            if (googleEmail.isNullOrEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Default.Lock, contentDescription = "Sign In Required", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            text = "Please sign in to Google Drive (in the Sync tab) to view your remote synced files.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Button(onClick = { activeTab = AppScreenTab.SYNC }) {
                                            Text("Go to Sync Settings")
                                        }
                                    }
                                }
                            } else if (filteredDriveFiles.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isEmpty()) "No files found inside the sync directory or your document subfolder sections." else "No matching Drive files found.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(bottom = 90.dp, start = 16.dp, end = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    items(
                                        count = filteredDriveFiles.size,
                                        key = { index -> filteredDriveFiles[index].id }
                                    ) { index ->
                                        val file = filteredDriveFiles[index]
                                        DriveFileItem(file) {
                                            file.webViewLink?.let { link ->
                                                try {
                                                    val intent = android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(link)
                                                    )
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Cannot open link: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            } ?: Toast.makeText(context, "No web link available for this file", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                AppScreenTab.SYNC -> {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val email = googleEmail
                                if (email.isNullOrEmpty()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Sync Info",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Text(
                                            text = "Google Drive Sync",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "Back up and organize your scans and generated PDFs automatically within your Google Drive.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = {
                                            val signInIntent = googleSignInClient.signInIntent
                                            googleSignInLauncher.launch(signInIntent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Connect Google Drive", fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                        ) {
                                            Text(
                                                text = "Google Drive Sync ON",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Account: $email",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                googleSignInClient.signOut().addOnCompleteListener {
                                                    viewModel.setGoogleEmail(null)
                                                }
                                            }
                                        ) {
                                            Text("Disconnect", color = Color.Red, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                                        ) {
                                            Text(
                                                text = "DESTINATION FOLDER",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = driveFolderName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                activeFolderPickerState = ActiveFolderPickerState.Open(FolderPickerPurpose.DEFAULT_SYNC_FOLDER)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                        ) {
                                            Text("Change Folder", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Filename Pattern Setup",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "Portal Filename Setup",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Text(
                                    text = "Choose the naming sequence when preparing direct uploads for state/commercial portals.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.updateNameBeforeType(true) }
                                    ) {
                                        RadioButton(
                                            selected = nameBeforeType,
                                            onClick = { viewModel.updateNameBeforeType(true) }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Name_Type (e.g., Subhojit_PAN)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.updateNameBeforeType(false) }
                                    ) {
                                        RadioButton(
                                            selected = !nameBeforeType,
                                            onClick = { viewModel.updateNameBeforeType(false) }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Type_Name (e.g., PAN_Subhojit)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Backup Statistics",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Diagnostics Status", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = if (googleEmail != null) "SUCCESS" else "CONNECTED PENDING",
                                        fontWeight = FontWeight.Bold,
                                        color = if (googleEmail != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Connected Email", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = googleEmail ?: "None",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Active Folder ID", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = driveFolderId,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Local Data Defense & Wipe Panel
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Shield",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = "Professional Data Defense",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Text(
                                    text = "Cyber cafes routinely handle sensitive identity documents (Aadhaar, government IDs, passbooks). Modern portal uploads pose a massive privacy risk if left on shared machines.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Text(
                                        text = "Local Cache Isolation: PDFs are stored in private internal directories.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Text(
                                        text = "Secure Direct Pipe: Scans pipe straight into your Google Drive, bypassing public servers.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Danger",
                                        tint = Color.Red,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Secure Instant Cache Wipe",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        val kb = publicFolderSize / 1024.0
                                        val mb = kb / 1024.0
                                        val gb = mb / 1024.0
                                        val sizeText = when {
                                            gb >= 1.0 -> "${String.format("%.2f", gb)} GB"
                                            mb >= 1.0 -> "${String.format("%.2f", mb)} MB"
                                            else -> "${String.format("%.2f", kb)} KB"
                                        }
                                        Text(
                                            text = "Dasmo Scan folder size: $sizeText",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }

                                Text(
                                    text = "Wipe all scanned items, local caches, metadata caches, and Room state logs instantly from this machine.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )

                                Button(
                                    onClick = {
                                        activeOverlayModal = DashboardOverlayModal.ClearJunkWarning()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Secure Device Scan Cleansing", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                AppScreenTab.SETTINGS -> {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. App Version & In-App Updater Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SystemUpdate,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "App Version & Updates",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "v${BuildConfig.VERSION_NAME}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "Directly check GitHub Releases for newer features, security updates, and instant APK downloads.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                val isCheckingUpdate = updatePromptState is UpdatePromptState.Checking
                                Button(
                                    enabled = !isCheckingUpdate,
                                    onClick = {
                                        updatePromptState = UpdatePromptState.Checking
                                        scope.launch {
                                            try {
                                                val info = com.example.util.UpdateChecker.checkForUpdates(context)
                                                if (info.hasUpdate) {
                                                    updatePromptState = UpdatePromptState.Available(info)
                                                } else {
                                                    updatePromptState = UpdatePromptState.Idle
                                                    Toast.makeText(context, "You are using the latest version (v${info.currentVersion})", Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                updatePromptState = UpdatePromptState.Idle
                                                Toast.makeText(context, "Update check failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isCheckingUpdate) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Checking GitHub Releases...", fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Check for Updates Now", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // 2. Storage & Junk Cleaner Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CleaningServices,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "Storage & Junk Cleaner",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Cached scans, cropped images, and logs",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                val limitBytes = storageLimitMb.toLong() * 1024 * 1024L
                                val progress = (publicFolderSize.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
                                val isNearLimit = publicFolderSize > limitBytes * 0.8
                                val limitLabel = if (storageLimitMb >= 1000) "${storageLimitMb / 1000} GB" else "$storageLimitMb MB"
                                val sizeText = if (publicFolderSize == 0L) "0.00 KB" else {
                                    val kb = publicFolderSize / 1024.0
                                    val mb = kb / 1024.0
                                    val gb = mb / 1024.0
                                    when {
                                        gb >= 1.0 -> "${String.format("%.2f", gb)} GB"
                                        mb >= 1.0 -> "${String.format("%.2f", mb)} MB"
                                        else -> "${String.format("%.2f", kb)} KB"
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = "Occupied Storage: $sizeText",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Limit: $limitLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                // Custom Storage Alert Threshold Slider
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Storage Alert Limit",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Clean-up warnings trigger when storage reaches 80%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = limitLabel,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Slider(
                                        value = storageLimitMb.toFloat(),
                                        onValueChange = { viewModel.updateStorageLimitMb(it.toInt()) },
                                        valueRange = 50f..1000f,
                                        steps = 18,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("50 MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("250 MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("500 MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("1 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                OutlinedButton(
                                    onClick = { activeOverlayModal = DashboardOverlayModal.ClearJunkWarning() },
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Clean App Junk & Cache", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // 3. Scan & Processing Presets Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Text(
                                        text = "Scanning & Compression Presets",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // Smart Text Enhancement
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Smart Text Auto-Enhance", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Automatic contrast sharpening and shadow removal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = autoEnhanceEnabled,
                                        onCheckedChange = { viewModel.updateAutoEnhanceEnabled(it) }
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Standard A4 Format
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("ID Card A4 Xerox Page Canvas", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Place Front & Back ID cards together on upper A4 page (Cyber Cafe Xerox standard)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = useA4Format,
                                        onCheckedChange = { viewModel.updateUseA4Format(it) }
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // AI Auto-Name Analysis
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("AI Auto-Name Extraction", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Auto-detect person name and document type from scan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = enableAiAnalysis,
                                        onCheckedChange = { viewModel.updateEnableAiAnalysis(it) }
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Target Size Selector
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Target Compressed Size", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Current: ${targetSizeKb} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { activeOverlayModal = DashboardOverlayModal.SizeDialog(targetSizeKb.toString()) },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text("Change Size")
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Output Format (JPEG / PDF / BOTH)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Default Output Format", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Controls whether output is saved as PDF, JPEG, or both", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("JPEG", "PDF", "BOTH").forEach { format ->
                                            val isSelected = imageFormat.equals(format, ignoreCase = true)
                                            Button(
                                                onClick = { viewModel.updateImageFormat(format) },
                                                modifier = Modifier.weight(1f).height(40.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = if (isSelected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                                         else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                            ) {
                                                Text(format, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Compression Codec (WEBP / JPEG)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Compression Codec", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("WEBP preserves sharper text & edges to reach target KB. JPEG is standard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("WEBP", "JPEG").forEach { codec ->
                                            val isSelected = compressionCodec.equals(codec, ignoreCase = true)
                                            Button(
                                                onClick = { viewModel.updateCompressionCodec(codec) },
                                                modifier = Modifier.weight(1f).height(40.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = if (isSelected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                                         else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                            ) {
                                                Text(codec, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                // Batch Scan Grouping
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("Batch Pages Per Document", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text("Number of pages merged into each PDF/file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.clickable {
                                                activeOverlayModal = DashboardOverlayModal.BatchPagesSetting(batchPagesPerDoc.toString())
                                            }
                                        ) {
                                            Text(
                                                text = if (batchPagesPerDoc == 1) "1 Page (Single)" else if (batchPagesPerDoc == 2) "2 Pages (Pair)" else "$batchPagesPerDoc Pages",
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(1 to "1 (Single)", 2 to "2 (Pair)", 3 to "3", 4 to "4").forEach { (count, label) ->
                                            val isSelected = batchPagesPerDoc == count
                                            Button(
                                                onClick = { viewModel.updateBatchPagesPerDoc(count) },
                                                modifier = Modifier.weight(1f).height(40.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = if (isSelected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                                         else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                                contentPadding = PaddingValues(horizontal = 2.dp)
                                            ) {
                                                Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp)
                                            }
                                        }

                                        val isCustom = batchPagesPerDoc > 4
                                        Button(
                                            onClick = {
                                                activeOverlayModal = DashboardOverlayModal.BatchPagesSetting(batchPagesPerDoc.toString())
                                            },
                                            modifier = Modifier.weight(1.3f).height(40.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = if (isCustom) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                                     else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(
                                                text = if (isCustom) "Custom ($batchPagesPerDoc)" else "Custom...",
                                                fontWeight = if (isCustom) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Google Account & Device Info Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Text(
                                        text = "Account & Device Binding",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Google Account: ${googleEmail ?: "Not Connected"}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                        Text("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Device ID: $deviceId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Drive Target: $driveFolderName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                if (!googleEmail.isNullOrBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            googleSignInClient.signOut().addOnCompleteListener {
                                                viewModel.setGoogleEmail(null)
                                                viewModel.clearDbLogs()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(imageVector = Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sign Out & Disconnect", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val activeSizeDialog = activeOverlayModal as? DashboardOverlayModal.SizeDialog
    if (activeSizeDialog != null) {
        val parsedRaw = activeSizeDialog.sizeInput.toIntOrNull()
        AlertDialog(
            onDismissRequest = { activeOverlayModal = DashboardOverlayModal.None },
            title = { Text("Set Target Output Size") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = activeSizeDialog.sizeInput,
                        onValueChange = { input ->
                            val digits = input.filter { char -> char.isDigit() }.take(5)
                            activeOverlayModal = activeSizeDialog.copy(sizeInput = digits)
                        },
                        label = { Text("Size in KB (10 - 10,000)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    if (parsedRaw == null || parsedRaw !in 10..10000) {
                        Text(
                            text = "Values outside 10–10,000 KB will be clamped automatically.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = parsedRaw != null && parsedRaw > 0,
                    onClick = {
                        parsedRaw?.let { size ->
                            viewModel.updateTargetSize(size.coerceIn(10, 10000))
                        }
                        activeOverlayModal = DashboardOverlayModal.None
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeOverlayModal = DashboardOverlayModal.None }) {
                    Text("Cancel")
                }
            }
        )
    }

    val activeTargetSizeWarning = scanFlowModalState as? ScanFlowModalState.TargetSizeWarning
    if (activeTargetSizeWarning != null) {
        val pendingAction = activeTargetSizeWarning.pendingAction
        var sizeInput by remember(activeTargetSizeWarning) { mutableStateOf(targetSizeKb.toString()) }
        AlertDialog(
            onDismissRequest = {
                scanFlowModalState = ScanFlowModalState.None
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Verify Target Size",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "You are scanning into the subfolder \"${targetSubfolder}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "However, the target file size is still set to ${targetSizeKb} KB (from the previous subfolder \"${subfolderAtLastSizeChange ?: ""}\").",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "If you want to edit the target size for this subfolder, change it below:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sizeInput,
                        onValueChange = { sizeInput = it.filter { char -> char.isDigit() }.take(5) },
                        label = { Text("Target Size (KB)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            viewModel.setSubfolderAtLastSizeChange(targetSubfolder)
                            scanFlowModalState = ScanFlowModalState.None
                            executeScanAction(pendingAction)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Keep ${targetSizeKb}KB")
                    }
                    Button(
                        onClick = {
                            val clampedSize = (sizeInput.toIntOrNull() ?: targetSizeKb).coerceIn(10, 10000)
                            viewModel.updateTargetSize(clampedSize)
                            scanFlowModalState = ScanFlowModalState.None
                            executeScanAction(pendingAction)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save & Scan")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scanFlowModalState = ScanFlowModalState.None
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    val activeMergeModal = activeOverlayModal as? DashboardOverlayModal.MergeToPdf
    if (activeMergeModal != null) {
        val currentMergeDialog = activeMergeModal.mergeState
        AlertDialog(
            onDismissRequest = { activeOverlayModal = DashboardOverlayModal.None },
            title = {
                Text(
                    text = "Merge to PDF",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "Merge ${selectedDocIds.size} document(s) into a single PDF.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = currentMergeDialog.fileName,
                        onValueChange = {
                            activeOverlayModal = activeMergeModal.copy(
                                mergeState = currentMergeDialog.copy(fileName = it)
                            )
                        },
                        label = { Text("File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = currentMergeDialog.targetSizeKb,
                        onValueChange = { input ->
                            activeOverlayModal = activeMergeModal.copy(
                                mergeState = currentMergeDialog.copy(
                                    targetSizeKb = input.filter { it.isDigit() }.take(5)
                                )
                            )
                        },
                        label = { Text("Target Size (KB)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().clickable {
                            activeFolderPickerState = ActiveFolderPickerState.Open(FolderPickerPurpose.MERGE_DESTINATION_FOLDER)
                        }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Folder, contentDescription = "Folder", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (currentMergeDialog.folderId != null) currentMergeDialog.folderName else "Select Drive Folder",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (currentMergeDialog.folderId != null) "Folder Selected" else "Tap to choose destination",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val finalSize = (currentMergeDialog.targetSizeKb.toIntOrNull() ?: targetSizeKb).coerceIn(10, 10000)
                    viewModel.mergeDocumentsToPdf(
                        context = context,
                        docIds = selectedDocIds,
                        fileName = if (currentMergeDialog.fileName.isNotBlank()) currentMergeDialog.fileName else "Merged_${System.currentTimeMillis()}",
                        folderId = currentMergeDialog.folderId,
                        folderName = currentMergeDialog.folderName,
                        targetSizeKb = finalSize
                    )
                    activeOverlayModal = DashboardOverlayModal.None
                    selectedDocIds = setOf()
                }) {
                    Text("Merge")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeOverlayModal = DashboardOverlayModal.None }) {
                    Text("Cancel")
                }
            }
        )
    }

    val activeReviewSession = multiScanSessionState as? MultiScanSessionState.ReviewingPages
    if (activeReviewSession != null) {
        val targetEditDocId = activeReviewSession.targetEditDocId
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                // Do not dismiss on click outside to prevent accidental loss
            },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Multi-Scan Option",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = if (targetEditDocId != null) "Replace Document Pages" else "Multi-Page Scan",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "You have scanned ${accumulatedPageUris.size} page(s). You can add more pages or finish and process them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    ) {
                        items(accumulatedPageUris.size) { index ->
                            val uri = accumulatedPageUris[index]
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(model = uri),
                                    contentDescription = "Page ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Page ${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .clickable {
                                            accumulatedPageUris.removeAt(index)
                                            if (accumulatedPageUris.isEmpty()) {
                                                multiScanSessionState = MultiScanSessionState.Idle
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove page",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Button(
                            onClick = {
                                launchAdditionalScan()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                                Text("Scan More Pages", fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = {
                                multiScanSessionState = MultiScanSessionState.Idle
                                if (targetEditDocId != null) {
                                    val scannedReplacementUris = accumulatedPageUris.toList()
                                    accumulatedPageUris.clear()
                                    val currentEditModal = activeOverlayModal as? DashboardOverlayModal.DocumentEdit
                                    if (currentEditModal != null && currentEditModal.editState.doc.id == targetEditDocId) {
                                        activeOverlayModal = currentEditModal.copy(
                                            editState = currentEditModal.editState.copy(replacementPageUris = scannedReplacementUris)
                                        )
                                    }
                                } else {
                                    val uris = accumulatedPageUris.toList()
                                    val overrideFormat = if (uris.size > 1) UploadFormat.PDF else null
                                    viewModel.processMultiScannedImages(uris, overrideFormat)
                                    accumulatedPageUris.clear()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Done")
                                Text("Finish & Merge PDF", fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                accumulatedPageUris.clear()
                                multiScanSessionState = MultiScanSessionState.Idle
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Discard All", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    val activeBatchSettingModal = activeOverlayModal as? DashboardOverlayModal.BatchPagesSetting
    if (activeBatchSettingModal != null) {
        val tempInput = activeBatchSettingModal.pagesInput
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { activeOverlayModal = DashboardOverlayModal.None },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text("Batch Pages Per File", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Default grouping preference", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { activeOverlayModal = DashboardOverlayModal.None }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Text(
                        text = "Enter how many pages to combine into each PDF/Document in Batch Mode (e.g. 5, 10, 20...):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Quick presets chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 2, 3, 5, 10, 20).forEach { presetNum ->
                            val isChosen = tempInput == presetNum.toString()
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        activeOverlayModal = activeBatchSettingModal.copy(pagesInput = presetNum.toString())
                                    }
                            ) {
                                Text(
                                    text = "$presetNum",
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isChosen) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (isChosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // Stepper & Direct TextField
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val current = tempInput.toIntOrNull() ?: 2
                                if (current > 1) {
                                    activeOverlayModal = activeBatchSettingModal.copy(pagesInput = (current - 1).toString())
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        ) {
                            Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedTextField(
                            value = tempInput,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() } && input.length <= 3) {
                                    activeOverlayModal = activeBatchSettingModal.copy(pagesInput = input)
                                }
                            },
                            label = { Text("Pages Per File") },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = {
                                val current = tempInput.toIntOrNull() ?: 2
                                if (current < 100) {
                                    activeOverlayModal = activeBatchSettingModal.copy(pagesInput = (current + 1).toString())
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        ) {
                            Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { activeOverlayModal = DashboardOverlayModal.None },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                val num = tempInput.toIntOrNull()?.coerceIn(1, 100) ?: 2
                                viewModel.updateBatchPagesPerDoc(num)
                                activeOverlayModal = DashboardOverlayModal.None
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(46.dp)
                        ) {
                            Text("Save Default", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (scanFlowModalState == ScanFlowModalState.BatchSetup) {
        var pageSelection by remember(batchPagesPerDoc) {
            mutableStateOf<BatchPageSelection>(
                if (batchPagesPerDoc in 1..4) BatchPageSelection.Preset(batchPagesPerDoc)
                else BatchPageSelection.Custom(batchPagesPerDoc.coerceIn(1, 100).toString())
            )
        }
        var rememberAsDefault by remember { mutableStateOf(true) }

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { scanFlowModalState = ScanFlowModalState.None },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text("Batch Scan Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                                Text("Pages per PDF/document", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(
                            onClick = { scanFlowModalState = ScanFlowModalState.None },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Options List
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            1 to ("1 Page (Single Document)" to "Every single scan is saved as an individual file"),
                            2 to ("2 Pages (Pair / ID Card)" to "Front & Back sides combined onto 1 document / A4 sheet"),
                            3 to ("3 Pages" to "3 pages merged per document"),
                            4 to ("4 Pages" to "4 pages merged per document")
                        ).forEach { (count, info) ->
                            val isSelected = (pageSelection as? BatchPageSelection.Preset)?.count == count
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pageSelection = BatchPageSelection.Preset(count)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            pageSelection = BatchPageSelection.Preset(count)
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(info.first, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text(info.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        // Custom Option Card
                        val customSelection = pageSelection as? BatchPageSelection.Custom
                        val isCustomSelected = customSelection != null
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCustomSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = if (isCustomSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (customSelection == null) {
                                        pageSelection = BatchPageSelection.Custom("5")
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    RadioButton(
                                        selected = isCustomSelected,
                                        onClick = {
                                            if (customSelection == null) {
                                                pageSelection = BatchPageSelection.Custom("5")
                                            }
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Custom Pages (5, 10, 20...)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text("Enter custom number of pages per file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                if (customSelection != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(5, 10, 15, 20).forEach { presetNum ->
                                            val isPillActive = customSelection.inputText == presetNum.toString()
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isPillActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        pageSelection = BatchPageSelection.Custom(presetNum.toString())
                                                    }
                                            ) {
                                                Text(
                                                    text = "$presetNum",
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (isPillActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                                                    color = if (isPillActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val curr = customSelection.inputText.toIntOrNull() ?: 5
                                                if (curr > 1) {
                                                    pageSelection = BatchPageSelection.Custom((curr - 1).toString())
                                                }
                                            },
                                            modifier = Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                        ) {
                                            Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedTextField(
                                            value = customSelection.inputText,
                                            onValueChange = { input ->
                                                if (input.all { it.isDigit() } && input.length <= 3) {
                                                    pageSelection = BatchPageSelection.Custom(input)
                                                }
                                            },
                                            label = { Text("Pages / Doc") },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )

                                        IconButton(
                                            onClick = {
                                                val curr = customSelection.inputText.toIntOrNull() ?: 5
                                                if (curr < 100) {
                                                    pageSelection = BatchPageSelection.Custom((curr + 1).toString())
                                                }
                                            },
                                            modifier = Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                        ) {
                                            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Remember default checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberAsDefault = !rememberAsDefault },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = rememberAsDefault,
                            onCheckedChange = { rememberAsDefault = it },
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Save as default batch preference", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Action Buttons (Cleanly stacked, 0 overlap)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Button(
                            onClick = {
                                val finalPages = pageSelection.resolvedCount(fallback = batchPagesPerDoc)
                                sessionBatchPagesPerDoc = finalPages
                                if (rememberAsDefault) {
                                    viewModel.updateBatchPagesPerDoc(finalPages)
                                }
                                launchContinuousBatchCamera(finalPages)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Start Camera Batch (Continuous)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val finalPages = pageSelection.resolvedCount(fallback = batchPagesPerDoc)
                                sessionBatchPagesPerDoc = finalPages
                                if (rememberAsDefault) {
                                    viewModel.updateBatchPagesPerDoc(finalPages)
                                }
                                scanFlowModalState = ScanFlowModalState.None
                                launchBatchScan()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Use System Document Scanner", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    val activeBatchCameraModal = scanFlowModalState as? ScanFlowModalState.ContinuousBatchCamera
    if (activeBatchCameraModal != null) {
        com.example.ui.BatchCameraScanScreen(
            onDismiss = { scanFlowModalState = ScanFlowModalState.None },
            initialPagesPerDoc = activeBatchCameraModal.initialPagesPerDoc,
            onFinishBatch = { uris, pagesPerDoc ->
                scanFlowModalState = ScanFlowModalState.None
                viewModel.processBatchScannedImages(uris, pagesPerDoc)
            }
        )
    }

    if (batchVerificationGroups != null) {
        BatchVerificationDialog(
            groups = batchVerificationGroups!!,
            onConfirm = { updatedGroups ->
                viewModel.confirmBatchVerification(updatedGroups)
            },
            onDismiss = {
                viewModel.dismissBatchVerification()
            }
        )
    }

    if (pendingDoc != null) {
        val doc = pendingDoc!!
        var tempPersonName by remember(doc) { mutableStateOf(doc.initialPersonName) }
        var tempDocumentType by remember(doc) { mutableStateOf(doc.initialDocumentType) }
        var tempUploadFormat by remember(doc) { mutableStateOf(doc.initialFormat) }
        var tempTargetSize by remember(doc) { mutableStateOf(targetSizeKb.toString()) }

        val presets = remember { listOf("Aadhaar Card", "PAN Card", "Voter ID", "Passport", "Driving License", "Marksheet", "Ration Card") }

        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingDocument(tempUploadFormat) },
            title = {
                Text(
                    text = "Confirm Document Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                val configuration = LocalConfiguration.current
                val maxDialogHeight = (configuration.screenHeightDp * 0.62f).dp
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxDialogHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "Dasmo AI analyzed the document content. Adjust the details to fit your governmental portal requirements.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    var isPreviewExpanded by remember { mutableStateOf(false) }
                    val compressedSizeKbLabel = remember(doc.compressedFile) {
                        val kb = doc.compressedFile.length() / 1024f
                        String.format(Locale.US, "%.1f KB", kb)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isPreviewExpanded) 260.dp else 118.dp)
                            .clickable { isPreviewExpanded = !isPreviewExpanded },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Image(
                                painter = rememberAsyncImagePainter(model = doc.compressedFile),
                                contentDescription = "Analysis Preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = if (isPreviewExpanded) ContentScale.Fit else ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "Optimized: $compressedSizeKbLabel (${doc.pageFiles?.size ?: 1} pg)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E676)
                                )
                            }
                            if (!isPreviewExpanded) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(10.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        "Tap to expand crop",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = tempPersonName,
                        onValueChange = { tempPersonName = it },
                        label = { Text("Person's Name") },
                        singleLine = true,
                        placeholder = { Text("e.g. Subhojit") },
                        trailingIcon = {
                            if (tempPersonName.isNotEmpty()) {
                                IconButton(onClick = { tempPersonName = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear Input",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            tempPersonName = "Client_${System.currentTimeMillis() % 100000}"
                                        }
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "Auto Name",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    if (tempPersonName.trim().isEmpty()) {
                        Text(
                            text = "⚠ Blank will auto-generate: Client_xxxxx",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    OutlinedTextField(
                        value = tempDocumentType,
                        onValueChange = { tempDocumentType = it },
                        label = { Text("Document Type") },
                        singleLine = true,
                        placeholder = { Text("e.g. Aadhaar Card") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Text(
                        text = "Upload Format",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { tempUploadFormat = UploadFormat.JPEG }
                                .padding(horizontal = 8.dp)
                        ) {
                            RadioButton(
                                selected = tempUploadFormat == UploadFormat.JPEG,
                                onClick = { tempUploadFormat = UploadFormat.JPEG }
                            )
                            Text("JPEG", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { tempUploadFormat = UploadFormat.PDF }
                                .padding(horizontal = 8.dp)
                        ) {
                            RadioButton(
                                selected = tempUploadFormat == UploadFormat.PDF,
                                onClick = { tempUploadFormat = UploadFormat.PDF }
                            )
                            Text("PDF", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { tempUploadFormat = UploadFormat.BOTH }
                                .padding(horizontal = 8.dp)
                        ) {
                            RadioButton(
                                selected = tempUploadFormat == UploadFormat.BOTH,
                                onClick = { tempUploadFormat = UploadFormat.BOTH }
                            )
                            Text("Both", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Target Output Size Control
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Target Output Size",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val displaySize = tempTargetSize.toIntOrNull() ?: targetSizeKb
                        Text(
                            text = "Limit: ${displaySize} KB",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    OutlinedTextField(
                        value = tempTargetSize,
                        onValueChange = { input ->
                            tempTargetSize = input.filter { it.isDigit() }.take(5)
                        },
                        label = { Text("Target Size (KB)") },
                        placeholder = { Text("e.g. 90") },
                        trailingIcon = {
                            Text(
                                "KB",
                                modifier = Modifier.padding(end = 12.dp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    val sizePresets = remember { listOf(50, 90, 100, 200, 500, 2000) }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(sizePresets.size) { index ->
                            val presetSize = sizePresets[index]
                            val isSelected = tempTargetSize == presetSize.toString()
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.clickable { tempTargetSize = presetSize.toString() }
                            ) {
                                Text(
                                    text = "${presetSize} KB",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Quick category selectors
                    Text(
                        text = "Quick Document Presets",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(presets.size) { index ->
                            val preset = presets[index]
                            val isSelected = tempDocumentType.equals(preset, ignoreCase = true)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.clickable { tempDocumentType = preset }
                            ) {
                                Text(
                                    text = preset,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Proposed name preview
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "PORTAL FILENAME PREVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
							)
                            Spacer(modifier = Modifier.height(2.dp))
                            val displayPersonName = tempPersonName.trim().ifEmpty { "Client_xxxxx" }
                            val displayDocumentType = tempDocumentType.trim().ifEmpty { "Document" }
                            val displayBaseName = if (nameBeforeType) {
                                "${displayPersonName.replace(" ", "_")}_${displayDocumentType.replace(" ", "_")}"
                            } else {
                                "${displayDocumentType.replace(" ", "_")}_${displayPersonName.replace(" ", "_")}"
                            }
                            val fileExt = when (tempUploadFormat) {
                                UploadFormat.PDF -> ".pdf"
                                UploadFormat.JPEG -> ".jpeg"
                                UploadFormat.BOTH -> ".jpeg + .pdf"
                            }
                            val parsedSize = tempTargetSize.toIntOrNull()?.coerceIn(10, 10000) ?: targetSizeKb
                            Text(
                                "$displayBaseName$fileExt (Max: ${parsedSize} KB)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                val finalTargetSize = tempTargetSize.toIntOrNull()?.coerceIn(10, 10000) ?: targetSizeKb
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            viewModel.uploadInBackground(context, tempPersonName, tempDocumentType, tempUploadFormat, finalTargetSize)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Upload in Background", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            viewModel.confirmAndUpload(context, tempPersonName, tempDocumentType, tempUploadFormat, finalTargetSize)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Sync Directly", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelPendingDocument(tempUploadFormat) }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val activeClearJunkModal = activeOverlayModal as? DashboardOverlayModal.ClearJunkWarning
    if (activeClearJunkModal != null) {
        AlertDialog(
            onDismissRequest = { activeOverlayModal = DashboardOverlayModal.None },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear All App Junk?", color = MaterialTheme.colorScheme.error)
                }
            },
            text = {
                Column {
                    Text(
                        "This action will permanently delete all cached images, locally processed PDFs, and database entries inside the app's internal storage.\n\n" +
                        "Are you absolutely sure?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                activeOverlayModal = activeClearJunkModal.copy(
                                    alsoDeletePublicFiles = !activeClearJunkModal.alsoDeletePublicFiles
                                )
                            }
                            .padding(top = 8.dp)
                    ) {
                        Checkbox(
                            checked = activeClearJunkModal.alsoDeletePublicFiles,
                            onCheckedChange = { checked ->
                                activeOverlayModal = activeClearJunkModal.copy(alsoDeletePublicFiles = checked)
                            }
                        )
                        Text(
                            "Also delete exported files in Device's Documents/Dasmo Scan folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllData(activeClearJunkModal.alsoDeletePublicFiles)
                        activeOverlayModal = DashboardOverlayModal.None
                        Toast.makeText(context, "App junk cleared successfully", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Yes, Clear Junk", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { activeOverlayModal = DashboardOverlayModal.None }) {
                    Text("Cancel")
                }
            }
        )
    }

    val openFolderPicker = activeFolderPickerState as? ActiveFolderPickerState.Open
    if (openFolderPicker != null) {
        val currentFolderPickerPurpose = openFolderPicker.purpose
        val expandedNewFolder = openFolderPicker.newFolderInput as? NewFolderInputState.Expanded
        val folderSearchQuery = openFolderPicker.searchQuery
        val isSignedInToDrive = !googleEmail.isNullOrBlank()

        LaunchedEffect(openFolderPicker.purpose) {
            viewModel.clearFolderError()
        }

        LaunchedEffect(currentFolderId, googleEmail) {
            if (googleEmail != null) {
                viewModel.fetchSubfolders(context, currentFolderId)
            }
        }

        AlertDialog(
            onDismissRequest = { activeFolderPickerState = ActiveFolderPickerState.Closed },
            title = {
                Text(
                    text = when (currentFolderPickerPurpose) {
                        FolderPickerPurpose.PASSPORT_DESTINATION_FOLDER -> "Select Passport Drive Folder"
                        FolderPickerPurpose.MERGE_DESTINATION_FOLDER -> "Select Merged PDF Folder"
                        FolderPickerPurpose.DEFAULT_SYNC_FOLDER -> "Select Drive Destination"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (currentFolderPickerPurpose) {
                            FolderPickerPurpose.PASSPORT_DESTINATION_FOLDER -> "Choose or create the Google Drive folder where your Passport photos and 4×6 print sheets will be uploaded."
                            FolderPickerPurpose.MERGE_DESTINATION_FOLDER -> "Choose or create the Google Drive folder for your merged PDF document."
                            FolderPickerPurpose.DEFAULT_SYNC_FOLDER -> "Decide where to store your PDFs and scanned images. Navigate, click to enter subfolders, or create new ones."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!isSignedInToDrive) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Sign in with your Google Account first to browse or select Google Drive folders.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    val activeDiagnostic = folderDiagnostic
                    if (activeDiagnostic != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (activeDiagnostic.isSuccess)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (activeDiagnostic.isSuccess) "✅ Drive Diagnostic Passed:" else "⚠️ Drive Diagnostic Failed:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (activeDiagnostic.isSuccess)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = activeDiagnostic.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (activeDiagnostic.isSuccess)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    if (folderError != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "⚠️ Google API Notice / Error:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = folderError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Breadcrumb Display
                    val crumbText = folderPathStack.joinToString(" / ") { it.name }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📂 $crumbText",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Go back button
                        val canGoUp = folderPathStack.size > 1 && isSignedInToDrive
                        Button(
                            onClick = {
                                viewModel.navigateUp(context)
                            },
                            enabled = canGoUp,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.outlineVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("← Go Up", fontWeight = FontWeight.Bold)
                        }

                        // Create folder button
                        Button(
                            onClick = {
                                activeFolderPickerState = openFolderPicker.copy(
                                    newFolderInput = if (expandedNewFolder != null)
                                        NewFolderInputState.Collapsed
                                    else
                                        NewFolderInputState.Expanded()
                                )
                            },
                            enabled = isSignedInToDrive,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+ New Folder", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (expandedNewFolder != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = expandedNewFolder.name,
                                onValueChange = { nextName ->
                                    activeFolderPickerState = openFolderPicker.copy(
                                        newFolderInput = expandedNewFolder.copy(name = nextName)
                                    )
                                },
                                placeholder = { Text("Folder Name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    cursorColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Button(
                                enabled = expandedNewFolder.name.isNotBlank() && isSignedInToDrive,
                                onClick = {
                                    if (expandedNewFolder.name.isNotBlank()) {
                                        viewModel.createNewSubfolder(context, expandedNewFolder.name) { success ->
                                            if (success) {
                                                (activeFolderPickerState as? ActiveFolderPickerState.Open)?.let { latestPicker ->
                                                    activeFolderPickerState = latestPicker.copy(
                                                        newFolderInput = NewFolderInputState.Collapsed
                                                    )
                                                }
                                            } else {
                                                Toast.makeText(context, folderError ?: "Failed to create folder", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Create")
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Folders list
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Folders Inside Directory:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Real-time folder search bar
                    OutlinedTextField(
                        value = folderSearchQuery,
                        onValueChange = { query ->
                            activeFolderPickerState = openFolderPicker.copy(searchQuery = query)
                        },
                        placeholder = { Text("Filter/Search folders...", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Folder Search Icon",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (folderSearchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    activeFolderPickerState = openFolderPicker.copy(searchQuery = "")
                                }) {
                                    Text(
                                        text = "Clear",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Red
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    val filteredSubfolders = subfolders.filter { f ->
                        f.name.contains(folderSearchQuery, ignoreCase = true)
                    }

                    if (isFolderLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (filteredSubfolders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (folderSearchQuery.isEmpty()) "No subfolders found here" else "No matching subfolders",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            items(filteredSubfolders.size) { index ->
                                val f = filteredSubfolders[index]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.navigateToFolder(context, f.id, f.name)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📁  ${f.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = isSignedInToDrive,
                    onClick = {
                        when (currentFolderPickerPurpose) {
                            FolderPickerPurpose.MERGE_DESTINATION_FOLDER -> {
                                (activeOverlayModal as? DashboardOverlayModal.MergeToPdf)?.let { mergeModal ->
                                    activeOverlayModal = mergeModal.copy(
                                        mergeState = mergeModal.mergeState.copy(
                                            folderId = currentFolderId,
                                            folderName = currentFolderName ?: "Selected Folder"
                                        )
                                    )
                                }
                            }
                            FolderPickerPurpose.PASSPORT_DESTINATION_FOLDER -> {
                                passportDriveSelection = PassportDriveSelection.CustomFolder(
                                    folderId = currentFolderId,
                                    folderName = currentFolderName?.ifBlank { "My Drive" } ?: "My Drive"
                                )
                            }
                            FolderPickerPurpose.DEFAULT_SYNC_FOLDER -> {
                                viewModel.selectCurrentAsDestination()
                            }
                        }
                        activeFolderPickerState = ActiveFolderPickerState.Closed
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Select This Folder")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { activeFolderPickerState = ActiveFolderPickerState.Closed }
                ) {
                    Text("Close")
                }
            }
        )
    }

    val activePreviewModal = activeOverlayModal as? DashboardOverlayModal.DocumentPreview
    if (activePreviewModal != null) {
        val doc = activePreviewModal.doc
        val formattedPreviewDate = remember(doc.timestamp) {
            SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(doc.timestamp))
        }
        AlertDialog(
            onDismissRequest = { activeOverlayModal = DashboardOverlayModal.None },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Document Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = {
                            viewModel.deleteSelectedDocuments(setOf(doc.id))
                            activeOverlayModal = DashboardOverlayModal.None
                            Toast.makeText(context, "Deleted ${doc.fileName}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Document",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DocumentThumbnail(
                        localFilePath = doc.localFilePath,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "File Path: ${doc.localFilePath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Person Name", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(doc.personName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Doc Type", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(doc.documentType, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("File Name", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(doc.fileName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Scanned On", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(
                            formattedPreviewDate,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Drive Sync Status", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(
                            if (doc.isUploaded) "Synced Successfully" else "Pending Sync / Local Only",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (doc.isUploaded) Color(0xFF4CAF50) else Color(0xFFE91E63)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { activeOverlayModal = DashboardOverlayModal.None },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Close")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        activeOverlayModal = DashboardOverlayModal.DocumentEdit(
                            DocumentEditDialogState(
                                doc = doc,
                                personName = doc.personName,
                                docType = doc.documentType,
                                format = if (doc.fileName.endsWith(".pdf", ignoreCase = true)) com.example.ui.UploadFormat.PDF else com.example.ui.UploadFormat.JPEG,
                                replacementPageUris = null
                            )
                        )
                        multiScanSessionState = MultiScanSessionState.Idle
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                        Text("Edit / Replace")
                    }
                }
            }
        )
    }

    val activeEditModal = activeOverlayModal as? DashboardOverlayModal.DocumentEdit
    if (activeEditModal != null) {
        val currentEditDialog = activeEditModal.editState
        val doc = currentEditDialog.doc
        AlertDialog(
            onDismissRequest = { activeOverlayModal = DashboardOverlayModal.None },
            title = {
                Text(
                    text = "Edit Document & Replace File",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = currentEditDialog.personName,
                        onValueChange = {
                            activeOverlayModal = activeEditModal.copy(
                                editState = currentEditDialog.copy(personName = it)
                            )
                        },
                        label = { Text("Person Name") },
                        placeholder = { Text("e.g., John Doe") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = currentEditDialog.docType,
                        onValueChange = {
                            activeOverlayModal = activeEditModal.copy(
                                editState = currentEditDialog.copy(docType = it)
                            )
                        },
                        label = { Text("Document Type") },
                        placeholder = { Text("e.g., Passport, Invoice") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Upload Format",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        com.example.ui.UploadFormat.values().forEach { format ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (currentEditDialog.format == format) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable {
                                        activeOverlayModal = activeEditModal.copy(
                                            editState = currentEditDialog.copy(format = format)
                                        )
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                RadioButton(
                                    selected = (currentEditDialog.format == format),
                                    onClick = {
                                        activeOverlayModal = activeEditModal.copy(
                                            editState = currentEditDialog.copy(format = format)
                                        )
                                    }
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = format.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentEditDialog.format == format) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (currentEditDialog.replacementPageUris == null) {
                        Button(
                            onClick = { launchEditScan(doc.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = "Replace Pages",
                                    modifier = Modifier.size(20.dp)
                                )
                                Text("Re-scan / Replace Pages", fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "📄 ${currentEditDialog.replacementPageUris.size} new page(s) scanned to replace file!",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Button(
                                onClick = { launchEditScan(doc.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text("Re-scan Again", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateAndUploadDocument(
                            context = context,
                            docId = doc.id,
                            personName = currentEditDialog.personName,
                            documentType = currentEditDialog.docType,
                            format = currentEditDialog.format,
                            newPageUris = currentEditDialog.replacementPageUris
                        )
                        activeOverlayModal = DashboardOverlayModal.None
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Save & Sync", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { activeOverlayModal = DashboardOverlayModal.None },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun DocumentThumbnail(
    localFilePath: String,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedFile by androidx.compose.runtime.produceState<java.io.File?>(initialValue = null, key1 = localFilePath) {
        value = com.example.data.ImageProcessor.getOrCreateThumbnailFile(context, localFilePath)
    }
    val isPdf = remember(localFilePath) { localFilePath.endsWith(".pdf", ignoreCase = true) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (resolvedFile != null) {
            Image(
                painter = rememberAsyncImagePainter(model = resolvedFile),
                contentDescription = "Document Thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
            if (isPdf) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0xFFD32F2F).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "PDF",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }
        } else {
            Icon(
                imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                contentDescription = "Document Icon",
                tint = if (isPdf) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DocumentItem(doc: DocumentEntity, isSelected: Boolean = false, onLongClick: () -> Unit = {}, onClick: () -> Unit, onRetryClick: (() -> Unit)? = null) {
    val formattedDate = remember(doc.timestamp) {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(doc.timestamp))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Checkbox(
                    checked = true,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            DocumentThumbnail(
                localFilePath = doc.localFilePath,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${doc.personName}'s ${doc.documentType}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = doc.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (doc.drivePath != null) {
                    Text(
                        text = "☁️ Drive: ${doc.drivePath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF4CAF50)
                    )
                } else if (doc.isUploaded) {
                    Text(
                        text = "☁️ Drive: Synced",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF4CAF50)
                    )
                } else {
                    Text(
                        text = "📱 Saved Locally (Pending Sync)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFB300)
                    )
                }
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (doc.isUploaded) {
                Icon(
                    Icons.Default.CheckCircle, 
                    contentDescription = "Uploaded", 
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
            } else if (onRetryClick != null) {
                IconButton(onClick = onRetryClick) {
                    Icon(
                        Icons.Default.CloudUpload, 
                        contentDescription = "Upload Failed - Retry", 
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else {
                Icon(
                    Icons.Default.Warning, 
                    contentDescription = "Pending Upload", 
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun DriveFileItem(file: com.example.network.DriveFile, onOpenClick: () -> Unit) {
    val isPdf = remember(file.mimeType) { file.mimeType.contains("pdf", ignoreCase = true) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ClayIconOrb(
                icon = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                accentColor = if (isPdf) Color(0xFFFF5252) else Color(0xFF00C853),
                orbSize = 46.dp,
                iconSize = 22.dp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = "Folder Section: ${file.folderName ?: "My Drive"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sizeKb = file.size / 1024
                    Text(
                        text = "${sizeKb} KB",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (file.mimeType.contains("/")) file.mimeType.substringAfter("/").uppercase() else "FILE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onOpenClick,
                modifier = Modifier
                    .size(38.dp)
                    .claySurface(
                        baseColor = MaterialTheme.colorScheme.surfaceVariant,
                        accentColor = MaterialTheme.colorScheme.primary,
                        cornerRadius = 19.dp,
                        bevelDepth = 2.5.dp
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "View File",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 3D Claymorphism Surface Modifier (Swiggy / Blinkit tactile 3D clay pillow effect):
 * Renders a soft 3D body gradient, a bright top-left specular inner rim highlight,
 * and a physical bottom-right 3D clay bevel lip for genuine tactile depth.
 */
fun Modifier.claySurface(
    baseColor: Color,
    accentColor: Color = baseColor,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    bevelDepth: androidx.compose.ui.unit.Dp = 3.5.dp,
    isHeroSolid: Boolean = false
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(
        brush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = if (isHeroSolid) {
                listOf(
                    androidx.compose.ui.graphics.lerp(accentColor, Color.White, 0.22f),
                    accentColor,
                    androidx.compose.ui.graphics.lerp(accentColor, Color.Black, 0.22f)
                )
            } else {
                listOf(
                    androidx.compose.ui.graphics.lerp(baseColor, accentColor, 0.18f),
                    baseColor,
                    androidx.compose.ui.graphics.lerp(baseColor, Color.Black, 0.14f)
                )
            }
        ),
        shape = RoundedCornerShape(cornerRadius)
    )
    .drawWithContent {
        drawContent()
        val strokePx = bevelDepth.toPx()
        val radiusPx = cornerRadius.toPx()
        // 1. Bottom-right 3D clay physical lip / inner shadow
        drawRoundRect(
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = if (isHeroSolid) 0.28f else 0.22f)
                ),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height)
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx * 1.35f)
        )
        // 2. Top-left glossy clay specular highlight rim
        drawRoundRect(
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isHeroSolid) 0.48f else 0.28f),
                    accentColor.copy(alpha = 0.25f),
                    Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.85f)
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx * 0.75f)
        )
    }

/**
 * CLAY ELEMENT TYPE 1A: 3D Sculpted Clay Icon Orb (with glossy specular highlight dot)
 */
@Composable
fun ClayIconOrb(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    orbSize: androidx.compose.ui.unit.Dp = 42.dp,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
    isSolidWhiteOrb: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(orbSize)
            .clip(RoundedCornerShape(orbSize * 0.36f))
            .background(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = if (isSolidWhiteOrb) {
                        listOf(
                            Color.White,
                            Color.White.copy(alpha = 0.90f),
                            androidx.compose.ui.graphics.lerp(Color.White, accentColor, 0.28f)
                        )
                    } else {
                        listOf(
                            androidx.compose.ui.graphics.lerp(accentColor, Color.White, 0.38f),
                            accentColor,
                            androidx.compose.ui.graphics.lerp(accentColor, Color.Black, 0.28f)
                        )
                    }
                )
            )
            .border(
                width = 1.2.dp,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color.Black.copy(alpha = 0.22f)
                    )
                ),
                shape = RoundedCornerShape(orbSize * 0.36f)
            ),
        contentAlignment = Alignment.Center
    ) {
        // Specular 3D clay shine dot in top-left corner
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = orbSize * 0.14f, top = orbSize * 0.12f)
                .size(orbSize * 0.18f, orbSize * 0.10f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.55f))
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSolidWhiteOrb) accentColor else Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * CLAY ELEMENT TYPE 1B: Swiggy / Blinkit 3D Clay Action Category Tile
 */
@Composable
fun SwiggyClayActionTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    isHeroFeatured: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseSurface = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .claySurface(
                baseColor = baseSurface,
                accentColor = accentColor,
                cornerRadius = 20.dp,
                bevelDepth = 3.5.dp,
                isHeroSolid = isHeroFeatured
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ClayIconOrb(
                icon = icon,
                accentColor = accentColor,
                orbSize = 40.dp,
                iconSize = 21.dp,
                isSolidWhiteOrb = isHeroFeatured
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (isHeroFeatured) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isHeroFeatured) Color.Black.copy(alpha = 0.22f) else accentColor.copy(alpha = 0.16f)
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isHeroFeatured) Color.White else accentColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * CLAY ELEMENT TYPE 2: 3D Puffed Clay Metric Capsule & Status Pill
 */
@Composable
fun ClayMetricCapsule(
    valueText: String,
    labelText: String,
    accentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseSurface = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .claySurface(
                baseColor = baseSurface,
                accentColor = accentColor,
                cornerRadius = 18.dp,
                bevelDepth = 3.dp,
                isHeroSolid = false
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.lerp(accentColor, Color.White, 0.35f),
                                    accentColor
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = accentColor
                )
            }
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * CLAY ELEMENT TYPE 3: 3D Sculpted Clay Bento Preset Card (Swiggy / Blinkit Bento Block)
 */
@Composable
fun ClayBentoPresetTile(
    badgeLabel: String,
    valueTitle: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseSurface = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .claySurface(
                baseColor = baseSurface,
                accentColor = accentColor,
                cornerRadius = 20.dp,
                bevelDepth = 3.5.dp,
                isHeroSolid = false
            )
            .clickable(onClick = onClick)
            .padding(13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                ClayIconOrb(
                    icon = icon,
                    accentColor = accentColor,
                    orbSize = 30.dp,
                    iconSize = 16.dp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = valueTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchVerificationDialog(
    groups: List<com.example.ui.BatchGroup>,
    onConfirm: (List<com.example.ui.BatchGroup>) -> Unit,
    onDismiss: () -> Unit
) {
    var editableGroups by remember(groups) { mutableStateOf(groups) }
    val allUris = remember(groups) { groups.flatMap { it.uris } }
    var customRegroupState by remember { mutableStateOf<CustomRegroupModalState>(CustomRegroupModalState.Hidden) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Review Batch Documents") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        Button(
                            onClick = { onConfirm(editableGroups) },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Process ${editableGroups.size} Docs", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier.padding(paddingValues).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Quick Regroup Banner
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Total Pages: ${allUris.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Quick Regroup Batch",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 10 to "10").forEach { (size, label) ->
                                    OutlinedButton(
                                        onClick = {
                                            editableGroups = allUris.chunked(size).map { com.example.ui.BatchGroup(uris = it, isIdCard = false) }
                                        },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp)
                                    ) {
                                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { customRegroupState = CustomRegroupModalState.Visible("2") },
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("Custom", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                itemsIndexed(editableGroups) { index, group ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Document ${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("${group.uris.size} page(s)", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Images row
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(group.uris) { uri ->
                                    coil.compose.AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (group.uris.size > 1) {
                                    TextButton(onClick = {
                                        // Separate pages
                                        val newGroups = editableGroups.toMutableList()
                                        newGroups.removeAt(index)
                                        val separated = group.uris.map { com.example.ui.BatchGroup(uris = listOf(it), isIdCard = false) }
                                        newGroups.addAll(index, separated)
                                        editableGroups = newGroups
                                    }) {
                                        Text("Separate Pages")
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(1.dp))
                                }
                                
                                if (index < editableGroups.size - 1) {
                                    TextButton(onClick = {
                                        // Merge with next
                                        val newGroups = editableGroups.toMutableList()
                                        val current = newGroups.removeAt(index)
                                        val next = newGroups.removeAt(index) // next is now at `index`
                                        val merged = current.copy(uris = current.uris + next.uris)
                                        newGroups.add(index, merged)
                                        editableGroups = newGroups
                                    }) {
                                        Text("Merge with Next")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val activeRegroup = customRegroupState as? CustomRegroupModalState.Visible
        if (activeRegroup != null) {
            val customRegroupInput = activeRegroup.inputText
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { customRegroupState = CustomRegroupModalState.Hidden },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .wrapContentHeight()
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Custom Batch Grouping", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(
                                onClick = { customRegroupState = CustomRegroupModalState.Hidden },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Text(
                            text = "Enter how many pages per document to group the ${allUris.size} scanned pages (e.g. 5, 10, 20...):",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Quick preset pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(1, 2, 3, 5, 10, 20).forEach { presetNum ->
                                val isChosen = customRegroupInput == presetNum.toString()
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            customRegroupState = activeRegroup.copy(inputText = presetNum.toString())
                                        }
                                ) {
                                    Text(
                                        text = "$presetNum",
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isChosen) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (isChosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        // Stepper & Direct TextField
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val current = customRegroupInput.toIntOrNull() ?: 2
                                    if (current > 1) {
                                        customRegroupState = activeRegroup.copy(inputText = (current - 1).toString())
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            ) {
                                Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedTextField(
                                value = customRegroupInput,
                                onValueChange = { input ->
                                    if (input.all { it.isDigit() } && input.length <= 3) {
                                        customRegroupState = activeRegroup.copy(inputText = input)
                                    }
                                },
                                label = { Text("Pages Per Doc") },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    val current = customRegroupInput.toIntOrNull() ?: 2
                                    if (current < 100) {
                                        customRegroupState = activeRegroup.copy(inputText = (current + 1).toString())
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            ) {
                                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { customRegroupState = CustomRegroupModalState.Hidden },
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    val size = customRegroupInput.toIntOrNull()?.coerceIn(1, 100) ?: 2
                                    editableGroups = allUris.chunked(size).map { com.example.ui.BatchGroup(uris = it, isIdCard = false) }
                                    customRegroupState = CustomRegroupModalState.Hidden
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("Apply & Regroup", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
