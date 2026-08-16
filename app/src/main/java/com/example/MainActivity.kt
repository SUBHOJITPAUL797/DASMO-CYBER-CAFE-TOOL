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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

        database = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "dasmo_db").fallbackToDestructiveMigration().build()
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
                MainScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val targetSizeKb by viewModel.targetSizeKb.collectAsStateWithLifecycle()
    val imageFormat by viewModel.imageFormat.collectAsStateWithLifecycle()
    val enableAiAnalysis by viewModel.enableAiAnalysis.collectAsStateWithLifecycle()
    val showConfirmation by viewModel.showConfirmation.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
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

    val driveFiles by viewModel.driveFiles.collectAsStateWithLifecycle()
    val isDriveFilesLoading by viewModel.isDriveFilesLoading.collectAsStateWithLifecycle()
    val driveFilesError by viewModel.driveFilesError.collectAsStateWithLifecycle()

    var showSizeDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderDialogMode by remember { mutableStateOf("SYNC") } // "SYNC" or "MERGE"
    var showClearJunkWarning by remember { mutableStateOf(false) }
    var showTargetSizeWarningDialog by remember { mutableStateOf(false) }
    var pendingScanType by remember { mutableStateOf<String?>(null) } // "SINGLE" or "BATCH" or "MULTI"
    var isFabExpanded by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf("DASHBOARD") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedDocIds by remember { mutableStateOf(setOf<Int>()) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var mergeFileName by remember { mutableStateOf("") }
    var mergeFolderName by remember { mutableStateOf("") }
    var mergeFolderId by remember { mutableStateOf<String?>(null) }
    var mergeTargetSizeKb by remember { mutableStateOf(targetSizeKb.toString()) }

    val nameBeforeType by viewModel.nameBeforeType.collectAsStateWithLifecycle()
    val activeQueue by viewModel.activeQueue.collectAsStateWithLifecycle()
    val publicFolderSize by viewModel.publicFolderSize.collectAsStateWithLifecycle()
    var selectedPreviewDoc by remember { mutableStateOf<com.example.data.DocumentEntity?>(null) }
    val scope = rememberCoroutineScope()
    val batchVerificationGroups by viewModel.batchVerificationGroups.collectAsStateWithLifecycle()

    // Google Sign-In setup
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive"))
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

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

    var updateInfo by remember { mutableStateOf<com.example.util.UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val info = com.example.util.UpdateChecker.checkForUpdates(context)
            if (info.hasUpdate) {
                updateInfo = info
                showUpdateDialog = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(activeTab, googleEmail) {
        if (activeTab == "FILES" && googleEmail != null) {
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
    var showMultiScanDialog by remember { mutableStateOf(false) }
    var showContinuousBatchCamera by remember { mutableStateOf(false) }

    var editingDoc by remember { mutableStateOf<com.example.data.DocumentEntity?>(null) }
    var editPersonName by remember { mutableStateOf("") }
    var editDocType by remember { mutableStateOf("") }
    var editFormat by remember { mutableStateOf(com.example.ui.UploadFormat.PDF) }
    var editScannedUris by remember { mutableStateOf<List<Uri>?>(null) }
    var editDocIdForScan by remember { mutableStateOf<Int?>(null) }

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
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                val imageUris = pages.map { it.imageUri }
                accumulatedPageUris.addAll(imageUris)
                showMultiScanDialog = true
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
                viewModel.processBatchScannedImages(imageUris)
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
        val activity = context.findActivity()
        if (activity != null) {
            scanner.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.addOnFailureListener { e ->
                Toast.makeText(context, "Failed to launch scanner: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Activity context not found", Toast.LENGTH_SHORT).show()
        }
    }

    val launchAdditionalScan: () -> Unit = {
        val activity = context.findActivity()
        if (activity != null) {
            scanner.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.addOnFailureListener { e ->
                Toast.makeText(context, "Failed to launch scanner: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Activity context not found", Toast.LENGTH_SHORT).show()
        }
    }

    val launchEditScan: (Int) -> Unit = { docId ->
        accumulatedPageUris.clear()
        editDocIdForScan = docId
        val activity = context.findActivity()
        if (activity != null) {
            scanner.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.addOnFailureListener { e ->
                Toast.makeText(context, "Failed to launch scanner: ${e.message}", Toast.LENGTH_LONG).show()
                editDocIdForScan = null
            }
        } else {
            Toast.makeText(context, "Activity context not found", Toast.LENGTH_SHORT).show()
            editDocIdForScan = null
        }
    }

    val launchMultiScan: () -> Unit = {
        val activity = context.findActivity()
        if (activity != null) {
            batchScanner.getStartScanIntent(activity).addOnSuccessListener { intentSender ->
                multiScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.addOnFailureListener { e ->
                Toast.makeText(context, "Failed to launch scanner: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Activity context not found", Toast.LENGTH_SHORT).show()
        }
    }

    val launchBatchScan: () -> Unit = {
        showContinuousBatchCamera = true
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

    if (showUpdateDialog && updateInfo != null) {
        com.example.ui.InAppUpdateDialog(
            updateInfo = updateInfo!!,
            onDismiss = { showUpdateDialog = false }
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
                        activeTab = "APPROVALS"
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
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
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (googleEmail != null) Color(0xFF4CAF50) else Color(0xFF2C2F36))
                                )
                                Text(
                                    text = if (googleEmail != null) "DRIVE SYNC ON" else "DRIVE DISCONNECTED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                                )
                            }
                        }
                    }
                    Box {
                        var expanded by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { expanded = true },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Clear App Junk", color = MaterialTheme.colorScheme.error) },
                                onClick = { 
                                    expanded = false
                                    showClearJunkWarning = true
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dashboard Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeTab = "DASHBOARD" }
                            .padding(8.dp)
                            .weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Dashboard",
                            tint = if (activeTab == "DASHBOARD") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "HOME",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeTab == "DASHBOARD") FontWeight.Bold else FontWeight.Medium,
                            color = if (activeTab == "DASHBOARD") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Files Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeTab = "FILES" }
                            .padding(8.dp)
                            .weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Files",
                            tint = if (activeTab == "FILES") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "DRIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeTab == "FILES") FontWeight.Bold else FontWeight.Medium,
                            color = if (activeTab == "FILES") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Space for center FAB
                    Spacer(modifier = Modifier.weight(1f))

                    // Captures Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeTab = "CAPTURES" }
                            .padding(8.dp)
                            .weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Captures",
                            tint = if (activeTab == "CAPTURES") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "CAPTURES",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeTab == "CAPTURES") FontWeight.Bold else FontWeight.Medium,
                            color = if (activeTab == "CAPTURES") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Sync Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeTab = "SYNC" }
                            .padding(8.dp)
                            .weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync",
                            tint = if (activeTab == "SYNC") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeTab == "SYNC") FontWeight.Bold else FontWeight.Medium,
                            color = if (activeTab == "SYNC") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isAdmin) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { activeTab = "APPROVALS" }
                                .padding(8.dp)
                                .weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Approvals",
                                tint = if (activeTab == "APPROVALS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "ADMIN",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (activeTab == "APPROVALS") FontWeight.Bold else FontWeight.Medium,
                                color = if (activeTab == "APPROVALS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (activeTab == "DASHBOARD") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset(y = 48.dp)
                ) {
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
                                        isFabExpanded = false
                                        activeTab = "PASSPORT"
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
                                        isFabExpanded = false
                                        val lastSizeSub = subfolderAtLastSizeChange
                                        val shouldWarn = targetSubfolder.trim().isNotEmpty() && 
                                                         lastSizeSub != null && 
                                                         targetSubfolder.trim() != lastSizeSub.trim()
                                        if (shouldWarn) {
                                            pendingScanType = "BATCH"
                                            showTargetSizeWarningDialog = true
                                        } else {
                                            launchBatchScan()
                                        }
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
                                        isFabExpanded = false
                                        val lastSizeSub = subfolderAtLastSizeChange
                                        val shouldWarn = targetSubfolder.trim().isNotEmpty() && 
                                                         lastSizeSub != null && 
                                                         targetSubfolder.trim() != lastSizeSub.trim()
                                        if (shouldWarn) {
                                            pendingScanType = "MULTI"
                                            showTargetSizeWarningDialog = true
                                        } else {
                                            launchMultiScan()
                                        }
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
                                        isFabExpanded = false
                                        val lastSizeSub = subfolderAtLastSizeChange
                                        val shouldWarn = targetSubfolder.trim().isNotEmpty() && 
                                                         lastSizeSub != null && 
                                                         targetSubfolder.trim() != lastSizeSub.trim()
                                        if (shouldWarn) {
                                            pendingScanType = "SINGLE"
                                            showTargetSizeWarningDialog = true
                                        } else {
                                            launchSingleScan()
                                        }
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
                        onClick = { isFabExpanded = !isFabExpanded },
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
                "DASHBOARD" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Smart Subfolder Input
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Target Subfolder (Optional)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Files will be saved in a subfolder inside '$driveFolderName'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = targetSubfolder,
                                onValueChange = { 
                                    viewModel.setTargetSubfolder(it) 
                                },
                                placeholder = { Text("e.g. Customer Name") },
                                singleLine = true,
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

                    // Processing Presets Card (Lavender Theme)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
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
                                Text(
                                    text = "Processing Presets",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "GLOBAL SETTINGS",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Card 1
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { showSizeDialog = true }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "TARGET SIZE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "${targetSizeKb} KB",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                // Card 2
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.updateEnableAiAnalysis(!enableAiAnalysis) }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "PARSER ENGINE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (enableAiAnalysis) "CLOUD AI" else "LOCAL OCR",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (enableAiAnalysis) "Cloud deep learning" else "100% local, fast & offline",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Card 3 (Image Format)
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { 
                                            viewModel.updateImageFormat(if (imageFormat == "WEBP") "JPEG" else "WEBP")
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "IMAGE FORMAT",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            imageFormat,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (imageFormat == "WEBP") "Smaller size, lossless" else "Wider compatibility",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                // Placeholder card to maintain grid layout
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            // Confirmation Screen Setting Toggle
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateShowConfirmation(!showConfirmation) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "CONFIRMATION POPUP",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (showConfirmation) "SHOW DETAILS POPUP" else "AUTO SAVE & UPLOAD",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (showConfirmation) "Verify and edit scanned details before uploading" else "Instantly save scans in background without prompts",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = showConfirmation,
                                        onCheckedChange = { viewModel.updateShowConfirmation(it) }
                                    )
                                }
                            }

                            // Auto Enhance Setting Toggle
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateAutoEnhanceEnabled(!autoEnhanceEnabled) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "SCANNER FILTER",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (autoEnhanceEnabled) "AUTO ENHANCE ON" else "ORIGINAL PHOTO (OFF)",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (autoEnhanceEnabled) "Apply ML cleanup and shadow removal by default" else "Raw camera photo. Best for preserving ID card photos",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = autoEnhanceEnabled,
                                        onCheckedChange = { viewModel.updateAutoEnhanceEnabled(it) }
                                    )
                                }
                            }

                            // A4 Format Setting Toggle
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateUseA4Format(!useA4Format) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "ID CARD LAYOUT",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (useA4Format) "A4 SHEET CANVAS" else "STACKED VERTICALLY",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (useA4Format) "ID cards will be placed onto a standard A4 canvas" else "ID card sides will be stacked vertically without an A4 background",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = useA4Format,
                                        onCheckedChange = { viewModel.updateUseA4Format(it) }
                                    )
                                }
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surface)
                                        )
                                    }
                                    Text(
                                        "Auto Stack Docs",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surface)
                                        )
                                    }
                                    Text(
                                        "AI Auto-Name",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // App Update Card
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Toast.makeText(context, "Checking GitHub Releases for updates...", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            try {
                                                val info = com.example.util.UpdateChecker.checkForUpdates(context)
                                                updateInfo = info
                                                if (info.hasUpdate) {
                                                    showUpdateDialog = true
                                                } else {
                                                    Toast.makeText(context, "You are using the latest version (v${info.currentVersion})", Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Failed to check updates: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "APP VERSION & UPDATES",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "CHECK FOR UPDATES",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "Fetch latest APK and release changelogs from GitHub",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = "Update",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            // Visual Storage Usage Indicator Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
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
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Dasmo Scan Folder",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Device Documents",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            val limitBytes = 100 * 1024 * 1024L // 100 MB threshold
                            val progress = (publicFolderSize.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
                            val isNearLimit = publicFolderSize > limitBytes * 0.8 // warning at 80%
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column {
                                    Text(
                                        text = "Occupied Storage",
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
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Limit: 100 MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isNearLimit) "⚠️ Storage is full! Clean up suggested." else "Storage status healthy",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = { showClearJunkWarning = true },
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

                    if (isProcessing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = statusMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (statusMessage.isNotEmpty()) {
                        Text(
                            text = statusMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (statusMessage.contains("Success")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }

                    if (activeQueue.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Pulse status dot representing active sessions
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE65100))
                                        )
                                        Text(
                                            "Active Session Queue",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.clearActiveQueue() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear Completed", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                
                                Text(
                                    "Displays live text recognition, image compression, and background Google Drive sync progress.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    activeQueue.forEach { item ->
                                        val isDone = item.status.contains("Completed") || item.status.contains("locally")
                                        val isFailed = item.status.contains("Failed")
                                        val isProcessing = !isDone && !isFailed && !item.status.contains("Cancelled")
                                        
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                                .border(BorderStroke(1.dp, Color(0xFFF0F1F5)), RoundedCornerShape(12.dp))
                                                .padding(12.dp)
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
                                                        imageVector = if (isDone) Icons.Default.CheckCircle 
                                                                     else if (isFailed) Icons.Default.Warning 
                                                                     else Icons.Default.Refresh,
                                                        contentDescription = "Status Icon",
                                                        tint = if (isDone) Color(0xFF2E7D32) 
                                                               else if (isFailed) Color(0xFFC62828) 
                                                               else MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Column {
                                                        Text(
                                                            text = "${item.personName}'s ${item.documentType}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = "Format: ${item.format}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(
                                                            if (isDone) Color(0xFFE8F5E9) 
                                                            else if (isFailed) Color(0xFFFFEBEE) 
                                                            else Color(0xFFE8EAF6)
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = item.status,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = if (isDone) Color(0xFF2E7D32) 
                                                                else if (isFailed) Color(0xFFC62828) 
                                                                else MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            
                                            if (isProcessing) {
                                                Spacer(modifier = Modifier.height(8.dp))
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

                    Spacer(modifier = Modifier.height(120.dp))
                    }
                }
                "CAPTURES" -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (selectedDocIds.isNotEmpty()) "${selectedDocIds.size} Selected" else "Recent Captures",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selectedDocIds.isNotEmpty()) {
                            Row {
                                IconButton(onClick = {
                                    viewModel.retryUpload(context, selectedDocIds)
                                    selectedDocIds = setOf()
                                    Toast.makeText(context, "Added to upload queue", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = "Upload to Drive", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    val urisToShare = ArrayList<Uri>()
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
                                            }
                                        }
                                    }
                                    if (urisToShare.isNotEmpty()) {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "*/*"
                                            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, urisToShare)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share Documents"))
                                    }
                                    selectedDocIds = setOf()
                                }) {
                                    Icon(imageVector = Icons.Default.Share, contentDescription = "Share Selected")
                                }
                                if (selectedDocIds.size >= 2) {
                                    IconButton(onClick = { 
                                        mergeFileName = "Merged_${System.currentTimeMillis()}"
                                        mergeFolderName = ""
                                        mergeFolderId = null
                                        mergeTargetSizeKb = targetSizeKb.toString()
                                        showMergeDialog = true 
                                    }) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = "Merge to PDF")
                                    }
                                }
                                IconButton(onClick = { selectedDocIds = setOf() }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Selection")
                                }
                            }
                        }
                    }

                    if (documents.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No documents scanned yet. Press + to start.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 90.dp, start = 16.dp, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            items(documents.size) { index ->
                                val doc = documents[index]
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
                                            selectedPreviewDoc = doc
                                        }
                                    },
                                    onRetryClick = if (!doc.isUploaded) {
                                        {
                                            viewModel.retryUpload(context, setOf(doc.id))
                                            Toast.makeText(context, "Added to upload queue", Toast.LENGTH_SHORT).show()
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }
                "PASSPORT" -> {
                    com.example.ui.PassportPhotoScreen(onBack = { activeTab = "DASHBOARD" })
                }
                "APPROVALS" -> {
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
                "FILES" -> {
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

                            val filteredDriveFiles = driveFiles.filter { file ->
                                file.name.contains(searchQuery, ignoreCase = true) ||
                                (file.folderName ?: "").contains(searchQuery, ignoreCase = true) ||
                                file.mimeType.contains(searchQuery, ignoreCase = true)
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
                                        .background(Color(0xFFE8F5E9), shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
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
                                        Button(onClick = { activeTab = "SYNC" }) {
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
                                    items(filteredDriveFiles.size) { index ->
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
                "SYNC" -> {
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

                                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

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
                                                folderDialogMode = "SYNC"
                                                showFolderDialog = true
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

                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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

                                Divider(color = MaterialTheme.colorScheme.outlineVariant)

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
                                        viewModel.clearAllData()
                                        Toast.makeText(context, "Scans & local database securely wiped!", Toast.LENGTH_SHORT).show()
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
            }
        }
    }

    if (showSizeDialog) {
        var sizeInput by remember { mutableStateOf(targetSizeKb.toString()) }
        AlertDialog(
            onDismissRequest = { showSizeDialog = false },
            title = { Text("Set Target Output Size") },
            text = {
                OutlinedTextField(
                    value = sizeInput,
                    onValueChange = { sizeInput = it.filter { char -> char.isDigit() } },
                    label = { Text("Size in KB") },
                    singleLine = true,
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
            },
            confirmButton = {
                Button(onClick = {
                    sizeInput.toIntOrNull()?.let { size ->
                        if (size > 0) {
                            viewModel.updateTargetSize(size)
                        }
                    }
                    showSizeDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSizeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTargetSizeWarningDialog) {
        var sizeInput by remember { mutableStateOf(targetSizeKb.toString()) }
        AlertDialog(
            onDismissRequest = { 
                showTargetSizeWarningDialog = false 
                pendingScanType = null
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
                        onValueChange = { sizeInput = it.filter { char -> char.isDigit() } },
                        label = { Text("Target Size (KB)") },
                        singleLine = true,
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
                            showTargetSizeWarningDialog = false
                            if (pendingScanType == "SINGLE") {
                                launchSingleScan()
                            } else if (pendingScanType == "BATCH") {
                                launchBatchScan()
                            } else if (pendingScanType == "MULTI") {
                                launchMultiScan()
                            }
                            pendingScanType = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Keep ${targetSizeKb}KB")
                    }
                    Button(
                        onClick = {
                            sizeInput.toIntOrNull()?.let { size ->
                                if (size > 0) {
                                    viewModel.updateTargetSize(size)
                                }
                            }
                            showTargetSizeWarningDialog = false
                            if (pendingScanType == "SINGLE") {
                                launchSingleScan()
                            } else if (pendingScanType == "BATCH") {
                                launchBatchScan()
                            } else if (pendingScanType == "MULTI") {
                                launchMultiScan()
                            }
                            pendingScanType = null
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
                    showTargetSizeWarningDialog = false 
                    pendingScanType = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMergeDialog) {
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
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
                        text = "Merge ${selectedDocIds.size} images into a single PDF.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = mergeFileName,
                        onValueChange = { mergeFileName = it },
                        label = { Text("File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mergeTargetSizeKb,
                        onValueChange = { mergeTargetSizeKb = it },
                        label = { Text("Target Size (KB)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().clickable {
                            folderDialogMode = "MERGE"
                            showFolderDialog = true
                        }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Folder, contentDescription = "Folder", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (mergeFolderId != null) mergeFolderName else "Select Drive Folder",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (mergeFolderId != null) "Folder Selected" else "Tap to choose destination",
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
                    val finalSize = mergeTargetSizeKb.toIntOrNull() ?: targetSizeKb
                    viewModel.mergeDocumentsToPdf(
                        context = context,
                        docIds = selectedDocIds,
                        fileName = if (mergeFileName.isNotBlank()) mergeFileName else "Merged_${System.currentTimeMillis()}",
                        folderId = mergeFolderId,
                        folderName = mergeFolderName,
                        targetSizeKb = finalSize
                    )
                    showMergeDialog = false
                    selectedDocIds = setOf()
                }) {
                    Text("Merge")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMultiScanDialog) {
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
                            text = "Multi-Page Scan",
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
                                                showMultiScanDialog = false
                                                editDocIdForScan = null
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
                                showMultiScanDialog = false
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
                                showMultiScanDialog = false
                                if (editDocIdForScan != null) {
                                    editScannedUris = accumulatedPageUris.toList()
                                    accumulatedPageUris.clear()
                                    editDocIdForScan = null
                                } else {
                                    viewModel.processMultiScannedImages(accumulatedPageUris.toList())
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
                                showMultiScanDialog = false
                                accumulatedPageUris.clear()
                                editDocIdForScan = null
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

    if (showContinuousBatchCamera) {
        com.example.ui.BatchCameraScanScreen(
            onDismiss = { showContinuousBatchCamera = false },
            onFinishBatch = { uris ->
                showContinuousBatchCamera = false
                viewModel.processBatchScannedImages(uris)
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
        var tempUploadFormat by remember(doc) { mutableStateOf(UploadFormat.JPEG) }

        val presets = remember { listOf("Aadhaar Card", "PAN Card", "Voter ID", "Passport", "Driving License", "Marksheet", "Ration Card") }

        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingDocument() },
            title = {
                Text(
                    text = "Confirm Document Details",
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
                    Text(
                        text = "Dasmo AI analyzed the document content. Adjust the details to fit your governmental portal requirements.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    var isPreviewExpanded by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isPreviewExpanded) 300.dp else 140.dp)
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
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = if (isPreviewExpanded) ContentScale.Fit else ContentScale.Crop
                            )
                            if (!isPreviewExpanded) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        "Tap to review crop",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.surface
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
                            text = "⚠ Leaving this blank will auto-generate: Client_xxxxx",
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

                    Spacer(modifier = Modifier.height(16.dp))

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = tempUploadFormat == UploadFormat.JPEG,
                                onClick = { tempUploadFormat = UploadFormat.JPEG }
                            )
                            Text("JPEG", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = tempUploadFormat == UploadFormat.PDF,
                                onClick = { tempUploadFormat = UploadFormat.PDF }
                            )
                            Text("PDF", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = tempUploadFormat == UploadFormat.BOTH,
                                onClick = { tempUploadFormat = UploadFormat.BOTH }
                            )
                            Text("Both", style = MaterialTheme.typography.bodySmall)
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
                                "PORTAL FILENAME",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
							)
                            val displayPersonName = tempPersonName.trim().ifEmpty { "Client_xxxxx" }
                            val displayDocumentType = tempDocumentType.trim().ifEmpty { "Document" }
                            val displayBaseName = if (nameBeforeType) {
                                "${displayPersonName.replace(" ", "_")}_${displayDocumentType.replace(" ", "_")}"
                            } else {
                                "${displayDocumentType.replace(" ", "_")}_${displayPersonName.replace(" ", "_")}"
                            }
                            Text(
                                "$displayBaseName.jpeg",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            viewModel.uploadInBackground(context, tempPersonName, tempDocumentType, tempUploadFormat)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Upload in Background", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            viewModel.confirmAndUpload(context, tempPersonName, tempDocumentType, tempUploadFormat)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Sync Directly", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelPendingDocument() }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearJunkWarning) {
        var alsoDeletePublicFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showClearJunkWarning = false },
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
                        modifier = Modifier.clickable { alsoDeletePublicFiles = !alsoDeletePublicFiles }.padding(top = 8.dp)
                    ) {
                        Checkbox(
                            checked = alsoDeletePublicFiles,
                            onCheckedChange = { alsoDeletePublicFiles = it }
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
                        viewModel.clearAllData(alsoDeletePublicFiles)
                        showClearJunkWarning = false
                        Toast.makeText(context, "App junk cleared successfully", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Yes, Clear Junk", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearJunkWarning = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFolderDialog) {
        var showNewFolderInput by remember { mutableStateOf(false) }
        var newFolderNameInput by remember { mutableStateOf("") }
        var folderSearchQuery by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            viewModel.clearFolderError()
        }

        LaunchedEffect(currentFolderId, googleEmail) {
            if (googleEmail != null) {
                viewModel.fetchSubfolders(context, currentFolderId)
            }
        }

        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = {
                Text(
                    text = "Select Drive Destination",
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
                        text = "Decide where to store your PDFs and scanned images. Navigate, click to enter subfolders, or create new ones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Google Security Notice Card (Removed)

// Removed debug diagnostics button

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
                        val canGoUp = folderPathStack.size > 1
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
                            onClick = { showNewFolderInput = !showNewFolderInput },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+ New Folder", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (showNewFolderInput) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newFolderNameInput,
                                onValueChange = { newFolderNameInput = it },
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
                                onClick = {
                                    if (newFolderNameInput.isNotBlank()) {
                                        viewModel.createNewSubfolder(context, newFolderNameInput) { success ->
                                            if (success) {
                                                newFolderNameInput = ""
                                                showNewFolderInput = false
                                            } else {
                                                Toast.makeText(context, "Failed to create folder", Toast.LENGTH_SHORT).show()
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

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

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
                        onValueChange = { folderSearchQuery = it },
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
                                IconButton(onClick = { folderSearchQuery = "" }) {
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
                                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderDialogMode == "MERGE") {
                            mergeFolderId = currentFolderId
                            mergeFolderName = currentFolderName ?: "Selected Folder"
                        } else {
                            viewModel.selectCurrentAsDestination()
                        }
                        showFolderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Select This Folder")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFolderDialog = false }
                ) {
                    Text("Close")
                }
            }
        )
    }

    if (selectedPreviewDoc != null) {
        val doc = selectedPreviewDoc!!
        AlertDialog(
            onDismissRequest = { selectedPreviewDoc = null },
            title = {
                Text(
                    text = "Document Details & Local File Info",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(model = doc.localFilePath),
                        contentDescription = "Full Document",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "File localFilePath: ${doc.localFilePath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)

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
                            SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(doc.timestamp)),
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
                    onClick = { selectedPreviewDoc = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        val doc = selectedPreviewDoc!!
                        editingDoc = doc
                        editPersonName = doc.personName
                        editDocType = doc.documentType
                        editFormat = if (doc.fileName.endsWith(".pdf", ignoreCase = true)) com.example.ui.UploadFormat.PDF else com.example.ui.UploadFormat.JPEG
                        editScannedUris = null
                        editDocIdForScan = null
                        selectedPreviewDoc = null
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

    if (editingDoc != null) {
        val doc = editingDoc!!
        AlertDialog(
            onDismissRequest = { editingDoc = null },
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
                        value = editPersonName,
                        onValueChange = { editPersonName = it },
                        label = { Text("Person Name") },
                        placeholder = { Text("e.g., John Doe") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editDocType,
                        onValueChange = { editDocType = it },
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (editFormat == format) MaterialTheme.colorScheme.primaryContainer 
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable { editFormat = format }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                RadioButton(
                                    selected = (editFormat == format),
                                    onClick = { editFormat = format }
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = format.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (editFormat == format) MaterialTheme.colorScheme.onPrimaryContainer 
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (editScannedUris == null) {
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
                                text = "📄 ${editScannedUris!!.size} new page(s) scanned to replace file!",
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
                            personName = editPersonName,
                            documentType = editDocType,
                            format = editFormat,
                            newPageUris = editScannedUris
                        )
                        editingDoc = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Save & Sync", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editingDoc = null },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DocumentItem(doc: DocumentEntity, isSelected: Boolean = false, onLongClick: () -> Unit = {}, onClick: () -> Unit, onRetryClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
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
            Image(
                painter = rememberAsyncImagePainter(model = doc.localFilePath),
                contentDescription = "Document Thumbnail",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${doc.personName}'s ${doc.documentType}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = doc.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "📱 Local: ${doc.localFilePath}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        text = "☁️ Drive: Uploaded",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF4CAF50)
                    )
                }
                Text(
                    text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(doc.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (doc.isUploaded) {
                Icon(
                    Icons.Default.CheckCircle, 
                    contentDescription = "Uploaded", 
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else if (onRetryClick != null) {
                IconButton(onClick = onRetryClick) {
                    Icon(
                        Icons.Default.CloudUpload, 
                        contentDescription = "Upload Failed - Retry", 
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Icon(
                    Icons.Default.Warning, 
                    contentDescription = "Upload Failed", 
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun DriveFileItem(file: com.example.network.DriveFile, onOpenClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (file.mimeType.contains("pdf", ignoreCase = true)) Color(0xFFFFEBEE)
                        else Color(0xFFE8F5E9)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Document Icon",
                    tint = if (file.mimeType.contains("pdf", ignoreCase = true)) Color(0xFFD32F2F)
                           else Color(0xFF388E3C),
                    modifier = Modifier.size(24.dp)
                )
            }
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
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchVerificationDialog(
    groups: List<com.example.ui.BatchGroup>,
    onConfirm: (List<com.example.ui.BatchGroup>) -> Unit,
    onDismiss: () -> Unit
) {
    var editableGroups by remember(groups) { mutableStateOf(groups) }

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
                        TextButton(onClick = { onConfirm(editableGroups) }) {
                            Text("Process ${editableGroups.size} Docs")
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
    }
}
