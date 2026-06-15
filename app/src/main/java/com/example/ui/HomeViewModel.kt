package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.AppDatabase
import com.example.data.DocumentEntity
import com.example.data.ImageProcessor
import com.example.data.SettingsRepository
import com.example.network.Content
import com.example.network.DocumentAnalysisResult
import com.example.network.GeminiRetrofitClient
import com.example.network.GenerateContentRequest
import com.example.network.GenerationConfig
import com.example.network.InlineData
import com.example.network.Part
import com.example.network.GoogleDriveClient
import com.example.network.GoogleDriveFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class UploadFormat {
    JPEG,
    PDF,
    BOTH
}

data class PendingDocument(
    val compressedFile: File,
    val initialPersonName: String,
    val initialDocumentType: String
)

class HomeViewModel(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val context: Context
) : ViewModel() {

    val documents = database.documentDao().getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val targetSizeKb = settingsRepository.targetSizeKb
        .stateIn(viewModelScope, SharingStarted.Lazily, 500)

    val enableAiAnalysis = settingsRepository.enableAiAnalysis
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val showConfirmation = settingsRepository.showConfirmation
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val googleEmail = settingsRepository.googleEmail
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val driveFolderId = settingsRepository.driveFolderId
        .stateIn(viewModelScope, SharingStarted.Lazily, "root")

    val driveFolderName = settingsRepository.driveFolderName
        .stateIn(viewModelScope, SharingStarted.Lazily, "My Drive")

    private val _targetSubfolder = MutableStateFlow("")
    val targetSubfolder = _targetSubfolder.asStateFlow()

    fun setTargetSubfolder(name: String) {
        _targetSubfolder.value = name
    }

    // Scan Mode selection: standard pages vs A4 2-sided ID card merge
    enum class ScanMode {
        STANDARD,
        A4_ID_MERGE
    }

    private val _scanMode = MutableStateFlow(ScanMode.A4_ID_MERGE)
    val scanMode = _scanMode.asStateFlow()

    fun setScanMode(mode: ScanMode) {
        _scanMode.value = mode
    }

    // Active background processing/upload tasks queue
    data class QueueItem(
        val id: String,
        val personName: String,
        val documentType: String,
        val format: UploadFormat,
        val status: String, // "Analyzing (NVIDIA AI)...", "Uploading PDF/JPEG...", "Completed", "Failed"
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _activeQueue = MutableStateFlow<List<QueueItem>>(emptyList())
    val activeQueue = _activeQueue.asStateFlow()

    fun clearActiveQueue() {
        _activeQueue.value = _activeQueue.value.filter { 
            !it.status.contains("Completed") && !it.status.contains("locally") && !it.status.contains("Failed") 
        }
    }

    val nameBeforeType = settingsRepository.nameBeforeType
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    fun updateNameBeforeType(nameBefore: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNameBeforeType(nameBefore)
        }
    }

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage = _statusMessage.asStateFlow()

    private val _pendingDocument = MutableStateFlow<PendingDocument?>(null)
    val pendingDocument = _pendingDocument.asStateFlow()

    // Google Drive folder selection session details
    private val _currentFolderId = MutableStateFlow("root")
    val currentFolderId = _currentFolderId.asStateFlow()

    private val _currentFolderName = MutableStateFlow("My Drive")
    val currentFolderName = _currentFolderName.asStateFlow()

    private val _folderPathStack = MutableStateFlow<List<GoogleDriveFolder>>(listOf(GoogleDriveFolder("root", "My Drive")))
    val folderPathStack = _folderPathStack.asStateFlow()

    private val _subfolders = MutableStateFlow<List<GoogleDriveFolder>>(emptyList())
    val subfolders = _subfolders.asStateFlow()

    private val _isFolderLoading = MutableStateFlow(false)
    val isFolderLoading = _isFolderLoading.asStateFlow()

    private val _folderError = MutableStateFlow<String?>(null)
    val folderError = _folderError.asStateFlow()

    fun clearFolderError() {
        _folderError.value = null
    }

    private val _recoveryIntent = MutableStateFlow<android.content.Intent?>(null)
    val recoveryIntent = _recoveryIntent.asStateFlow()

    fun clearRecoveryIntent() {
        _recoveryIntent.value = null
    }

    private val _driveFiles = MutableStateFlow<List<com.example.network.DriveFile>>(emptyList())
    val driveFiles = _driveFiles.asStateFlow()

    private val _isDriveFilesLoading = MutableStateFlow(false)
    val isDriveFilesLoading = _isDriveFilesLoading.asStateFlow()

    private val _driveFilesError = MutableStateFlow<String?>(null)
    val driveFilesError = _driveFilesError.asStateFlow()

    fun fetchDriveFiles(context: Context) {
        val email = googleEmail.value
        if (email.isNullOrEmpty()) {
            _driveFiles.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isDriveFilesLoading.value = true
            _driveFilesError.value = null
            try {
                val obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val rootFolderId = driveFolderId.value
                    val rootNameStr = driveFolderName.value ?: "My Drive"
                    
                    // 1. Fetch direct files from rootFolderId
                    val direct = GoogleDriveClient.listFiles(obtainedToken, rootFolderId)
                    direct.forEach { it.folderName = rootNameStr }
                    
                    // 2. Fetch immediate subfolders of rootFolderId
                    val subFolderDefs = GoogleDriveClient.listFolders(obtainedToken, rootFolderId)
                    
                    val combined = mutableListOf<com.example.network.DriveFile>()
                    combined.addAll(direct)
                    
                    // 3. For each subfolder, retrieve its files
                    for (folder in subFolderDefs) {
                        try {
                            val subFiles = GoogleDriveClient.listFiles(obtainedToken, folder.id)
                            subFiles.forEach { it.folderName = folder.name }
                            combined.addAll(subFiles)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    
                    val sorted = combined.sortedByDescending { it.createdTime ?: "" }
                    _driveFiles.value = sorted
                } else {
                    _driveFilesError.value = "Failed to obtain credentials"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _driveFilesError.value = e.localizedMessage ?: e.message
            } finally {
                _isDriveFilesLoading.value = false
            }
        }
    }

    fun updateTargetSize(sizeKb: Int) {
        viewModelScope.launch {
            settingsRepository.setTargetSizeKb(sizeKb)
        }
    }

    fun updateEnableAiAnalysis(enable: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEnableAiAnalysis(enable)
        }
    }

    fun updateShowConfirmation(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowConfirmation(show)
        }
    }

    fun cancelPendingDocument() {
        try { _pendingDocument.value?.compressedFile?.delete() } catch (e: Exception) {}
        _pendingDocument.value = null
    }

    fun setGoogleEmail(email: String?) {
        viewModelScope.launch {
            settingsRepository.setGoogleEmail(email)
            if (email == null) {
                // reset folder settings
                settingsRepository.setDriveFolder("root", "My Drive")
                _currentFolderId.value = "root"
                _currentFolderName.value = "My Drive"
                _folderPathStack.value = listOf(GoogleDriveFolder("root", "My Drive"))
                _subfolders.value = emptyList()
            }
        }
    }

    fun fetchSubfolders(context: Context, folderId: String = _currentFolderId.value) {
        val email = googleEmail.value ?: return
        viewModelScope.launch {
            _isFolderLoading.value = true
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val list = GoogleDriveClient.listFolders(obtainedToken, folderId)
                    _subfolders.value = list
                    _folderError.value = null
                } else {
                    _folderError.value = "Failed to retrieve access token"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderError.value = e.localizedMessage ?: e.message
                obtainedToken?.let { invalidateCachedToken(context, it) }
            } finally {
                _isFolderLoading.value = false
            }
        }
    }

    fun navigateToFolder(context: Context, id: String, name: String) {
        val email = googleEmail.value ?: return
        viewModelScope.launch {
            _isFolderLoading.value = true
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val currentStack = _folderPathStack.value.toMutableList()
                    val target = GoogleDriveFolder(id, name)
                    currentStack.add(target)
                    _folderPathStack.value = currentStack
                    _currentFolderId.value = id
                    _currentFolderName.value = name
                    val list = GoogleDriveClient.listFolders(obtainedToken, id)
                    _subfolders.value = list
                    _folderError.value = null
                } else {
                    _folderError.value = "Failed to retrieve access token"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderError.value = e.localizedMessage ?: e.message
                obtainedToken?.let { invalidateCachedToken(context, it) }
            } finally {
                _isFolderLoading.value = false
            }
        }
    }

    fun navigateUp(context: Context) {
        val email = googleEmail.value ?: return
        viewModelScope.launch {
            _isFolderLoading.value = true
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val currentStack = _folderPathStack.value.toMutableList()
                    if (currentStack.size > 1) {
                        currentStack.removeAt(currentStack.size - 1)
                        val parent = currentStack.last()
                        _folderPathStack.value = currentStack
                        _currentFolderId.value = parent.id
                        _currentFolderName.value = parent.name
                        val list = GoogleDriveClient.listFolders(obtainedToken, parent.id)
                        _subfolders.value = list
                        _folderError.value = null
                    }
                } else {
                    _folderError.value = "Failed to retrieve access token"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderError.value = e.localizedMessage ?: e.message
                obtainedToken?.let { invalidateCachedToken(context, it) }
            } finally {
                _isFolderLoading.value = false
            }
        }
    }

    fun createNewSubfolder(context: Context, name: String, onResult: (Boolean) -> Unit) {
        val email = googleEmail.value ?: return
        viewModelScope.launch {
            _isFolderLoading.value = true
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val newId = GoogleDriveClient.createFolder(obtainedToken, name, _currentFolderId.value)
                    if (newId != null) {
                        val list = GoogleDriveClient.listFolders(obtainedToken, _currentFolderId.value)
                        _subfolders.value = list
                        _folderError.value = null
                        onResult(true)
                    } else {
                        _folderError.value = "Failed to create folder on Google Drive"
                        onResult(false)
                    }
                } else {
                    _folderError.value = "Failed to retrieve access token"
                    onResult(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderError.value = e.localizedMessage ?: e.message
                obtainedToken?.let { invalidateCachedToken(context, it) }
                onResult(false)
            } finally {
                _isFolderLoading.value = false
            }
        }
    }

    fun selectCurrentAsDestination() {
        viewModelScope.launch {
            settingsRepository.setDriveFolder(_currentFolderId.value, _currentFolderName.value)
        }
    }

    // Resolves standard authorization token asynchronously
    fun testDriveApi(context: Context, email: String?) {
        if (email == null) {
            _folderError.value = "No Google account selected."
            return
        }
        viewModelScope.launch {
            _isFolderLoading.value = true
            _folderError.value = "Testing token fetch..."
            var token: String? = null
            try {
                token = getAccessToken(context, email)
                if (token == null) {
                    _folderError.value = "Failed to fetch token, but no exception was thrown (returned null)."
                    return@launch
                }
                
                _folderError.value = "Token fetched! Testing API list call..."
                val folders = GoogleDriveClient.listFolders(token, "root")
                _folderError.value = "SUCCESS! Found ${folders.size} root folders. Token length: ${token.length}"
                
            } catch (e: Exception) {
                e.printStackTrace()
                _folderError.value = "Test Failed!\nException Layer 1:\n=== ${e.javaClass.simpleName}: ${e.message}\nCause:\n=== ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}"
                token?.let { invalidateCachedToken(context, it) }
            } finally {
                _isFolderLoading.value = false
            }
        }
    }

    suspend fun getAccessToken(context: Context, email: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val scope = "oauth2:https://www.googleapis.com/auth/drive"
                com.google.android.gms.auth.GoogleAuthUtil.getToken(context, email, scope)
            } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
                e.printStackTrace()
                _recoveryIntent.value = e.intent
                null
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    fun invalidateCachedToken(context: Context, token: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, token)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun processScannedImages(imageUris: List<Uri>, pdfUri: Uri?) {
        if (imageUris.isEmpty()) return
        
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                // 1. Resolve paths
                _statusMessage.value = "Combining images..."
                val paths = imageUris.mapIndexed { index, uri ->
                    val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}_$index.jpeg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.absolutePath
                }

                // 2. Combine images (respecting A4 2-sided scan mode)
                val combinedFile = File(context.cacheDir, "combined_${System.currentTimeMillis()}.jpeg")
                val resultFile = if (_scanMode.value == ScanMode.A4_ID_MERGE) {
                    ImageProcessor.combineImagesToA4(paths, combinedFile)
                } else {
                    ImageProcessor.combineImages(paths, combinedFile)
                }
                if (resultFile == null) {
                    _statusMessage.value = "Failed to combine images"
                    _isProcessing.value = false
                    return@launch
                }

                // 3. Compress
                _statusMessage.value = "Compressing to ${targetSizeKb.value}KB..."
                val compressedFile = ImageProcessor.compressImage(resultFile, targetSizeKb.value)

                // High efficiency cache cleanup: delete the original separate page images and the uncompressed raw combined image
                paths.forEach { path ->
                    try { File(path).delete() } catch (e: Exception) {}
                }
                try { resultFile.delete() } catch (e: Exception) {}

                if (enableAiAnalysis.value) {
                    if (showConfirmation.value) {
                        // Cloud AI Mode WITH confirmation screen:
                        _statusMessage.value = "Analyzing with AI..."
                        val analysis = try {
                            val base64Image = encodeFileToBase64(compressedFile)
                            analyzeDocumentWithNvidia(base64Image)
                        } catch (e: Exception) {
                            null
                        }
                        
                        val checkedPersonName = sanitizePersonName(analysis?.personName ?: "Unknown")
                        val checkedDocumentType = sanitizeDocumentType(analysis?.documentType ?: "Document", "")
                        _statusMessage.value = "AI Analysis complete. Please confirm."
                        _pendingDocument.value = PendingDocument(
                            compressedFile = compressedFile,
                            initialPersonName = checkedPersonName,
                            initialDocumentType = checkedDocumentType
                        )
                    } else {
                        // Cloud AI Mode WITHOUT confirmation screen (Instant background auto-save):
                        _statusMessage.value = "Queued for AI background analysis..."
                        val queueId = java.util.UUID.randomUUID().toString()
                        val format = UploadFormat.PDF
                        val initialItem = QueueItem(
                            id = queueId,
                            personName = "Analyzing AI...",
                            documentType = "Document",
                            format = format,
                            status = "Analyzing with AI..."
                        )
                        _activeQueue.value = _activeQueue.value + initialItem

                        _isProcessing.value = false // Release camera immediately

                        viewModelScope.launch {
                            try {
                                val base64Image = encodeFileToBase64(compressedFile)
                                val analysis = analyzeDocumentWithNvidia(base64Image)
                                
                                val checkedPersonName = sanitizePersonName(analysis?.personName ?: "Unknown")
                                val checkedDocumentType = sanitizeDocumentType(analysis?.documentType ?: "Document", "")
                                
                                _activeQueue.value = _activeQueue.value.map {
                                    if (it.id == queueId) it.copy(personName = checkedPersonName, documentType = checkedDocumentType) else it
                                }
                                executeBackgroundUpload(context, queueId, compressedFile, checkedPersonName, checkedDocumentType, format)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                updateQueueStatus(queueId, "Failed: AI Analysis Error")
                                try { compressedFile.delete() } catch (ex: Exception) {}
                            }
                        }
                        return@launch
                    }
                } else {
                    // Local OCR Mode:
                    _statusMessage.value = "Running on-device local text recognition..."
                    var personName = "Unknown"
                    var documentType = "Document"
                    try {
                        val localAnalysis = analyzeDocumentLocally(compressedFile)
                        personName = sanitizePersonName(localAnalysis.personName)
                        documentType = sanitizeDocumentType(localAnalysis.documentType)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (showConfirmation.value) {
                        _statusMessage.value = "Processing complete. Please confirm document details."
                        _pendingDocument.value = PendingDocument(
                            compressedFile = compressedFile,
                            initialPersonName = personName,
                            initialDocumentType = documentType
                        )
                    } else {
                        // Local OCR WITHOUT confirmation screen (Instant direct upload):
                        _statusMessage.value = "Uploading and saving automatically..."
                        val queueId = java.util.UUID.randomUUID().toString()
                        val format = UploadFormat.PDF
                        val initialItem = QueueItem(
                            id = queueId,
                            personName = personName,
                            documentType = documentType,
                            format = format,
                            status = "Saving automatically..."
                        )
                        _activeQueue.value = _activeQueue.value + initialItem
                        _isProcessing.value = false
                        
                        viewModelScope.launch {
                            executeBackgroundUpload(context, queueId, compressedFile, personName, documentType, format)
                        }
                        return@launch
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Error: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun confirmAndUpload(context: Context, personName: String, documentType: String, format: UploadFormat) {
        val pending = _pendingDocument.value ?: return
        _pendingDocument.value = null
        
        val checkedPersonName = if (personName.trim().isEmpty()) {
            "Client_${System.currentTimeMillis() % 100000}"
        } else {
            personName.trim()
        }
        val checkedDocumentType = if (documentType.trim().isEmpty()) {
            "Document"
        } else {
            documentType.trim()
        }

        viewModelScope.launch {
            _isProcessing.value = true
            _statusMessage.value = "Requesting Google authorization..."
            
            val email = settingsRepository.googleEmail.first()
            val folderId = settingsRepository.driveFolderId.first()

            if (email.isNullOrEmpty()) {
                _statusMessage.value = "Error: Please sign in with Google to upload scans to Drive!"
                _isProcessing.value = false
                try { pending.compressedFile.delete() } catch (el: Exception) {}
                return@launch
            }

            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken == null) {
                    _statusMessage.value = "Failed to retrieve Google token. Sign in again."
                    _isProcessing.value = false
                    try { pending.compressedFile.delete() } catch (el: Exception) {}
                    return@launch
                }

                val safePersonName = checkedPersonName.replace(" ", "_").trim()
                val safeDocumentType = checkedDocumentType.replace(" ", "_").trim()
                val finalNameBase = if (nameBeforeType.value) {
                    "${safePersonName}_${safeDocumentType}"
                } else {
                    "${safeDocumentType}_${safePersonName}"
                }
                val finalJpgName = "${finalNameBase}.jpeg"
                val finalPdfName = "${finalNameBase}.pdf"

                var uploadParentId = folderId
                val subFolderName = targetSubfolder.value.trim()
                if (subFolderName.isNotEmpty()) {
                    _statusMessage.value = "Creating/finding target folder: $subFolderName..."
                    val subFolderId = GoogleDriveClient.getOrCreateFolder(obtainedToken, subFolderName, folderId)
                    if (subFolderId != null) {
                        uploadParentId = subFolderId
                    }
                }

                var isJpgUploaded = false
                var isPdfUploaded = false

                if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                    _statusMessage.value = "Uploading compressed image to Google Drive..."
                    isJpgUploaded = GoogleDriveClient.uploadFile(
                        accessToken = obtainedToken,
                        file = pending.compressedFile,
                        mimeType = "image/jpeg",
                        fileName = finalJpgName,
                        parentId = uploadParentId
                    )
                }

                if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                    _statusMessage.value = "Generating and structuring PDF..."
                    val pdfFile = File(context.cacheDir, "${System.currentTimeMillis().hashCode()}_pdf.pdf")
                    try {
                        ImageProcessor.convertToPdf(pending.compressedFile, pdfFile, targetSizeKb.value)

                        _statusMessage.value = "Uploading structured PDF to Google Drive..."
                        isPdfUploaded = GoogleDriveClient.uploadFile(
                            accessToken = obtainedToken,
                            file = pdfFile,
                            mimeType = "application/pdf",
                            fileName = finalPdfName,
                            parentId = uploadParentId
                        )
                    } finally {
                        try { pdfFile.delete() } catch (ep: Exception) {}
                    }
                }

                val overallSuccess = if (format == UploadFormat.BOTH) isJpgUploaded && isPdfUploaded 
                                     else if (format == UploadFormat.JPEG) isJpgUploaded
                                     else isPdfUploaded

                val driveFolderNameStr = settingsRepository.driveFolderName.first() ?: "Root"
                val builtDrivePath = "My Drive/$driveFolderNameStr${if (subFolderName.isNotEmpty()) "/$subFolderName" else ""}"

                // Save to local DB as reference
                val localCopy = File(context.filesDir, finalJpgName)
                pending.compressedFile.copyTo(localCopy, overwrite = true)
                
                database.documentDao().insertDocument(
                    DocumentEntity(
                        fileName = finalJpgName,
                        personName = checkedPersonName,
                        documentType = checkedDocumentType,
                        localFilePath = localCopy.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        isUploaded = overallSuccess,
                        drivePath = if (overallSuccess) builtDrivePath else null
                    )
                )

                fetchDriveFiles(context)

                _statusMessage.value = if (overallSuccess) {
                    "Success! Saved into your Google Drive"
                } else if (isJpgUploaded) {
                    "Uploaded Image to Google Drive, PDF upload failed"
                } else if (isPdfUploaded) {
                    "Uploaded PDF to Google Drive, Image upload failed"
                } else {
                    "Saved locally (Drive Upload Failed)"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Error uploading to Drive: ${e.message}"
                obtainedToken?.let { invalidateCachedToken(context, it) }
            } finally {
                _isProcessing.value = false
                try { pending.compressedFile.delete() } catch (el: Exception) {}
            }
        }
    }

    private suspend fun executeBackgroundUpload(
        context: Context,
        queueId: String,
        compressedFile: File,
        checkedPersonName: String,
        checkedDocumentType: String,
        format: UploadFormat
    ) {
        try {
            updateQueueStatus(queueId, "Authorizing Google Drive...")
            
            val email = settingsRepository.googleEmail.first()
            val folderId = settingsRepository.driveFolderId.first()
            
            if (email.isNullOrEmpty()) {
                updateQueueStatus(queueId, "Failed: Sign-In required")
                return
            }
            
            var obtainedToken = getAccessToken(context, email)
            if (obtainedToken == null) {
                updateQueueStatus(queueId, "Failed: Sign-In required")
                return
            }

            val safePersonName = checkedPersonName.replace(" ", "_").trim()
            val safeDocumentType = checkedDocumentType.replace(" ", "_").trim()
            val finalNameBase = if (nameBeforeType.value) {
                "${safePersonName}_${safeDocumentType}"
            } else {
                "${safeDocumentType}_${safePersonName}"
            }
            val finalJpgName = "${finalNameBase}.jpeg"
            val finalPdfName = "${finalNameBase}.pdf"

            var uploadParentId = folderId
            val subFolderName = targetSubfolder.value.trim()
            if (subFolderName.isNotEmpty()) {
                updateQueueStatus(queueId, "Locating subfolder: $subFolderName...")
                val subFolderId = GoogleDriveClient.getOrCreateFolder(obtainedToken, subFolderName, folderId)
                if (subFolderId != null) {
                    uploadParentId = subFolderId
                }
            }

            var isJpgUploaded = false
            var isPdfUploaded = false

            if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                updateQueueStatus(queueId, "Uploading image standard file...")
                isJpgUploaded = GoogleDriveClient.uploadFile(
                    accessToken = obtainedToken,
                    file = compressedFile,
                    mimeType = "image/jpeg",
                    fileName = finalJpgName,
                    parentId = uploadParentId
                )
            }

            if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                updateQueueStatus(queueId, "Generating structured PDF...")
                val pdfFile = File(context.cacheDir, "${System.currentTimeMillis().hashCode()}_pdf.pdf")
                try {
                    ImageProcessor.convertToPdf(compressedFile, pdfFile, targetSizeKb.value)

                    updateQueueStatus(queueId, "Uploading PDF to Drive...")
                    isPdfUploaded = GoogleDriveClient.uploadFile(
                        accessToken = obtainedToken,
                        file = pdfFile,
                        mimeType = "application/pdf",
                        fileName = finalPdfName,
                        parentId = uploadParentId
                    )
                } finally {
                    try { pdfFile.delete() } catch (ed: Exception) {}
                }
            }

            val overallSuccess = if (format == UploadFormat.BOTH) isJpgUploaded && isPdfUploaded 
                                 else if (format == UploadFormat.JPEG) isJpgUploaded
                                 else isPdfUploaded

            val driveFolderNameStr = settingsRepository.driveFolderName.first() ?: "Root"
            val builtDrivePath = "My Drive/$driveFolderNameStr${if (subFolderName.isNotEmpty()) "/$subFolderName" else ""}"

            // Save locally
            val localCopy = File(context.filesDir, finalJpgName)
            compressedFile.copyTo(localCopy, overwrite = true)
            
            database.documentDao().insertDocument(
                DocumentEntity(
                    fileName = finalJpgName,
                    personName = checkedPersonName,
                    documentType = checkedDocumentType,
                    localFilePath = localCopy.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isUploaded = overallSuccess,
                    drivePath = if (overallSuccess) builtDrivePath else null
                )
            )

            fetchDriveFiles(context)

            updateQueueStatus(queueId, if (overallSuccess) "Completed" else "Saved locally (Drive fail)")
        } catch (e: Exception) {
            e.printStackTrace()
            updateQueueStatus(queueId, "Failed: ${e.message}")
        } finally {
            try { compressedFile.delete() } catch (ex: Exception) {}
        }
    }

    fun uploadInBackground(context: Context, personName: String, documentType: String, format: UploadFormat) {
        val pending = _pendingDocument.value ?: return
        _pendingDocument.value = null // dismiss dialog immediately to allow continued scanning

        val checkedPersonName = if (personName.trim().isEmpty()) {
            "Client_${System.currentTimeMillis() % 100000}"
        } else {
            personName.trim()
        }
        val checkedDocumentType = if (documentType.trim().isEmpty()) {
            "Document"
        } else {
            documentType.trim()
        }

        val queueId = java.util.UUID.randomUUID().toString()

        val initialItem = QueueItem(
            id = queueId,
            personName = checkedPersonName,
            documentType = checkedDocumentType,
            format = format,
            status = "Queued..."
        )
        _activeQueue.value = _activeQueue.value + initialItem

        viewModelScope.launch {
            executeBackgroundUpload(context, queueId, pending.compressedFile, checkedPersonName, checkedDocumentType, format)
        }
    }

    private fun updateQueueStatus(id: String, status: String) {
        _activeQueue.value = _activeQueue.value.map { item ->
            if (item.id == id) item.copy(status = status) else item
        }
    }

    private fun parseDocumentAnalysisResult(content: String): DocumentAnalysisResult? {
        try {
            val format = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
            var parsed: DocumentAnalysisResult? = null
            
            // Try direct parse first
            val jsonStartIndex = content.indexOf("{")
            val jsonEndIndex = content.lastIndexOf("}")
            if (jsonStartIndex != -1 && jsonEndIndex != -1 && jsonEndIndex > jsonStartIndex) {
                val cleanJson = content.substring(jsonStartIndex, jsonEndIndex + 1).trim()
                try {
                    parsed = format.decodeFromString<DocumentAnalysisResult>(cleanJson)
                } catch (inner: Exception) {
                    inner.printStackTrace()
                }
            }
            
            if (parsed == null) {
                // Highly resilient Regex Fallback: parses correct fields even from malformed/embedded JSON outputs
                val nameRegex = """"(?:personName|name)"\s*:\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
                val typeRegex = """"(?:documentType|type)"\s*:\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
                
                val nameMatch = nameRegex.find(content)?.groups?.get(1)?.value
                val typeMatch = typeRegex.find(content)?.groups?.get(1)?.value
                
                if (nameMatch != null || typeMatch != null) {
                    parsed = DocumentAnalysisResult(
                        personName = nameMatch?.trim() ?: "Unknown",
                        documentType = typeMatch?.trim() ?: "Document"
                    )
                }
            }

            if (parsed != null) {
                val sanName = sanitizePersonName(parsed.personName)
                val sanType = sanitizeDocumentType(parsed.documentType, content)
                return DocumentAnalysisResult(personName = sanName, documentType = sanType)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun analyzeDocumentWithNvidia(base64Image: String): DocumentAnalysisResult? {
        val apiKey = "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"
        
        val prompt = """
            You are an elite, industrial-grade document scanner & OCR specialist.
            Your task is to analyze the document image with 100% precision.
            1. Extract the holder's / primary person's full name. Look under fields like "Name", "Full Name", "नाम", "Name of Holder", etc. Crucial: Do NOT select field labels, parents' names, or address lines.
            2. Extract the document type (e.g., Aadhaar Card, PAN Card, Passport, Voter ID, Driving License, Marksheet, Ration Card).
            
            Return ONLY a valid JSON object matching this schema (do NOT include any conversational text or markdown blocks):
            {
                "personName": "Meticulously Extracted Name",
                "documentType": "Meticulously Extracted Document Type"
            }
        """.trimIndent()

        val nvidiaModels = listOf(
            "nvidia/llama-3.2-90b-vision-instruct",
            "meta/llama-3.2-90b-vision-instruct",
            "nvidia/llama-3.2-11b-vision-instruct",
            "meta/llama-3.2-11b-vision-instruct"
        )

        for (modelName in nvidiaModels) {
            val request = com.example.network.NvidiaChatRequest(
                model = modelName,
                messages = listOf(
                    com.example.network.NvidiaMessage(
                        role = "user",
                        content = listOf(
                            com.example.network.NvidiaContent(type = "text", text = prompt),
                            com.example.network.NvidiaContent(
                                type = "image_url",
                                image_url = com.example.network.NvidiaImageUrl(url = "data:image/jpeg;base64,$base64Image")
                            )
                        )
                    )
                ),
                temperature = 0.1f
            )

            try {
                val authHeader = "Bearer $apiKey"
                val response = com.example.network.NvidiaRetrofitClient.service.getChatCompletion(authHeader, request)
                val content = response.choices?.firstOrNull()?.message?.content
                if (content != null) {
                    val parsed = parseDocumentAnalysisResult(content)
                    if (parsed != null) {
                        return parsed
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Log and try the next configured NVIDIA NIM model
            }
        }

        // Fall back to Gemini API if all Nvidia models failed
        return analyzeDocument(base64Image)
    }

    private suspend fun analyzeDocument(base64Image: String): DocumentAnalysisResult? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") return null

        val prompt = """
            Analyze this document image. Identify the type of document (e.g., Aadhaar, PAN Card, Passport, Bill) and the primary person's name on it.
            Return a JSON object strictly matching this schema:
            {
                "personName": "Extracted Name",
                "documentType": "Extracted Document Type"
            }
        """.trimIndent()

        val jsonSchema = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("personName", buildJsonObject { put("type", "STRING") })
                put("documentType", buildJsonObject { put("type", "STRING") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { 
                add(kotlinx.serialization.json.JsonPrimitive("personName"))
                add(kotlinx.serialization.json.JsonPrimitive("documentType"))
            })
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData("image/jpeg", base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.1f // low temp for deterministic output
            )
        )

        val geminiModels = listOf(
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-3.5-flash"
        )

        for (model in geminiModels) {
            try {
                val response = GeminiRetrofitClient.service.generateContent(model, apiKey, request)
                val textResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (textResponse != null) {
                    val parsed = parseDocumentAnalysisResult(textResponse)
                    if (parsed != null) {
                        return parsed
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Log and try next Gemini fallback model (e.g. flash -> pro -> experimentals)
            }
        }
        return null
    }

    private fun encodeFileToBase64(file: File): String {
        val bytes = file.readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun analyzeDocumentLocally(imageFile: File): DocumentAnalysisResult = suspendCoroutine { continuation ->
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(imageFile))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    val result = extractLocalDetails(fullText)
                    try { recognizer.close() } catch (ex: Exception) {}
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                    try { recognizer.close() } catch (ex: Exception) {}
                    continuation.resume(DocumentAnalysisResult("Unknown", "Document"))
                }
        } catch (e: Exception) {
            e.printStackTrace()
            continuation.resume(DocumentAnalysisResult("Unknown", "Document"))
        }
    }

    private fun extractLocalDetails(fullText: String): DocumentAnalysisResult {
        var personName = "Unknown"
        var documentType = "Document"
        
        val lowercaseText = fullText.lowercase()
        
        // A. Smart detection of Aadhaar Card via 12-digit UID spacing patterns AND language/institution checks
        val uidPattern = Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b")
        val isAadhaarKeyword = lowercaseText.contains("aadhaar") || 
                              lowercaseText.contains("aadhar") || 
                              lowercaseText.contains("adhar") || 
                              lowercaseText.contains("unique identification") ||
                              lowercaseText.contains("enrollment ") ||
                              lowercaseText.contains("yob:") || 
                              lowercaseText.contains("dob:") ||
                              lowercaseText.contains("male") ||
                              lowercaseText.contains("female") ||
                              lowercaseText.contains("आधार")
                              
        if (uidPattern.containsMatchIn(fullText) || isAadhaarKeyword) {
            documentType = "Aadhaar Card"
        } 
        // B. Smart detection of PAN Card via alphanumeric Permanent Account pattern AND tax keywords
        else if (lowercaseText.contains("permanent account") || 
                 lowercaseText.contains("tax department") || 
                 lowercaseText.contains("income tax") ||
                 Regex("[a-zA-Z]{5}[0-9]{4}[a-zA-Z]").containsMatchIn(fullText)) {
            documentType = "PAN Card"
        } 
        // C. Smart detection of Driving License
        else if (lowercaseText.contains("driving") || 
                 lowercaseText.contains("licence") || 
                 lowercaseText.contains("license") || 
                 lowercaseText.contains("dl no") || 
                 lowercaseText.contains("transport department") ||
                 Regex("\\b[A-Za-Z]{2}[- ]?[0-9]{2}").containsMatchIn(fullText)) {
            documentType = "Driving License"
        } 
        // D. Smart detection of Voter ID Card
        else if (lowercaseText.contains("voter") || 
                 lowercaseText.contains("election") || 
                 lowercaseText.contains("commission of india") || 
                 lowercaseText.contains("elector photo") || 
                 lowercaseText.contains("epic") ||
                 Regex("[a-zA-Z]{3}[0-9]{7}").containsMatchIn(fullText)) {
            documentType = "Voter ID"
        } 
        // E. Smart detection of Passport
        else if (lowercaseText.contains("passport") || 
                 lowercaseText.contains("republic of india") || 
                 lowercaseText.contains("paspot") ||
                 Regex("\\b[a-zA-Z][0-9]{7}\\b").containsMatchIn(fullText)) {
            documentType = "Passport"
        } 
        // F. Academy Marksheet or certificates
        else if (lowercaseText.contains("marksheet") || 
                 lowercaseText.contains("mark sheet") || 
                 lowercaseText.contains("school certificate") || 
                 lowercaseText.contains("board of school") || 
                 lowercaseText.contains("marks statement") || 
                 lowercaseText.contains("roll no") || 
                 lowercaseText.contains("examination")) {
            documentType = "Marksheet"
        } 
        // G. Ration Card
        else if (lowercaseText.contains("ration card") || lowercaseText.contains("ration")) {
            documentType = "Ration Card"
        }
        
        // 2. Exact on-device name extraction logic
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val nameIndicators = listOf("name", "full name", "नाम", "holder name", "name of holder", "card holder")
        var foundName = ""
        
        for (i in lines.indices) {
            val lineLower = lines[i].lowercase()
            for (ind in nameIndicators) {
                if (lineLower.startsWith(ind) || lineLower.contains("$ind:") || lineLower.contains("$ind :") || lineLower.contains("$ind=")) {
                    var afterInd = ""
                    val idx = lines[i].lowercase().indexOf(ind)
                    if (idx != -1) {
                        afterInd = lines[i].substring(idx + ind.length).trim(':', '-', ' ', '=', '।', '/')
                    }
                    if (afterInd.length >= 3 && isValidName(afterInd)) {
                        foundName = afterInd
                        break
                    } else if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1].trim()
                        if (nextLine.length >= 3 && isValidName(nextLine) && !isBlacklistedLine(nextLine)) {
                            foundName = nextLine
                            break
                        }
                    }
                }
            }
            if (foundName.isNotEmpty()) break
        }
        
        // Fallback: If indicator not found, let's scan for uppercase lines of length 2-4 words which looks like a person's name
        if (foundName.isEmpty()) {
            for (line in lines) {
                val cleaned = line.replace(Regex("[^a-zA-Z\\s]"), "").trim()
                val words = cleaned.split("\\s+".toRegex())
                if (words.size in 2..4 && cleaned.uppercase() == cleaned && cleaned.length >= 5) {
                    if (!isBlacklistedLine(cleaned) && isValidName(line)) {
                        foundName = line
                        break
                    }
                }
            }
        }
        
        if (foundName.isNotEmpty()) {
            personName = foundName.replace(Regex("[^a-zA-Z\\s]"), "").trim()
            personName = personName.split("\\s+".toRegex()).joinToString(" ") { 
                it.lowercase().replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } 
            }
        }
        
        val sanName = sanitizePersonName(personName)
        val sanType = sanitizeDocumentType(documentType, fullText)
        return DocumentAnalysisResult(personName = sanName, documentType = sanType)
    }

    private fun sanitizePersonName(detectedName: String): String {
        val nameLower = detectedName.trim().lowercase()
        if (nameLower.isEmpty() || nameLower == "unknown") {
            return "Unknown"
        }

        // Blacklisted exact/substring labels
        val blacklist = listOf(
            "card holder", "cardholder", "holder name", "name of holder", "head of family", "father's name", "father name", "father", "mother", "mother's name", "husband", "spouse", "parent", 
            "voter id", "aadhaar", "aadhar", "pan card", "passport", "driving license", "driving licence", "marksheet", "ration card", 
            "signature", "photo", "thumb impression", "impression", "relation", "relationship", "address", "government", "government of india", 
            "income tax", "department", "election commission", "male", "female", "transgender", "yob:", "dob:", "date of birth", "place of birth", 
            "authority", "uidai", "epic", "serial no", "card no", "unique", "identification", "republic of india", "resident of", "to permanent", 
            "permanent account", "tax department", "transport department", "school board", "elector photo", "marks statement", "roll no", "examination"
        )

        for (black in blacklist) {
            if (nameLower == black || nameLower.startsWith(black) || (nameLower.length <= black.length + 4 && nameLower.contains(black))) {
                return "Unknown"
            }
        }

        // Clean up typical labels prefixing the name if any (e.g. "Name : John Doe" -> "John Doe")
        var cleaned = detectedName.trim()
        val prefixes = listOf("name:", "name of holder:", "card holder:", "holder:", "full name:", "नाम:", "card holder name:", "cardholder name:")
        for (pref in prefixes) {
            if (cleaned.lowercase().startsWith(pref)) {
                cleaned = cleaned.substring(pref.length).trim(':', '-', ' ', '=', '।', '/')
            }
        }

        // Additional check: if the name is too long or contains lines, or digits, or symbols, it's not a valid name
        if (cleaned.any { it.isDigit() }) return "Unknown"
        val invalidChars = listOf('@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '=', '[', ']', '{', '}', ';', ':', '"', '<', '>', '/', '\\', '|')
        if (cleaned.any { it in invalidChars }) return "Unknown"
        
        val words = cleaned.split("\\s+".toRegex())
        if (words.size > 5) return "Unknown" // Person names rarely exceed 5 words

        return cleaned
    }

    private fun sanitizeDocumentType(detectedType: String, keywordText: String = ""): String {
        val typeLower = detectedType.trim().lowercase()
        val textLower = keywordText.lowercase()

        // 1. Explicitly check content keywords first to enforce high-accuracy fallback correction
        if (typeLower.contains("aadhaar") || typeLower.contains("aadhar") || textLower.contains("aadhaar") || textLower.contains("aadhar") || textLower.contains("unique identification") || textLower.contains("enrollment")) {
            return "Aadhaar Card"
        }
        if (typeLower.contains("pan") || typeLower.contains("permanent account") || textLower.contains("permanent account") || textLower.contains("income tax")) {
            return "PAN Card"
        }
        if (typeLower.contains("passport") || textLower.contains("passport")) {
            return "Passport"
        }
        if (typeLower.contains("driving") || typeLower.contains("license") || typeLower.contains("licence") || textLower.contains("driving licence") || textLower.contains("driving license") || textLower.contains("dl no")) {
            return "Driving License"
        }
        if (typeLower.contains("voter") || typeLower.contains("election") || typeLower.contains("elector") || typeLower.contains("epic") || typeLower.contains("identity card") || typeLower.contains("identity_card") || textLower.contains("voter") || textLower.contains("election commission") || textLower.contains("epic") || textLower.contains("elector photo")) {
            return "Voter ID"
        }
        if (typeLower.contains("marksheet") || typeLower.contains("mark sheet") || textLower.contains("marksheet") || textLower.contains("mark sheet") || textLower.contains("roll no") || textLower.contains("examination")) {
            return "Marksheet"
        }
        if (typeLower.contains("ration") || textLower.contains("ration card") || textLower.contains("ration")) {
            return "Ration Card"
        }

        // 2. Generic identity mapping: if "identity card" falls through to this point, Map it to Voter ID (which is the main Identity Card on Indian Govt Portals)
        if (typeLower.contains("identity")) {
            return "Voter ID"
        }

        return detectedType.trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun isBlacklistedLine(line: String): Boolean {
        val lower = line.lowercase()
        val blacklists = listOf(
            "government", "india", "income tax", "permanent", "department", "election", "commission", "signature", "card",
            "father", "mother", "husband", "spouse", "address", "photo", "licence", "license", "republic", "citizen", "national",
            "state", "district", "union", "authority", "unique", "identification", "school", "board", "voter", "birth", "date", "no",
            "delhi", "mumbai", "kolkata", "chennai", "road", "street", "lane", "floor", "house", "flat", "office", "post", "bazar", "nagar",
            "city", "town", "village", "taluk", "tehsil", "dist", "pin", "code", "phone", "mobile", "tel", "email", "web", "site",
            "issue", "expiry", "holder", "assembly", "elector", "marksheet", "certificate", "examined", "roll", "marks", "grades"
        )
        return blacklists.any { lower.contains(it) }
    }

    private fun isValidName(line: String): Boolean {
        if (line.any { it.isDigit() }) return false
        val invalidChars = listOf('@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '=', '[', ']', '{', '}', ';', ':', '"', '<', '>', '/', '\\', '|')
        if (line.any { it in invalidChars }) return false
        val letters = line.filter { it.isLetter() }.length
        val total = line.length
        return total > 0 && (letters.toDouble() / total.toDouble()) > 0.6
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete cached files
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.contains("scan") || file.name.contains("combined") || file.name.contains("compressed")) {
                        file.delete()
                    }
                }
                // Wipe Room database
                database.documentDao().deleteAllDocuments()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
