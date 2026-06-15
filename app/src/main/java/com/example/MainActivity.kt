package com.example

import android.app.Activity
import android.content.Context
import android.os.Bundle
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.UploadFormat
import com.example.ui.theme.MyApplicationTheme
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
    val enableAiAnalysis by viewModel.enableAiAnalysis.collectAsStateWithLifecycle()
    val showConfirmation by viewModel.showConfirmation.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val pendingDoc by viewModel.pendingDocument.collectAsStateWithLifecycle()

    val googleEmail by viewModel.googleEmail.collectAsStateWithLifecycle()
    val driveFolderId by viewModel.driveFolderId.collectAsStateWithLifecycle()
    val driveFolderName by viewModel.driveFolderName.collectAsStateWithLifecycle()
    val targetSubfolder by viewModel.targetSubfolder.collectAsStateWithLifecycle()

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
    var activeTab by remember { mutableStateOf("DASHBOARD") }
    var searchQuery by remember { mutableStateOf("") }

    val scanMode by viewModel.scanMode.collectAsStateWithLifecycle()
    val nameBeforeType by viewModel.nameBeforeType.collectAsStateWithLifecycle()
    val activeQueue by viewModel.activeQueue.collectAsStateWithLifecycle()
    var selectedPreviewDoc by remember { mutableStateOf<com.example.data.DocumentEntity?>(null) }
    val scope = rememberCoroutineScope()

    // Google Sign-In setup
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
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

    LaunchedEffect(activeTab, googleEmail) {
        if (activeTab == "FILES" && googleEmail != null) {
            viewModel.fetchDriveFiles(context)
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    viewModel.setGoogleEmail(account.email)
                    Toast.makeText(context, "Connected to ${account.email}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
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

    val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(2)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    val scanner = GmsDocumentScanning.getClient(options)
    
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                val imageUris = pages.map { it.imageUri }
                val pdfUri = scanResult.pdf?.uri
                viewModel.processScannedImages(imageUris, pdfUri)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(
        topBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
                color = Color.White
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
                                .background(Color(0xFF005AC1)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Dasmo Cyber Tool",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B1B1F)
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
                                    color = Color(0xFF44474E),
                                    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            scanner.getStartScanIntent(context as Activity).addOnSuccessListener { intentSender ->
                                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }.addOnFailureListener {
                                Toast.makeText(context, "Failed to launch scanner", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF3F4F9))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Scan Document",
                            tint = Color(0xFF44474E)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                color = Color.White,
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
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Dashboard",
                            tint = if (activeTab == "DASHBOARD") Color(0xFF005AC1) else Color(0xFF44474E)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "DASHBOARD",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeTab == "DASHBOARD") FontWeight.Bold else FontWeight.Medium,
                            color = if (activeTab == "DASHBOARD") Color(0xFF005AC1) else Color(0xFF44474E)
                        )
                    }

                    // Files Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeTab = "FILES" }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Files",
                            tint = if (activeTab == "FILES") Color(0xFF005AC1) else Color(0xFF44474E)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "FILES",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeTab == "FILES") FontWeight.Bold else FontWeight.Medium,
                            color = if (activeTab == "FILES") Color(0xFF005AC1) else Color(0xFF44474E)
                        )
                    }

                    // Sync Tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { activeTab = "SYNC" }
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sync",
                            tint = if (activeTab == "SYNC") Color(0xFF005AC1) else Color(0xFF44474E)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeTab == "SYNC") FontWeight.Bold else FontWeight.Medium,
                            color = if (activeTab == "SYNC") Color(0xFF005AC1) else Color(0xFF44474E)
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    scanner.getStartScanIntent(context as Activity).addOnSuccessListener { intentSender ->
                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                    }.addOnFailureListener {
                        Toast.makeText(context, "Failed to launch scanner", Toast.LENGTH_SHORT).show()
                    }
                },
                containerColor = Color(0xFF005AC1),
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .size(72.dp)
                    .offset(y = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add, 
                    contentDescription = "Scan Document",
                    modifier = Modifier.size(36.dp)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFF3F4F9))
        ) {
            when (activeTab) {
                "DASHBOARD" -> {
                    // Smart Subfolder Input
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                color = Color(0xFF1D192B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Files will be saved in a subfolder inside '$driveFolderName'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF44474E)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = targetSubfolder,
                                onValueChange = { viewModel.setTargetSubfolder(it) },
                                placeholder = { Text("e.g. Customer Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF1D192B),
                                    unfocusedTextColor = Color(0xFF1D192B),
                                    focusedPlaceholderColor = Color(0xFF74777F),
                                    unfocusedPlaceholderColor = Color(0xFF74777F),
                                    focusedBorderColor = Color(0xFF005AC1),
                                    unfocusedBorderColor = Color(0xFF74777F),
                                    cursorColor = Color(0xFF005AC1)
                                )
                            )
                        }
                    }

                    // Processing Presets Card (Lavender Theme)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DEF8)),
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
                                    color = Color(0xFF1D192B)
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFD0BCFF),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "GLOBAL SETTINGS",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF381E72)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Card 1
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.6f)),
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
                                            color = Color(0xFF49454F)
                                        )
                                        Text(
                                            "${targetSizeKb} KB",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1D192B)
                                        )
                                    }
                                }
                                // Card 2
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.6f)),
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
                                            color = Color(0xFF005AC1)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (enableAiAnalysis) "CLOUD AI" else "LOCAL OCR",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF1D192B)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (enableAiAnalysis) "Cloud deep learning" else "100% local, fast & offline",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF74777F)
                                        )
                                    }
                                }
                            }

                            // Confirmation Screen Setting Toggle
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.6f)),
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
                                            color = Color(0xFF005AC1)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (showConfirmation) "SHOW DETAILS POPUP" else "AUTO SAVE & UPLOAD",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF1D192B)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (showConfirmation) "Verify and edit scanned details before uploading" else "Instantly save scans in background without prompts",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF74777F)
                                        )
                                    }
                                    Switch(
                                        checked = showConfirmation,
                                        onCheckedChange = { viewModel.updateShowConfirmation(it) }
                                    )
                                }
                            }

                            Divider(color = Color(0xFFE1E2E9).copy(alpha = 0.5f))
                            Text(
                                text = "SCAN LAYOUT FORMAT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF49454F)
                            )
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFE8DEF8)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.6.dp, Color(0xFF005AC1)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Active",
                                            tint = Color(0xFF005AC1),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "ID Card (A4 2-Sided)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF005AC1)
                                        )
                                    }
                                }
                            }

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
                                            .background(Color(0xFF6750A4)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                    Text(
                                        "Auto Stack Docs",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF49454F)
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
                                            .background(Color(0xFF6750A4)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                    Text(
                                        "AI Auto-Name",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF49454F)
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
                            color = Color(0xFF005AC1)
                        )
                        Text(
                            text = statusMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF005AC1)
                        )
                    } else if (statusMessage.isNotEmpty()) {
                        Text(
                            text = statusMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (statusMessage.contains("Success")) Color(0xFF005AC1) else MaterialTheme.colorScheme.error
                        )
                    }

                    if (activeQueue.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(16.dp),
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
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Background Queue",
                                            tint = Color(0xFF005AC1),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            "Active Queue Monitor",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1D192B)
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.clearActiveQueue() },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("Clear done", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    activeQueue.forEach { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "${item.personName}'s ${item.documentType}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF1D192B)
                                                )
                                                Text(
                                                    "Format: ${item.format}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.Gray
                                                )
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (!item.status.contains("Completed") && !item.status.contains("Failed") && !item.status.contains("locally")) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(12.dp),
                                                        strokeWidth = 2.dp,
                                                        color = Color(0xFF005AC1)
                                                    )
                                                }
                                                Text(
                                                    item.status,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (item.status.contains("Completed") || item.status.contains("locally")) Color(0xFF4CAF50) else if (item.status.contains("Failed")) Color.Red else Color(0xFF005AC1)
                                                )
                                            }
                                        }
                                        Divider(color = Color(0xFFF3F4F9))
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "Recent Captures",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF44474E)
                    )

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
                                DocumentItem(doc) { selectedPreviewDoc = doc }
                            }
                        }
                    }
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
                                    focusedTextColor = Color(0xFF1D192B),
                                    unfocusedTextColor = Color(0xFF1D192B),
                                    focusedPlaceholderColor = Color(0xFF74777F),
                                    unfocusedPlaceholderColor = Color(0xFF74777F),
                                    focusedBorderColor = Color(0xFF005AC1),
                                    unfocusedBorderColor = Color(0xFF74777F),
                                    cursorColor = Color(0xFF005AC1)
                                )
                            )

                            IconButton(
                                onClick = { viewModel.fetchDriveFiles(context) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF3F4F9))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh list",
                                    tint = Color(0xFF005AC1)
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
                                    CircularProgressIndicator(color = Color(0xFF005AC1))
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
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEEBEE)),
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
                                    color = Color(0xFF44474E)
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

                            if (filteredDriveFiles.isEmpty()) {
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
                            colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                            tint = Color(0xFF44474E),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Text(
                                            text = "Google Drive Sync",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1B1B1F)
                                        )
                                    }
                                    Text(
                                        text = "Back up and organize your scans and generated PDFs automatically within your Google Drive.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF44474E)
                                    )
                                    Button(
                                        onClick = {
                                            val signInIntent = googleSignInClient.signInIntent
                                            googleSignInLauncher.launch(signInIntent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1)),
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
                                        Column {
                                            Text(
                                                text = "Google Drive Sync ON",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1B1B1F)
                                            )
                                            Text(
                                                text = "Account: $email",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF005AC1),
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

                                    Divider(color = Color(0xFFE1E2E9))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "DESTINATION FOLDER",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF44474E)
                                            )
                                            Text(
                                                text = driveFolderName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF1B1B1F)
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                showFolderDialog = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8DEF8), contentColor = Color(0xFF381E72))
                                        ) {
                                            Text("Change Folder", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                        tint = Color(0xFF005AC1),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "Portal Filename Setup",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B1B1F)
                                    )
                                }

                                Text(
                                    text = "Choose the naming sequence when preparing direct uploads for state/commercial portals.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF44474E)
                                )

                                Divider(color = Color(0xFFE1E2E9).copy(alpha = 0.5f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { viewModel.updateNameBeforeType(true) }
                                    ) {
                                        RadioButton(
                                            selected = nameBeforeType,
                                            onClick = { viewModel.updateNameBeforeType(true) }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Name_Type (e.g., Subhojit_PAN)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { viewModel.updateNameBeforeType(false) }
                                    ) {
                                        RadioButton(
                                            selected = !nameBeforeType,
                                            onClick = { viewModel.updateNameBeforeType(false) }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Type_Name (e.g., PAN_Subhojit)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                    color = Color(0xFF1B1B1F)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Diagnostics Status", color = Color(0xFF44474E))
                                    Text(
                                        text = if (googleEmail != null) "SUCCESS" else "CONNECTED PENDING",
                                        fontWeight = FontWeight.Bold,
                                        color = if (googleEmail != null) Color(0xFF4CAF50) else Color(0xFF44474E)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Connected Email", color = Color(0xFF44474E))
                                    Text(
                                        text = googleEmail ?: "None",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B1B1F)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Active Folder ID", color = Color(0xFF44474E))
                                    Text(
                                        text = driveFolderId,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B1B1F)
                                    )
                                }
                            }
                        }

                        // Local Data Defense & Wipe Panel
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                        tint = Color(0xFF005AC1),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = "Professional Data Defense",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B1B1F)
                                    )
                                }

                                Text(
                                    text = "Cyber cafes routinely handle sensitive identity documents (Aadhaar, government IDs, passbooks). Modern portal uploads pose a massive privacy risk if left on shared machines.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF44474E)
                                )

                                Divider(color = Color(0xFFE1E2E9))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF005AC1))
                                    )
                                    Text(
                                        text = "Local Cache Isolation: PDFs are stored in private internal directories.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF44474E)
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
                                            .background(Color(0xFF005AC1))
                                    )
                                    Text(
                                        text = "Secure Direct Pipe: Scans pipe straight into your Google Drive, bypassing public servers.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF44474E)
                                    )
                                }
                            }
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFDAD9)),
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
                                    Text(
                                        text = "Secure Instant Cache Wipe",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF410002)
                                    )
                                }

                                Text(
                                    text = "Wipe all scanned items, local caches, metadata caches, and Room state logs instantly from this machine.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF410002)
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
                        focusedTextColor = Color(0xFF1D192B),
                        unfocusedTextColor = Color(0xFF1D192B),
                        focusedLabelColor = Color(0xFF005AC1),
                        unfocusedLabelColor = Color(0xFF44474E),
                        focusedBorderColor = Color(0xFF005AC1),
                        unfocusedBorderColor = Color(0xFF74777F),
                        cursorColor = Color(0xFF005AC1)
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

    if (pendingDoc != null) {
        val doc = pendingDoc!!
        var tempPersonName by remember(doc) { mutableStateOf(doc.initialPersonName) }
        var tempDocumentType by remember(doc) { mutableStateOf(doc.initialDocumentType) }
        var tempUploadFormat by remember { mutableStateOf(UploadFormat.JPEG) }

        val presets = remember { listOf("Aadhaar Card", "PAN Card", "Voter ID", "Passport", "Driving License", "Marksheet", "Ration Card") }

        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingDocument() },
            title = {
                Text(
                    text = "Confirm Document Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B1B1F)
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
                        color = Color(0xFF44474E)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF3F4F9), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(doc.compressedFile),
                            contentDescription = "Analysis Preview",
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "COMPRESSED RESOLUTION",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF44474E)
                            )
                            Text(
                                "Ready within target size",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF005AC1)
                            )
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
                                        .background(Color(0xFF005AC1).copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "Auto Name",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF005AC1),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF1D192B),
                            unfocusedTextColor = Color(0xFF1D192B),
                            focusedLabelColor = Color(0xFF005AC1),
                            unfocusedLabelColor = Color(0xFF44474E),
                            focusedPlaceholderColor = Color(0xFF74777F),
                            unfocusedPlaceholderColor = Color(0xFF74777F),
                            focusedBorderColor = Color(0xFF005AC1),
                            unfocusedBorderColor = Color(0xFF74777F),
                            cursorColor = Color(0xFF005AC1)
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
                            focusedTextColor = Color(0xFF1D192B),
                            unfocusedTextColor = Color(0xFF1D192B),
                            focusedLabelColor = Color(0xFF005AC1),
                            unfocusedLabelColor = Color(0xFF44474E),
                            focusedPlaceholderColor = Color(0xFF74777F),
                            unfocusedPlaceholderColor = Color(0xFF74777F),
                            focusedBorderColor = Color(0xFF005AC1),
                            unfocusedBorderColor = Color(0xFF74777F),
                            cursorColor = Color(0xFF005AC1)
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Upload Format",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF44474E)
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
                        color = Color(0xFF44474E)
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
                                color = if (isSelected) Color(0xFFE8DEF8) else Color(0xFFF3F4F9),
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E2E9)),
                                modifier = Modifier.clickable { tempDocumentType = preset }
                            ) {
                                Text(
                                    text = preset,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFF381E72) else Color(0xFF44474E)
                                )
                            }
                        }
                    }

                    // Proposed name preview
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DEF8).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "PORTAL FILENAME",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF49454F)
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
                                color = Color(0xFF005AC1)
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1))
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
                        color = Color(0xFF44474E)
                    )

                    // Google Security Notice Card (Removed)

// Removed debug diagnostics button

                    if (folderError != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFDAD9)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "⚠️ Google API Notice / Error:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF410002),
                                    fontWeight = FontWeight.Bold
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = folderError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF601A18)
                                )
                            }
                        }
                    }

                    // Breadcrumb Display
                    val crumbText = folderPathStack.joinToString(" / ") { it.name }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F9)),
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
                                color = Color(0xFF005AC1)
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1E2E9), contentColor = Color(0xFF1B1B1F)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("← Go Up", fontWeight = FontWeight.Bold)
                        }

                        // Create folder button
                        Button(
                            onClick = { showNewFolderInput = !showNewFolderInput },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8DEF8), contentColor = Color(0xFF381E72)),
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
                                    focusedTextColor = Color(0xFF1D192B),
                                    unfocusedTextColor = Color(0xFF1D192B),
                                    focusedPlaceholderColor = Color(0xFF74777F),
                                    unfocusedPlaceholderColor = Color(0xFF74777F),
                                    focusedBorderColor = Color(0xFF005AC1),
                                    unfocusedBorderColor = Color(0xFF74777F),
                                    cursorColor = Color(0xFF005AC1)
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1))
                            ) {
                                Text("Create")
                            }
                        }
                    }

                    Divider(color = Color(0xFFE1E2E9))

                    // Folders list
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Folders Inside Directory:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF44474E)
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
                                tint = Color(0xFF44474E)
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
                            focusedTextColor = Color(0xFF1D192B),
                            unfocusedTextColor = Color(0xFF1D192B),
                            focusedPlaceholderColor = Color(0xFF74777F),
                            unfocusedPlaceholderColor = Color(0xFF74777F),
                            focusedBorderColor = Color(0xFF005AC1),
                            unfocusedBorderColor = Color(0xFF74777F),
                            cursorColor = Color(0xFF005AC1)
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
                            CircularProgressIndicator(color = Color(0xFF005AC1))
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
                                color = Color(0xFF44474E)
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
                                        color = Color(0xFF1B1B1F)
                                    )
                                }
                                Divider(color = Color(0xFFF3F4F9))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.selectCurrentAsDestination()
                        showFolderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1))
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
                    color = Color(0xFF1B1B1F)
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(File(doc.localFilePath)),
                        contentDescription = "Full Document",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF3F4F9)),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "File localFilePath: ${doc.localFilePath}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF005AC1)
                    )

                    Divider(color = Color(0xFFF3F4F9))

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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005AC1))
                ) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun DocumentItem(doc: DocumentEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(File(doc.localFilePath)),
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
                    color = Color(0xFF005AC1)
                )
                Text(
                    text = "📱 Local: ${doc.localFilePath}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF44474E)
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
        colors = CardDefaults.cardColors(containerColor = Color.White),
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
                    color = Color(0xFF1B1B1F)
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
                            .background(Color(0xFF005AC1))
                    )
                    Text(
                        text = "Folder Section: ${file.folderName ?: "My Drive"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF005AC1)
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
                        color = Color(0xFF44474E)
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF74777F)
                    )
                    Text(
                        text = if (file.mimeType.contains("/")) file.mimeType.substringAfter("/").uppercase() else "FILE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF74777F)
                    )
                }
            }
            IconButton(
                onClick = onOpenClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF3F4F9))
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "View File",
                    tint = Color(0xFF005AC1),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
