package com.example.ui

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.ByteArrayOutputStream
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import android.util.Log

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

enum class UploadFormat {
    JPEG,
    PDF,
    BOTH
}

enum class QueueItemPhase {
    PROCESSING,
    AWAITING_CONFIRMATION,
    COMPLETED,
    SAVED_LOCALLY_NEEDS_SYNC,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == SAVED_LOCALLY_NEEDS_SYNC || this == FAILED || this == CANCELLED
}

enum class StatusBannerTone {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

sealed interface StatusBannerState {
    data object Hidden : StatusBannerState
    data class Processing(val message: String) : StatusBannerState
    data class Visible(
        val message: String,
        val tone: StatusBannerTone
    ) : StatusBannerState
}

sealed interface FolderBrowserAsyncState {
    data object Idle : FolderBrowserAsyncState
    data object Loading : FolderBrowserAsyncState
    data object Loaded : FolderBrowserAsyncState
    data class DiagnosticNotice(val message: String, val isSuccess: Boolean) : FolderBrowserAsyncState
    data class Error(val message: String) : FolderBrowserAsyncState
}

data class DriveFolderBrowserState(
    val pathStack: List<GoogleDriveFolder> = listOf(GoogleDriveFolder("root", "My Drive")),
    val subfolders: List<GoogleDriveFolder> = emptyList(),
    val asyncState: FolderBrowserAsyncState = FolderBrowserAsyncState.Idle
) {
    val currentFolder: GoogleDriveFolder
        get() = pathStack.lastOrNull() ?: GoogleDriveFolder("root", "My Drive")
    val currentFolderId: String
        get() = currentFolder.id
    val currentFolderName: String
        get() = currentFolder.name
    val isLoading: Boolean
        get() = asyncState is FolderBrowserAsyncState.Loading
    val errorMessage: String?
        get() = (asyncState as? FolderBrowserAsyncState.Error)?.message
    val diagnosticNotice: FolderBrowserAsyncState.DiagnosticNotice?
        get() = asyncState as? FolderBrowserAsyncState.DiagnosticNotice
}

sealed interface DriveFilesUiState {
    data object Idle : DriveFilesUiState
    data class Loading(val cachedFiles: List<com.example.network.DriveFile> = emptyList()) : DriveFilesUiState
    data class Success(val files: List<com.example.network.DriveFile>) : DriveFilesUiState
    data class Error(
        val message: String,
        val cachedFiles: List<com.example.network.DriveFile> = emptyList()
    ) : DriveFilesUiState

    val currentFiles: List<com.example.network.DriveFile>
        get() = when (this) {
            Idle -> emptyList()
            is Loading -> cachedFiles
            is Success -> files
            is Error -> cachedFiles
        }
    val isLoading: Boolean
        get() = this is Loading
    val errorMessage: String?
        get() = (this as? Error)?.message
}

data class PendingDocument(
    val queueId: String,
    val compressedFile: File,
    val initialPersonName: String,
    val initialDocumentType: String,
    val pageFiles: List<File>? = null,
    val initialFormat: UploadFormat = UploadFormat.PDF
)

data class BatchGroup(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uris: List<Uri>,
    val isIdCard: Boolean
)

enum class AuthState {
    LOADING,
    NOT_LOGGED_IN,
    DEVICE_MISMATCH,
    PENDING_APPROVAL,
    APPROVED,
    EXPIRED
}

data class AppUser(
    val email: String = "",
    val deviceId: String = "",
    val deviceModel: String = "",
    val isApproved: Boolean = false,
    val isAdmin: Boolean = false,
    val role: String = "user",
    val status: String = "pending",
    val currentSessionToken: String = "",
    val expiryTimestamp: Long = 0L,
    val registrationTimestamp: Long = 0L,
    val lastActiveTimestamp: Long = 0L,
    val totalScannedCount: Long = 0L
)

class HomeViewModel(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val context: Context
) : ViewModel() {

    private val _batchVerificationGroups = MutableStateFlow<List<BatchGroup>?>(null)
    val batchVerificationGroups: StateFlow<List<BatchGroup>?> = _batchVerificationGroups.asStateFlow()

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        authListenerRegistration?.remove()
        usersListenerRegistration?.remove()
    }


    val documents = database.documentDao().getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val targetSizeKb = settingsRepository.targetSizeKb
        .stateIn(viewModelScope, SharingStarted.Lazily, 500)

    val imageFormat = settingsRepository.imageFormat
        .stateIn(viewModelScope, SharingStarted.Lazily, "PDF")

    val compressionCodec = settingsRepository.compressionCodec
        .stateIn(viewModelScope, SharingStarted.Lazily, "WEBP")

    val autoEnhanceEnabled = settingsRepository.autoEnhanceEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val ADMIN_SECURITY_KEY = "DASMO_ADMIN_SEC_26042004_SUBHOJIT_CYBER_KEY"
    private val firestore = FirebaseFirestore.getInstance()
    private var authListenerRegistration: ListenerRegistration? = null
    private var usersListenerRegistration: ListenerRegistration? = null
    private var currentSessionToken: String? = null
    // BUG FIX: track the auth listening job to prevent duplicate collectors if called multiple times
    private var authListeningJob: kotlinx.coroutines.Job? = null

    private val _authState = MutableStateFlow(AuthState.LOADING)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _allUsers = MutableStateFlow<List<AppUser>>(emptyList())
    val allUsers: StateFlow<List<AppUser>> = _allUsers.asStateFlow()

    private val _dbLogs = MutableStateFlow<List<String>>(emptyList())
    val dbLogs: StateFlow<List<String>> = _dbLogs.asStateFlow()

    fun addDbLog(msg: String) {
        android.util.Log.d("DB_LOG", msg)
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        _dbLogs.value = _dbLogs.value + "[$time] $msg"
    }

    fun clearDbLogs() {
        _dbLogs.value = emptyList()
    }

    fun loginUser(email: String, deviceId: String) {
        val normalizedEmail = email.trim().lowercase()
        addDbLog("loginUser called for: $normalizedEmail")
        _authState.value = AuthState.LOADING
        viewModelScope.launch {
            settingsRepository.setGoogleEmail(normalizedEmail)
            observeUserDoc(normalizedEmail, deviceId)
        }
    }

    fun startAuthListening(deviceId: String) {
        val currentModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        addDbLog("startAuthListening: deviceId=$deviceId, Model=$currentModel")
        // BUG FIX: cancel previous auth listening job to avoid stacking up duplicate collectors
        authListeningJob?.cancel()
        authListeningJob = viewModelScope.launch {
            googleEmail.collect { email ->
                if (email.isNullOrEmpty()) {
                    addDbLog("No user signed in. State -> NOT_LOGGED_IN")
                    authListenerRegistration?.remove()
                    authListenerRegistration = null
                    usersListenerRegistration?.remove()
                    usersListenerRegistration = null
                    _authState.value = AuthState.NOT_LOGGED_IN
                    _isAdmin.value = false
                } else {
                    observeUserDoc(email, deviceId)
                }
            }
        }
    }

    private fun observeUserDoc(email: String, deviceId: String) {
        val currentModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val normalizedEmail = email.trim().lowercase()
        val isAdminEmail = normalizedEmail == "subhojitpaul26042004@gmail.com"
        val safeDeviceId = if (deviceId.isNullOrBlank()) "unknown_device" else deviceId

        if (_authState.value == AuthState.NOT_LOGGED_IN) {
            _authState.value = AuthState.LOADING
        }

        authListenerRegistration?.remove()

        if (currentSessionToken == null) {
            currentSessionToken = java.util.UUID.randomUUID().toString()
        }
        val activeSessionToken = currentSessionToken ?: java.util.UUID.randomUUID().toString().also { currentSessionToken = it }
        val docRef = firestore.collection("dasmo_scanner_users").document(normalizedEmail)

        addDbLog("Observing Firestore user doc: users/$normalizedEmail (isAdmin=$isAdminEmail)")

        authListenerRegistration = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                addDbLog("Firestore snapshot error for users/$normalizedEmail: ${e.message}")
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val rawApproved = snapshot.getBoolean("isApproved") ?: snapshot.getBoolean("dasmo_isApproved") ?: false
                val rawStatus = snapshot.getString("status") ?: snapshot.getString("dasmo_status") ?: "pending"
                val rawRole = snapshot.getString("role") ?: snapshot.getString("dasmo_role") ?: if (isAdminEmail) "admin" else "user"
                val rawDeviceId = snapshot.getString("deviceId") ?: snapshot.getString("dasmo_deviceId") ?: ""
                val rawDeviceModel = snapshot.getString("deviceModel") ?: ""
                val rawExpiry = snapshot.getLong("expiryTimestamp") ?: 0L
                val rawAdmin = isAdminEmail || rawRole == "admin" || (snapshot.getBoolean("isAdmin") ?: false) || (snapshot.getBoolean("dasmo_isAdmin") ?: false)

                val isApproved = rawApproved || rawStatus == "approved" || rawAdmin

                // 1. Strict Hardware Device Binding Check (One Account Per Physical Device)
                if (rawDeviceId.isNotEmpty() && rawDeviceId != safeDeviceId) {
                    addDbLog("DEVICE MISMATCH: Account bound to '$rawDeviceModel' ($rawDeviceId). Current device is '$currentModel' ($safeDeviceId)")
                    _authState.value = AuthState.DEVICE_MISMATCH
                    _isAdmin.value = false
                    return@addSnapshotListener
                }

                // If not yet bound to a device (first login or after admin unbinds), bind this physical device ONCE:
                if (rawDeviceId.isEmpty()) {
                    docRef.update(
                        mapOf(
                            "deviceId" to safeDeviceId,
                            "dasmo_deviceId" to safeDeviceId,
                            "deviceModel" to currentModel
                        )
                    )
                }

                if (rawAdmin) {
                    addDbLog("Admin authenticated: $normalizedEmail on bound device. State -> APPROVED")
                    _authState.value = AuthState.APPROVED
                    _isAdmin.value = true
                    if (usersListenerRegistration == null) {
                        listenToAllUsers()
                    }
                } else {
                    _isAdmin.value = false

                    // 2. Admin Approval Verification
                    if (!isApproved || rawStatus != "approved") {
                        addDbLog("AWAITING APPROVAL: User $normalizedEmail status is '$rawStatus' (isApproved=$isApproved). Access blocked.")
                        _authState.value = AuthState.PENDING_APPROVAL
                        return@addSnapshotListener
                    }

                    // 3. Expiration Check
                    if (rawExpiry > 0L && System.currentTimeMillis() > rawExpiry) {
                        addDbLog("PLAN EXPIRED: Subscription ended for $normalizedEmail.")
                        _authState.value = AuthState.EXPIRED
                        return@addSnapshotListener
                    }

                    // 4. Approved & Device Matched: Full Access!
                    addDbLog("ACCESS GRANTED: User $normalizedEmail approved on bound device ($safeDeviceId).")
                    _authState.value = AuthState.APPROVED
                }
            } else {
                // User not registered in Firestore -> Create record ONCE
                addDbLog("Registering new user record in Firestore: users/$normalizedEmail")
                val userDoc = mapOf(
                    "email" to normalizedEmail,
                    "deviceId" to safeDeviceId,
                    "dasmo_deviceId" to safeDeviceId,
                    "deviceModel" to currentModel,
                    "currentSessionToken" to activeSessionToken,
                    "expiryTimestamp" to 0L,
                    "registrationTimestamp" to System.currentTimeMillis(),
                    "lastActiveTimestamp" to System.currentTimeMillis(),
                    "totalScannedCount" to 0L,
                    "isApproved" to isAdminEmail,
                    "dasmo_isApproved" to isAdminEmail,
                    "isAdmin" to isAdminEmail,
                    "dasmo_isAdmin" to isAdminEmail,
                    "role" to if (isAdminEmail) "admin" else "user",
                    "dasmo_role" to if (isAdminEmail) "admin" else "user",
                    "status" to if (isAdminEmail) "approved" else "pending",
                    "dasmo_status" to if (isAdminEmail) "approved" else "pending",
                    "appTag" to "dasmo_scanner"
                )
                docRef.set(userDoc, com.google.firebase.firestore.SetOptions.merge()).addOnSuccessListener {
                    addDbLog("Registered record for $normalizedEmail.")
                }.addOnFailureListener { e ->
                    Log.e("HomeViewModel", "Failed to register record for $normalizedEmail", e)
                    addDbLog("Error registering user record: ${e.localizedMessage}")
                    postStatusMessage("Registration sync warning: ${e.localizedMessage}", StatusBannerTone.WARNING)
                }

                if (isAdminEmail) {
                    _authState.value = AuthState.APPROVED
                    _isAdmin.value = true
                    if (usersListenerRegistration == null) {
                        listenToAllUsers()
                    }
                } else {
                    _authState.value = AuthState.PENDING_APPROVAL
                    _isAdmin.value = false
                }
            }
        }
    }


    private fun listenToAllUsers() {
        usersListenerRegistration?.remove()
        addDbLog("listenToAllUsers: Subscribing to snapshots for 'users' collection in Firestore.")
        usersListenerRegistration = firestore.collection("dasmo_scanner_users").addSnapshotListener { snapshot, e ->
            if (e != null) {
                addDbLog("listenToAllUsers ERROR: ${e.message}")
                postStatusMessage("Error loading users: ${e.message}", StatusBannerTone.ERROR)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                addDbLog("listenToAllUsers: Received collection snapshot. Total documents = ${snapshot.size()}")
                val oldPendingEmails = _allUsers.value
                    .filter { it.status == "pending" || (!it.isApproved && !it.isAdmin) }
                    .map { it.email.trim().lowercase() }
                    .toSet()

                val users = snapshot.documents.mapNotNull { doc ->
                    val email = doc.getString("email")?.takeIf { it.isNotBlank() } ?: doc.id
                    val isSuperAdmin = email.trim().lowercase() == "subhojitpaul26042004@gmail.com"
                    val isApprovedVal = doc.getBoolean("dasmo_isApproved") ?: doc.getBoolean("isApproved") ?: false
                    val statusVal = doc.getString("dasmo_status") ?: doc.getString("status") ?: "pending"
                    val roleVal = doc.getString("dasmo_role") ?: doc.getString("role") ?: if (isSuperAdmin) "admin" else "user"
                    val deviceIdVal = doc.getString("dasmo_deviceId") ?: doc.getString("deviceId") ?: ""
                    val deviceModelVal = doc.getString("deviceModel") ?: ""
                    val sessionVal = doc.getString("currentSessionToken") ?: ""
                    val expiryVal = doc.getLong("expiryTimestamp") ?: 0L
                    val regVal = doc.getLong("registrationTimestamp") ?: 0L
                    val lastActiveVal = doc.getLong("lastActiveTimestamp") ?: 0L
                    val totalScansVal = doc.getLong("totalScannedCount") ?: doc.getLong("scannedCount") ?: doc.getLong("scanCount") ?: 0L
                    val isAdminVal = isSuperAdmin || roleVal == "admin" || (doc.getBoolean("isAdmin") ?: false) || (doc.getBoolean("dasmo_isAdmin") ?: false)

                    AppUser(
                        email = email,
                        deviceId = deviceIdVal,
                        deviceModel = deviceModelVal,
                        isApproved = isApprovedVal || statusVal == "approved" || isAdminVal,
                        isAdmin = isAdminVal,
                        role = if (isAdminVal) "admin" else roleVal,
                        status = if (isAdminVal) "approved" else statusVal,
                        currentSessionToken = sessionVal,
                        expiryTimestamp = expiryVal,
                        registrationTimestamp = regVal,
                        lastActiveTimestamp = lastActiveVal,
                        totalScannedCount = totalScansVal
                    )
                }

                if (_allUsers.value.isNotEmpty()) {
                    val currentPending = users.filter { it.status == "pending" && !it.isAdmin }
                    for (u in currentPending) {
                        val normEmail = u.email.trim().lowercase()
                        if (!oldPendingEmails.contains(normEmail) && normEmail != "subhojitpaul26042004@gmail.com") {
                            addDbLog("listenToAllUsers: NEW PENDING ACCESS REQUEST: ${u.email}")
                            _newRequestNotification.value = u.email
                            sendSystemNotification(u.email)
                        }
                    }
                }

                _allUsers.value = users
            }
        }
    }

    fun toggleUserApproval(email: String, currentStatus: Boolean) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            postStatusMessage("Unauthorized action!", StatusBannerTone.ERROR)
            return
        }
        val newApproved = !currentStatus
        val newStatus = if (newApproved) "approved" else "rejected"
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            "isApproved", newApproved,
            "dasmo_isApproved", newApproved,
            "status", newStatus,
            "dasmo_status", newStatus,
            "adminKey", ADMIN_SECURITY_KEY
        )
    }

    fun approveUser(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            postStatusMessage("Unauthorized action!", StatusBannerTone.ERROR)
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            "isApproved", true,
            "dasmo_isApproved", true,
            "status", "approved",
            "dasmo_status", "approved",
            "adminKey", ADMIN_SECURITY_KEY
        ).addOnSuccessListener {
            postStatusMessage("User $email approved successfully!", StatusBannerTone.SUCCESS)
        }.addOnFailureListener { e ->
            postStatusMessage("Failed to approve $email: ${e.localizedMessage}", StatusBannerTone.ERROR)
        }
    }

    fun declineUser(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            postStatusMessage("Unauthorized action!", StatusBannerTone.ERROR)
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            "isApproved", false,
            "dasmo_isApproved", false,
            "status", "rejected",
            "dasmo_status", "rejected",
            "adminKey", ADMIN_SECURITY_KEY
        ).addOnSuccessListener {
            postStatusMessage("User $email access revoked.", StatusBannerTone.WARNING)
        }
    }

    /**
     * Unbinds the user's hardware device so they can register a new phone upon approval.
     */
    fun revokeUserDevice(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            postStatusMessage("Unauthorized action!", StatusBannerTone.ERROR)
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            mapOf(
                "deviceId" to "",
                "dasmo_deviceId" to "",
                "deviceModel" to "",
                "isApproved" to false,
                "dasmo_isApproved" to false,
                "status" to "pending",
                "dasmo_status" to "pending",
                "currentSessionToken" to "",
                "adminKey" to ADMIN_SECURITY_KEY
            )
        ).addOnSuccessListener {
            postStatusMessage("Device lock reset for $email. User can now bind a new device.", StatusBannerTone.SUCCESS)
        }
    }

    fun updateUserExpiry(email: String, expiryTimestamp: Long) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            postStatusMessage("Unauthorized action!", StatusBannerTone.ERROR)
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            mapOf(
                "expiryTimestamp" to expiryTimestamp,
                "adminKey" to ADMIN_SECURITY_KEY
            )
        ).addOnSuccessListener {
            postStatusMessage("Updated access plan duration for $email.", StatusBannerTone.SUCCESS)
        }
    }

    fun deleteUser(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            postStatusMessage("Unauthorized action!", StatusBannerTone.ERROR)
            return
        }
        val docRef = firestore.collection("dasmo_scanner_users").document(email.trim().lowercase())
        docRef.update("status", "deleted", "adminKey", ADMIN_SECURITY_KEY)
            .addOnCompleteListener {
                docRef.delete().addOnSuccessListener {
                    postStatusMessage("Deleted user $email from database.", StatusBannerTone.WARNING)
                }
            }
    }

    fun createUserManually(email: String, role: String, status: String, expiryTimestamp: Long = 0L) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            postStatusMessage("Unauthorized action!", StatusBannerTone.ERROR)
            return
        }
        val trimmed = email.trim().lowercase()
        val isApproved = status == "approved"
        val isAdmin = role == "admin"
        val doc = mapOf(
            "email" to trimmed,
            "role" to role,
            "dasmo_role" to role,
            "status" to status,
            "dasmo_status" to status,
            "isApproved" to isApproved,
            "dasmo_isApproved" to isApproved,
            "isAdmin" to isAdmin,
            "dasmo_isAdmin" to isAdmin,
            "expiryTimestamp" to expiryTimestamp,
            "registrationTimestamp" to System.currentTimeMillis(),
            "lastActiveTimestamp" to System.currentTimeMillis(),
            "totalScannedCount" to 0L,
            "appTag" to "admin_preapproved",
            "adminKey" to ADMIN_SECURITY_KEY
        )
        firestore.collection("dasmo_scanner_users").document(trimmed).set(doc, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                postStatusMessage("Created and pre-approved account for $trimmed.", StatusBannerTone.SUCCESS)
            }
    }

    fun incrementUserScannedCount() {
        val email = googleEmail.value?.trim()?.lowercase() ?: return
        if (email.isEmpty()) return
        firestore.collection("dasmo_scanner_users").document(email).update(
            "totalScannedCount", com.google.firebase.firestore.FieldValue.increment(1),
            "lastActiveTimestamp", System.currentTimeMillis()
        ).addOnFailureListener { err ->
            addDbLog("Failed to increment user scan count: ${err.message}")
        }
    }

    private fun sendSystemNotification(email: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "admin_notifications"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Admin Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts for new user access requests"
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = if (intent != null) {
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Access Requested")
                .setContentText("User $email is requesting access.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(email.hashCode(), notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val useA4Format = settingsRepository.useA4Format
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val enableAiAnalysis = settingsRepository.enableAiAnalysis
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val showConfirmation = settingsRepository.showConfirmation
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val storageLimitMb = settingsRepository.storageLimitMb
        .stateIn(viewModelScope, SharingStarted.Lazily, 100)

    val batchPagesPerDoc = settingsRepository.batchPagesPerDoc
        .stateIn(viewModelScope, SharingStarted.Lazily, 2)

    val googleEmail = settingsRepository.googleEmail
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val driveFolderId = settingsRepository.driveFolderId
        .stateIn(viewModelScope, SharingStarted.Lazily, "root")

    val driveFolderName = settingsRepository.driveFolderName
        .stateIn(viewModelScope, SharingStarted.Lazily, "My Drive")

    private val _targetSubfolder = MutableStateFlow("")
    val targetSubfolder = _targetSubfolder.asStateFlow()

    private val _publicFolderSize = MutableStateFlow<Long>(0L)
    val publicFolderSize = _publicFolderSize.asStateFlow()

    fun updatePublicFolderSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val size = ImageProcessor.getPublicFolderSize(context)
            _publicFolderSize.value = size
        }
    }

    private val _subfolderAtLastSizeChange = MutableStateFlow<String?>(null)
    val subfolderAtLastSizeChange = _subfolderAtLastSizeChange.asStateFlow()

    fun setSubfolderAtLastSizeChange(value: String?) {
        _subfolderAtLastSizeChange.value = value
    }

    init {
        viewModelScope.launch {
            _targetSubfolder.value = settingsRepository.targetSubfolder.first()
        }
        updatePublicFolderSize()
    }

    fun setTargetSubfolder(name: String) {
        _targetSubfolder.value = name
        viewModelScope.launch {
            settingsRepository.setTargetSubfolder(name)
        }
    }

    private val subFolderCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val subFolderMutex = kotlinx.coroutines.sync.Mutex()
    private val backgroundUploadMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun getCachedOrFetchSubFolder(token: String, subFolderName: String, parentId: String): String {
        val trimmedSubFolder = subFolderName.trim()
        if (trimmedSubFolder.isEmpty()) {
            return parentId
        }

        // Avoid creating a nested subfolder with the exact same name as the selected destination folder itself
        try {
            val destinationFolderName = settingsRepository.driveFolderName.first()
            if (trimmedSubFolder.equals(destinationFolderName.trim(), ignoreCase = true)) {
                android.util.Log.d("HomeViewModel", "Target subfolder '$trimmedSubFolder' is same as destination folder. Skipping nested folder creation.")
                return parentId
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val cacheKey = "$parentId:${trimmedSubFolder.lowercase()}"
        subFolderCache[cacheKey]?.let { return it }
        
        subFolderMutex.lock()
        try {
            subFolderCache[cacheKey]?.let { return it }
            val subFolderId = retryIO(times = 3) { 
                GoogleDriveClient.getOrCreateFolder(token, trimmedSubFolder, parentId) 
            }
            if (subFolderId != null) {
                subFolderCache[cacheKey] = subFolderId
                return subFolderId
            }
            return parentId
        } finally {
            subFolderMutex.unlock()
        }
    }

    // Active background processing/upload tasks queue
    data class QueueItem(
        val id: String,
        val personName: String,
        val documentType: String,
        val format: UploadFormat,
        val status: String,
        val phase: QueueItemPhase = QueueItemPhase.PROCESSING,
        val timestamp: Long = System.currentTimeMillis(),
        val docId: Int? = null
    )

    private val _activeQueue = MutableStateFlow<List<QueueItem>>(emptyList())
    val activeQueue = _activeQueue.asStateFlow()

    fun clearActiveQueue() {
        _activeQueue.update { q ->
            q.filter { !it.phase.isTerminal }
        }
    }

    val nameBeforeType = settingsRepository.nameBeforeType
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    fun updateNameBeforeType(nameBefore: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNameBeforeType(nameBefore)
        }
    }

    private val _statusBannerState = MutableStateFlow<StatusBannerState>(StatusBannerState.Hidden)
    val statusBannerState: StateFlow<StatusBannerState> = _statusBannerState.asStateFlow()

    val isProcessing: StateFlow<Boolean> = _statusBannerState
        .map { it is StatusBannerState.Processing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val statusMessage: StateFlow<String> = _statusBannerState
        .map { state ->
            when (state) {
                StatusBannerState.Hidden -> ""
                is StatusBannerState.Processing -> state.message
                is StatusBannerState.Visible -> state.message
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val statusBannerTone: StateFlow<StatusBannerTone> = _statusBannerState
        .map { state ->
            when (state) {
                StatusBannerState.Hidden -> StatusBannerTone.INFO
                is StatusBannerState.Processing -> StatusBannerTone.INFO
                is StatusBannerState.Visible -> state.tone
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StatusBannerTone.INFO)

    private fun postProcessing(message: String) {
        _statusBannerState.value = StatusBannerState.Processing(message.ifBlank { "Optimizing & processing document..." })
    }

    private fun clearProcessingIfActive() {
        _statusBannerState.update { state ->
            if (state is StatusBannerState.Processing) StatusBannerState.Hidden else state
        }
    }

    private fun postStatusMessage(message: String, tone: StatusBannerTone = inferBannerTone(message)) {
        if (message.isBlank()) {
            _statusBannerState.value = StatusBannerState.Hidden
        } else {
            _statusBannerState.value = StatusBannerState.Visible(message = message, tone = tone)
        }
    }

    private fun inferBannerTone(message: String): StatusBannerTone {
        if (message.isBlank()) return StatusBannerTone.INFO
        val lower = message.lowercase()
        return when {
            lower.contains("error") || lower.contains("failed") || lower.contains("unauthorized") -> StatusBannerTone.ERROR
            lower.contains("warning") || lower.contains("saved locally") || lower.contains("revoked") || lower.contains("deleted") -> StatusBannerTone.WARNING
            lower.contains("success") || lower.contains("approved") || lower.contains("complete") || lower.contains("reset") || lower.contains("updated") || lower.contains("created") -> StatusBannerTone.SUCCESS
            else -> StatusBannerTone.INFO
        }
    }

    fun clearStatusMessage() {
        _statusBannerState.value = StatusBannerState.Hidden
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            addDbLog("=== RUNNING SYSTEM SELF-DIAGNOSTICS ===")
            val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
            addDbLog("Logged-in Google account: '$currentLoggedInEmail'")
            
            val localMemoryRecord = _allUsers.value.find { it.email.trim().lowercase() == currentLoggedInEmail }
            if (localMemoryRecord != null) {
                addDbLog("Local memory state has user record: Approved=${localMemoryRecord.isApproved}, Admin=${localMemoryRecord.isAdmin}, Status='${localMemoryRecord.status}', Bound DeviceId='${localMemoryRecord.deviceId}'")
            } else {
                addDbLog("No user record in local memory list.")
            }

            firestore.collection("dasmo_scanner_users").get()
                .addOnSuccessListener { snapshot ->
                    addDbLog("Firestore Connection: SUCCESS! Loaded ${snapshot.size()} documents from 'users' collection.")
                    val matching = snapshot.documents.find { it.id.trim().lowercase() == currentLoggedInEmail }
                    if (matching != null) {
                        addDbLog("Match found for email document 'users/${matching.id}':")
                        addDbLog("  - email field: '${matching.getString("email")}'")
                        addDbLog("  - dasmo_isApproved: ${matching.getBoolean("dasmo_isApproved")} (isApproved: ${matching.getBoolean("isApproved")})")
                        addDbLog("  - dasmo_isAdmin: ${matching.getBoolean("dasmo_isAdmin")} (isAdmin: ${matching.getBoolean("isAdmin")})")
                        addDbLog("  - dasmo_role: '${matching.getString("dasmo_role")}' (role: '${matching.getString("role")}')")
                        addDbLog("  - dasmo_status: '${matching.getString("dasmo_status")}' (status: '${matching.getString("status")}')")
                        addDbLog("  - dasmo_deviceId: '${matching.getString("dasmo_deviceId")}' (deviceId: '${matching.getString("deviceId")}')")
                        addDbLog("  - appTag field: '${matching.getString("appTag")}'")
                    } else {
                        addDbLog("Document 'users/$currentLoggedInEmail' does NOT exist in Firestore 'users' collection. If you signed in, a new record should have been created.")
                    }
                }
                .addOnFailureListener { e ->
                    addDbLog("Firestore Read FAILED: ${e.message}")
                    addDbLog("Troubleshooting: This usually indicates either Firebase Firestore is not provisioned, has restrictive Security Rules (Permission Denied), or is bound to a different google-services.json file.")
                }
        }
    }

    private val _newRequestNotification = MutableStateFlow<String?>(null)
    val newRequestNotification = _newRequestNotification.asStateFlow()

    fun dismissNewRequestNotification() {
        _newRequestNotification.value = null
    }

    private val _pendingDocuments = MutableStateFlow<List<PendingDocument>>(emptyList())
    val pendingDocument: StateFlow<PendingDocument?> = _pendingDocuments.map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Google Drive folder selection session state machine
    private val _folderBrowserState = MutableStateFlow(DriveFolderBrowserState())
    val folderBrowserState: StateFlow<DriveFolderBrowserState> = _folderBrowserState.asStateFlow()

    val currentFolderId: StateFlow<String> = _folderBrowserState
        .map { it.currentFolderId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "root")

    val currentFolderName: StateFlow<String> = _folderBrowserState
        .map { it.currentFolderName }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "My Drive")

    val folderPathStack: StateFlow<List<GoogleDriveFolder>> = _folderBrowserState
        .map { it.pathStack }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(GoogleDriveFolder("root", "My Drive")))

    val subfolders: StateFlow<List<GoogleDriveFolder>> = _folderBrowserState
        .map { it.subfolders }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isFolderLoading: StateFlow<Boolean> = _folderBrowserState
        .map { it.isLoading }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val folderError: StateFlow<String?> = _folderBrowserState
        .map { it.errorMessage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val folderDiagnostic: StateFlow<FolderBrowserAsyncState.DiagnosticNotice?> = _folderBrowserState
        .map { it.diagnosticNotice }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun clearFolderError() {
        _folderBrowserState.update { state ->
            if (state.asyncState is FolderBrowserAsyncState.Error ||
                state.asyncState is FolderBrowserAsyncState.DiagnosticNotice) {
                state.copy(asyncState = FolderBrowserAsyncState.Idle)
            } else state
        }
    }

    private val _recoveryIntent = MutableStateFlow<android.content.Intent?>(null)
    val recoveryIntent = _recoveryIntent.asStateFlow()

    fun clearRecoveryIntent() {
        _recoveryIntent.value = null
    }

    private val _driveFilesUiState = MutableStateFlow<DriveFilesUiState>(DriveFilesUiState.Idle)
    val driveFilesUiState: StateFlow<DriveFilesUiState> = _driveFilesUiState.asStateFlow()

    val driveFiles: StateFlow<List<com.example.network.DriveFile>> = _driveFilesUiState
        .map { it.currentFiles }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isDriveFilesLoading: StateFlow<Boolean> = _driveFilesUiState
        .map { it.isLoading }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val driveFilesError: StateFlow<String?> = _driveFilesUiState
        .map { it.errorMessage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun fetchDriveFiles(context: Context) {
        val email = googleEmail.value
        if (email.isNullOrEmpty()) {
            _driveFilesUiState.value = DriveFilesUiState.Idle
            return
        }
        viewModelScope.launch {
            val previousFiles = _driveFilesUiState.value.currentFiles
            _driveFilesUiState.value = DriveFilesUiState.Loading(cachedFiles = previousFiles)
            try {
                val obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val rootFolderId = driveFolderId.value
                    val rootNameStr = driveFolderName.value ?: "My Drive"

                    // 1. Fetch direct files & immediate subfolders in parallel
                    val (direct, subFolderDefs) = coroutineScope {
                        val directDeferred = async(Dispatchers.IO) {
                            GoogleDriveClient.listFiles(obtainedToken, rootFolderId).also { list ->
                                list.forEach { it.folderName = rootNameStr }
                            }
                        }
                        val subFoldersDeferred = async(Dispatchers.IO) {
                            GoogleDriveClient.listFolders(obtainedToken, rootFolderId)
                        }
                        Pair(directDeferred.await(), subFoldersDeferred.await())
                    }

                    val combined = mutableListOf<com.example.network.DriveFile>()
                    combined.addAll(direct)

                    // 2. Fetch files across up to 10 recent subfolders concurrently
                    val limitedSubfolders = subFolderDefs.take(10)
                    val subFilesLists = coroutineScope {
                        limitedSubfolders.map { folder ->
                            async(Dispatchers.IO) {
                                try {
                                    GoogleDriveClient.listFiles(obtainedToken, folder.id).also { list ->
                                        list.forEach { it.folderName = folder.name }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    emptyList()
                                }
                            }
                        }.awaitAll()
                    }
                    subFilesLists.forEach { combined.addAll(it) }

                    val sorted = combined.sortedByDescending { it.createdTime ?: "" }
                    _driveFilesUiState.value = DriveFilesUiState.Success(files = sorted)
                } else {
                    _driveFilesUiState.value = DriveFilesUiState.Error(
                        message = "Failed to obtain credentials",
                        cachedFiles = previousFiles
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _driveFilesUiState.value = DriveFilesUiState.Error(
                    message = e.localizedMessage ?: e.message ?: "Unknown Drive error",
                    cachedFiles = previousFiles
                )
            }
        }
    }

    fun updateTargetSize(sizeKb: Int) {
        viewModelScope.launch {
            settingsRepository.setTargetSizeKb(sizeKb)
            _subfolderAtLastSizeChange.value = _targetSubfolder.value
        }
    }

    fun updateImageFormat(format: String) {
        viewModelScope.launch {
            settingsRepository.setImageFormat(format)
        }
    }

    fun updateCompressionCodec(codec: String) {
        viewModelScope.launch {
            settingsRepository.setCompressionCodec(codec)
        }
    }

    fun updateAutoEnhanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoEnhanceEnabled(enabled)
        }
    }

    fun updateUseA4Format(use: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUseA4Format(use)
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

    fun updateStorageLimitMb(limitMb: Int) {
        viewModelScope.launch {
            settingsRepository.setStorageLimitMb(limitMb)
        }
    }

    fun updateBatchPagesPerDoc(pages: Int) {
        viewModelScope.launch {
            settingsRepository.setBatchPagesPerDoc(pages)
        }
    }

    private var lastBatchScannedUris: List<Uri> = emptyList()

    fun regroupBatch(pagesPerDoc: Int) {
        if (lastBatchScannedUris.isEmpty()) return
        val chunkSize = pagesPerDoc.coerceAtLeast(1)
        val groupedUris = lastBatchScannedUris.chunked(chunkSize).map { chunk ->
            BatchGroup(uris = chunk, isIdCard = false)
        }
        _batchVerificationGroups.value = groupedUris
    }

    fun cancelPendingDocument(format: UploadFormat? = null) {
        val pending = _pendingDocuments.value.firstOrNull() ?: return
        try { pending.compressedFile.delete() } catch (e: Exception) {}
        pending.pageFiles?.forEach { file ->
            try { file.delete() } catch (e: Exception) {}
        }
        _pendingDocuments.update { it.drop(1) }
        _activeQueue.update { it.map { item ->
            if (item.id == pending.queueId) {
                item.copy(
                    format = format ?: item.format,
                    status = "Cancelled",
                    phase = QueueItemPhase.CANCELLED
                )
            } else item
        } }
        clearStatusMessage()
    }

    fun setGoogleEmail(email: String?) {
        viewModelScope.launch {
            settingsRepository.setGoogleEmail(email)
            if (email == null) {
                currentSessionToken = null
                // reset folder settings
                settingsRepository.setDriveFolder("root", "My Drive")
                _folderBrowserState.value = DriveFolderBrowserState()
                _driveFilesUiState.value = DriveFilesUiState.Idle
            }
        }
    }

    fun fetchSubfolders(context: Context, folderId: String = _folderBrowserState.value.currentFolderId) {
        val email = googleEmail.value ?: return
        viewModelScope.launch {
            _folderBrowserState.update { it.copy(asyncState = FolderBrowserAsyncState.Loading) }
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val list = GoogleDriveClient.listFolders(obtainedToken, folderId)
                    _folderBrowserState.update {
                        it.copy(
                            subfolders = list,
                            asyncState = FolderBrowserAsyncState.Loaded
                        )
                    }
                } else {
                    _folderBrowserState.update {
                        it.copy(asyncState = FolderBrowserAsyncState.Error("Failed to retrieve access token"))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderBrowserState.update {
                    it.copy(asyncState = FolderBrowserAsyncState.Error(e.localizedMessage ?: e.message ?: "Failed to load folders"))
                }
                obtainedToken?.let { invalidateCachedToken(context, it) }
            }
        }
    }

    fun navigateToFolder(context: Context, id: String, name: String) {
        val email = googleEmail.value ?: return
        viewModelScope.launch {
            _folderBrowserState.update { it.copy(asyncState = FolderBrowserAsyncState.Loading) }
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val list = GoogleDriveClient.listFolders(obtainedToken, id)
                    _folderBrowserState.update { current ->
                        val updatedStack = current.pathStack + GoogleDriveFolder(id, name)
                        current.copy(
                            pathStack = updatedStack,
                            subfolders = list,
                            asyncState = FolderBrowserAsyncState.Loaded
                        )
                    }
                } else {
                    _folderBrowserState.update {
                        it.copy(asyncState = FolderBrowserAsyncState.Error("Failed to retrieve access token"))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderBrowserState.update {
                    it.copy(asyncState = FolderBrowserAsyncState.Error(e.localizedMessage ?: e.message ?: "Failed to open folder"))
                }
                obtainedToken?.let { invalidateCachedToken(context, it) }
            }
        }
    }

    fun navigateUp(context: Context) {
        val email = googleEmail.value ?: return
        viewModelScope.launch {
            if (_folderBrowserState.value.pathStack.size <= 1) return@launch
            _folderBrowserState.update { it.copy(asyncState = FolderBrowserAsyncState.Loading) }
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken != null) {
                    val currentStack = _folderBrowserState.value.pathStack
                    if (currentStack.size > 1) {
                        val updatedStack = currentStack.dropLast(1)
                        val parent = updatedStack.last()
                        val list = GoogleDriveClient.listFolders(obtainedToken, parent.id)
                        _folderBrowserState.update {
                            it.copy(
                                pathStack = updatedStack,
                                subfolders = list,
                                asyncState = FolderBrowserAsyncState.Loaded
                            )
                        }
                    } else {
                        _folderBrowserState.update { it.copy(asyncState = FolderBrowserAsyncState.Loaded) }
                    }
                } else {
                    _folderBrowserState.update {
                        it.copy(asyncState = FolderBrowserAsyncState.Error("Failed to retrieve access token"))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderBrowserState.update {
                    it.copy(asyncState = FolderBrowserAsyncState.Error(e.localizedMessage ?: e.message ?: "Failed to navigate up"))
                }
                obtainedToken?.let { invalidateCachedToken(context, it) }
            }
        }
    }

    fun createNewSubfolder(context: Context, name: String, onResult: (Boolean) -> Unit) {
        val email = googleEmail.value
        if (email.isNullOrBlank()) {
            _folderBrowserState.update {
                it.copy(asyncState = FolderBrowserAsyncState.Error("Please sign in to Google Drive first."))
            }
            onResult(false)
            return
        }
        viewModelScope.launch {
            _folderBrowserState.update { it.copy(asyncState = FolderBrowserAsyncState.Loading) }
            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                val targetParentId = _folderBrowserState.value.currentFolderId
                if (obtainedToken != null) {
                    val newId = GoogleDriveClient.getOrCreateFolder(obtainedToken, name, targetParentId)
                    if (newId != null) {
                        val list = GoogleDriveClient.listFolders(obtainedToken, targetParentId)
                        _folderBrowserState.update {
                            it.copy(
                                subfolders = list,
                                asyncState = FolderBrowserAsyncState.Loaded
                            )
                        }
                        onResult(true)
                    } else {
                        _folderBrowserState.update {
                            it.copy(asyncState = FolderBrowserAsyncState.Error("Failed to create folder on Google Drive"))
                        }
                        onResult(false)
                    }
                } else {
                    _folderBrowserState.update {
                        it.copy(asyncState = FolderBrowserAsyncState.Error("Failed to retrieve access token"))
                    }
                    onResult(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderBrowserState.update {
                    it.copy(asyncState = FolderBrowserAsyncState.Error(e.localizedMessage ?: e.message ?: "Failed to create folder"))
                }
                obtainedToken?.let { invalidateCachedToken(context, it) }
                onResult(false)
            }
        }
    }

    fun selectCurrentAsDestination() {
        val current = _folderBrowserState.value
        viewModelScope.launch {
            settingsRepository.setDriveFolder(current.currentFolderId, current.currentFolderName)
        }
    }

    // Resolves standard authorization token asynchronously
    fun testDriveApi(context: Context, email: String?) {
        if (email == null) {
            _folderBrowserState.update {
                it.copy(asyncState = FolderBrowserAsyncState.DiagnosticNotice("No Google account selected.", isSuccess = false))
            }
            return
        }
        viewModelScope.launch {
            _folderBrowserState.update {
                it.copy(asyncState = FolderBrowserAsyncState.Loading)
            }
            var token: String? = null
            try {
                token = getAccessToken(context, email)
                if (token == null) {
                    _folderBrowserState.update {
                        it.copy(asyncState = FolderBrowserAsyncState.DiagnosticNotice("Failed to fetch token, but no exception was thrown (returned null).", isSuccess = false))
                    }
                    return@launch
                }

                val folders = GoogleDriveClient.listFolders(token, "root")
                _folderBrowserState.update {
                    it.copy(asyncState = FolderBrowserAsyncState.DiagnosticNotice("SUCCESS! Found ${folders.size} root folders. Token length: ${token.length}", isSuccess = true))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _folderBrowserState.update {
                    it.copy(asyncState = FolderBrowserAsyncState.DiagnosticNotice("Test Failed!\nException Layer 1:\n=== ${e.javaClass.simpleName}: ${e.message}\nCause:\n=== ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}", isSuccess = false))
                }
                token?.let { invalidateCachedToken(context, it) }
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

    fun processBatchScannedImages(imageUris: List<Uri>, customPagesPerDoc: Int? = null) {
        if (imageUris.isEmpty()) return
        lastBatchScannedUris = imageUris

        viewModelScope.launch {
            postProcessing("Processing batch...")

            try {
                val chunkSize = (customPagesPerDoc ?: batchPagesPerDoc.value).coerceAtLeast(1)
                val groupedUris = imageUris.chunked(chunkSize).map { chunk ->
                    BatchGroup(uris = chunk, isIdCard = false)
                }

                clearStatusMessage()

                if (showConfirmation.value) {
                    _batchVerificationGroups.value = groupedUris
                } else {
                    for (group in groupedUris) {
                        processMultiScannedImagesInternal(group.uris)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                postStatusMessage("Batch processing failed: ${e.message}", StatusBannerTone.ERROR)
            }
        }
    }

    fun dismissBatchVerification() {
        _batchVerificationGroups.value = null
    }

    fun confirmBatchVerification(groups: List<BatchGroup>) {
        _batchVerificationGroups.value = null
        viewModelScope.launch {
            for (group in groups) {
                processMultiScannedImagesInternal(group.uris)
            }
        }
    }

    fun updateBatchGroups(newGroups: List<BatchGroup>) {
        _batchVerificationGroups.value = newGroups
    }

    fun processMultiScannedImages(imageUris: List<Uri>, overrideFormat: UploadFormat? = null) {
        if (imageUris.isEmpty()) return
        viewModelScope.launch {
            processMultiScannedImagesInternal(imageUris, overrideFormat)
        }
    }

    private suspend fun processMultiScannedImagesInternal(imageUris: List<Uri>, overrideFormat: UploadFormat? = null) {
        if (imageUris.isEmpty()) return
        postProcessing("Copying page images...")
        val queueId = java.util.UUID.randomUUID().toString()
        val format = overrideFormat ?: if (imageFormat.value.equals("PDF", true)) UploadFormat.PDF 
                     else if (imageFormat.value.equals("BOTH", true)) UploadFormat.BOTH 
                     else UploadFormat.JPEG
            
            val initialItem = QueueItem(
                id = queueId,
                personName = "New Multi-Scan",
                documentType = "Document",
                format = format,
                status = "Processing multi-scan pages...",
                phase = QueueItemPhase.PROCESSING
            )
            _activeQueue.update { it + initialItem }

            try {
                postProcessing("Copying page images...")
                updateQueueStatus(queueId, "Copying page images...", QueueItemPhase.PROCESSING)
                val pageFiles = imageUris.mapIndexed { index, uri ->
                    val file = File(context.cacheDir, "multi_page_${java.util.UUID.randomUUID()}_$index.jpeg")
                    ImageProcessor.fixImageOrientation(context, uri, file)
                }

                postProcessing("Analyzing pages with AI...")
                updateQueueStatus(queueId, "Analyzing pages...", QueueItemPhase.PROCESSING)
                val firstPageFile = pageFiles.first()
                var personName = "Unknown"
                var documentType = "Document"

                if (enableAiAnalysis.value) {
                    var analyzed = false
                    try {
                        val base64Image = encodeFileToBase64(firstPageFile)
                        val analysis = analyzeDocumentWithNvidia(base64Image)
                        if (analysis != null && analysis.personName.isNotBlank() && !analysis.personName.equals("Unknown", ignoreCase = true)) {
                            personName = sanitizePersonName(analysis.personName)
                            documentType = sanitizeDocumentType(analysis.documentType, "")
                            analyzed = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Seamless offline fallback to on-device ML Kit OCR if online analysis failed or returned Unknown
                    if (!analyzed || personName.equals("Unknown", ignoreCase = true)) {
                        try {
                            val localAnalysis = analyzeDocumentLocally(firstPageFile)
                            personName = sanitizePersonName(localAnalysis.personName)
                            documentType = sanitizeDocumentType(localAnalysis.documentType)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    try {
                        val localAnalysis = analyzeDocumentLocally(firstPageFile)
                        personName = sanitizePersonName(localAnalysis.personName)
                        documentType = sanitizeDocumentType(localAnalysis.documentType)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                _activeQueue.update { it.map { item ->
                    if (item.id == queueId) item.copy(personName = personName, documentType = documentType) else item
                } }

                val pagePaths = pageFiles.map { it.absolutePath }
                val nonIdFullPageTypes = setOf("Birth Certificate", "Death Certificate", "Marksheet")
                var isId = imageUris.size == 2 &&
                    useA4Format.value &&
                    documentType !in nonIdFullPageTypes &&
                    ImageProcessor.canPairFitOnSingleA4IdSheet(pagePaths)

                // Extra smart check when 2 pages were scanned via "Add Page":
                // If Page 2 is a full-page certificate or a DIFFERENT specific ID card than Page 1
                // (e.g. Page 1 = Birth Certificate / Aadhaar Card and Page 2 = PAN Card),
                // they are 2 separate documents, NOT Front+Back of the same card -> use 2-page PDF!
                if (isId && pageFiles.size == 2) {
                    try {
                        val secondPageAnalysis = analyzeDocumentLocally(pageFiles[1])
                        val secondDocType = sanitizeDocumentType(secondPageAnalysis.documentType)
                        if (secondDocType in nonIdFullPageTypes) {
                            isId = false
                        } else if (documentType != "Document" && secondDocType != "Document" && documentType != secondDocType) {
                            // E.g. Page 1 is Voter ID / Birth Certificate and Page 2 is PAN Card (or Page 2 is a single-sided PAN Card)
                            isId = false
                        }
                    } catch (_: Exception) {}
                }

                postProcessing("Creating preview image...")
                updateQueueStatus(queueId, "Creating preview image...", QueueItemPhase.PROCESSING)
                val combinedFile = File(context.cacheDir, "combined_multi_${java.util.UUID.randomUUID()}.jpeg")
                val resultFile = if (isId) {
                    ImageProcessor.combineImagesToA4(pagePaths, combinedFile)
                } else {
                    ImageProcessor.combineImages(pagePaths, combinedFile)
                }

                if (resultFile == null) {
                    postStatusMessage("Failed to combine images", StatusBannerTone.ERROR)
                    updateQueueStatus(queueId, "Failed: Combined empty", QueueItemPhase.FAILED)
                    return
                }

                val targetKb = targetSizeKb.value
                postProcessing("Compressing preview...")
                updateQueueStatus(queueId, "Compressing preview...", QueueItemPhase.PROCESSING)
                val compressedPreviewFile = ImageProcessor.compressImage(resultFile, targetKb, compressionCodec.value, autoEnhance = autoEnhanceEnabled.value)

                try { resultFile.delete() } catch (e: Exception) {}

                // When a multi-page scan is NOT a 1-sheet ID card pair (so it has 2+ separate pages),
                // ensure the default format is at least PDF (or BOTH) rather than single-image JPEG.
                val effectiveFormat = if (!isId && pageFiles.size > 1 && format == UploadFormat.JPEG) {
                    UploadFormat.PDF
                } else {
                    format
                }

                _activeQueue.update { it.map { item ->
                    if (item.id == queueId) item.copy(format = effectiveFormat) else item
                } }

                if (isId) {
                    pageFiles.forEach { file ->
                        try { file.delete() } catch (e: Exception) {}
                    }
                }

                if (showConfirmation.value) {
                    postStatusMessage("Multi-scan complete. Please confirm.", StatusBannerTone.SUCCESS)
                    updateQueueStatus(queueId, "Awaiting Confirmation", QueueItemPhase.AWAITING_CONFIRMATION)
                    val item = PendingDocument(
                        queueId = queueId,
                        compressedFile = compressedPreviewFile,
                        initialPersonName = personName,
                        initialDocumentType = documentType,
                        pageFiles = if (!isId) pageFiles else null,
                        initialFormat = effectiveFormat
                    )
                    _pendingDocuments.update { it + item }
                } else {
                    clearStatusMessage()
                    updateQueueStatus(queueId, "Saving automatically...", QueueItemPhase.PROCESSING)

                    viewModelScope.launch {
                        executeBackgroundUpload(
                            context,
                            queueId,
                            compressedPreviewFile,
                            personName,
                            documentType,
                            effectiveFormat,
                            pageFiles = if (!isId) pageFiles else null
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                postStatusMessage("Failed: ${e.message}", StatusBannerTone.ERROR)
                updateQueueStatus(queueId, "Failed: ${e.message}", QueueItemPhase.FAILED)
            }
        }

    fun updateAndUploadDocument(
        context: Context,
        docId: Int,
        personName: String,
        documentType: String,
        format: UploadFormat,
        newPageUris: List<Uri>? = null
    ) {
        viewModelScope.launch {
            postProcessing("Updating document...")
            val queueId = java.util.UUID.randomUUID().toString()
            
            val initialItem = QueueItem(
                id = queueId,
                personName = personName,
                documentType = documentType,
                format = format,
                status = "Updating document...",
                phase = QueueItemPhase.PROCESSING,
                docId = docId
            )
            _activeQueue.update { it + initialItem }

            try {
                val existingDoc = database.documentDao().getAllDocuments().first().find { it.id == docId }
                if (existingDoc == null) {
                    postStatusMessage("Error: Document not found in database.", StatusBannerTone.ERROR)
                    updateQueueStatus(queueId, "Failed: Not found", QueueItemPhase.FAILED)
                    return@launch
                }

                val checkedPersonName = personName.trim().ifEmpty { "Client_${System.currentTimeMillis() % 100000}" }
                val checkedDocumentType = documentType.trim().ifEmpty { "Document" }

                val oldFileName = existingDoc.fileName
                val oldBase = oldFileName.substringBeforeLast(".")
                val existingSuffix = oldBase.substringAfterLast("_", "").takeIf { it.length == 4 && it.all { c -> c.isLetterOrDigit() } }
                    ?: java.util.UUID.randomUUID().toString().take(4)

                val safePersonName = checkedPersonName.replace(" ", "_").trim()
                val safeDocumentType = checkedDocumentType.replace(" ", "_").trim()
                val newBaseName = if (nameBeforeType.value) {
                    "${safePersonName}_${safeDocumentType}_$existingSuffix"
                } else {
                    "${safeDocumentType}_${safePersonName}_$existingSuffix"
                }

                val finalJpgName = "$newBaseName.jpeg"
                val finalPdfName = "$newBaseName.pdf"
                val dbFileName = if (format == UploadFormat.PDF || format == UploadFormat.BOTH) finalPdfName else finalJpgName

                val targetLocalJpg = File(context.filesDir, finalJpgName)
                val targetLocalPdf = File(context.filesDir, finalPdfName)
                var sourceFileForUpload = File(existingDoc.localFilePath)
                var tempPageFiles: List<File>? = null
                var isId = false

                if (newPageUris != null && newPageUris.isNotEmpty()) {
                    postProcessing("Processing new page scans...")
                    updateQueueStatus(queueId, "Processing new scans...", QueueItemPhase.PROCESSING)
                    
                    tempPageFiles = newPageUris.mapIndexed { index, uri ->
                        val file = File(context.cacheDir, "edit_page_${java.util.UUID.randomUUID()}_$index.jpeg")
                        ImageProcessor.fixImageOrientation(context, uri, file)
                    }

                    val combinedFile = File(context.cacheDir, "edit_combined_${java.util.UUID.randomUUID()}.jpeg")
                    // A4 Sheet Canvas: strictly for 2 compact ID card pages (Front + Back) when the A4 toggle is enabled
                    // AND both images fit on a single A4 sheet without portrait overflow or clipping.
                    val editPagePaths = tempPageFiles.map { it.absolutePath }
                    val nonIdFullPageTypes = setOf("Birth Certificate", "Death Certificate", "Marksheet")
                    isId = newPageUris.size == 2 &&
                        useA4Format.value &&
                        checkedDocumentType !in nonIdFullPageTypes &&
                        ImageProcessor.canPairFitOnSingleA4IdSheet(editPagePaths)

                    if (isId && tempPageFiles.size == 2) {
                        try {
                            val firstType = sanitizeDocumentType(analyzeDocumentLocally(tempPageFiles[0]).documentType)
                            val secondType = sanitizeDocumentType(analyzeDocumentLocally(tempPageFiles[1]).documentType)
                            if (firstType in nonIdFullPageTypes || secondType in nonIdFullPageTypes) {
                                isId = false
                            } else if (firstType != "Document" && secondType != "Document" && firstType != secondType) {
                                isId = false
                            }
                        } catch (_: Exception) {}
                    }

                    val resultFile = if (isId) {
                        ImageProcessor.combineImagesToA4(editPagePaths, combinedFile)
                    } else {
                        ImageProcessor.combineImages(editPagePaths, combinedFile)
                    }
                    if (resultFile == null) {
                        tempPageFiles.forEach { try { it.delete() } catch (_: Exception) {} }
                        postStatusMessage("Failed to combine images.", StatusBannerTone.ERROR)
                        updateQueueStatus(queueId, "Failed: Combined empty", QueueItemPhase.FAILED)
                        return@launch
                    }

                    if (isId) {
                        tempPageFiles.forEach { try { it.delete() } catch (_: Exception) {} }
                    }

                    val targetKb = targetSizeKb.value
                    val compressedFile = ImageProcessor.compressImage(resultFile, targetKb, compressionCodec.value, autoEnhance = autoEnhanceEnabled.value)
                    try { resultFile.delete() } catch (e: Exception) {}

                    // Always write the newly combined image file and clear any old PDF so executeBackgroundUpload generates a fresh PDF
                    compressedFile.copyTo(targetLocalJpg, overwrite = true)
                    try { compressedFile.delete() } catch (e: Exception) {}
                    if (targetLocalPdf.exists()) {
                        try { targetLocalPdf.delete() } catch (e: Exception) {}
                    }
                    sourceFileForUpload = targetLocalJpg
                }

                val updatedLocalPath = if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                    targetLocalPdf.absolutePath
                } else {
                    targetLocalJpg.absolutePath
                }

                val updatedEntity = existingDoc.copy(
                    fileName = dbFileName,
                    personName = checkedPersonName,
                    documentType = checkedDocumentType,
                    localFilePath = if (File(updatedLocalPath).exists()) updatedLocalPath else sourceFileForUpload.absolutePath,
                    isUploaded = false,
                    timestamp = System.currentTimeMillis()
                )
                database.documentDao().updateDocument(updatedEntity)

                clearStatusMessage()
                updateQueueStatus(queueId, "Syncing changes...", QueueItemPhase.PROCESSING)

                viewModelScope.launch {
                    executeBackgroundUpload(
                        context = context,
                        queueId = queueId,
                        compressedFile = sourceFileForUpload,
                        checkedPersonName = checkedPersonName,
                        checkedDocumentType = checkedDocumentType,
                        format = format,
                        existingDocId = docId,
                        pageFiles = if (tempPageFiles != null && !isId) tempPageFiles else null,
                        previousFileName = oldFileName
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
                postStatusMessage("Update failed: ${e.message}", StatusBannerTone.ERROR)
                updateQueueStatus(queueId, "Failed: ${e.message}", QueueItemPhase.FAILED)
            }
        }
    }

    fun processScannedImages(imageUris: List<Uri>, pdfUri: Uri? = null) {
        processMultiScannedImages(imageUris)
    }

    fun confirmAndUpload(
        context: Context,
        personName: String,
        documentType: String,
        format: UploadFormat,
        customTargetSizeKb: Int? = null
    ) {
        val pending = _pendingDocuments.value.firstOrNull() ?: return
        _pendingDocuments.update { it.drop(1) }
        val queueId = pending.queueId
        
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

        val activeTargetSize = (customTargetSizeKb ?: targetSizeKb.value).coerceIn(10, 10000)

        // Update queue item info, format & status
        _activeQueue.update { it.map { item ->
            if (item.id == queueId) item.copy(
                personName = checkedPersonName,
                documentType = checkedDocumentType,
                format = format,
                status = "Authorizing Google...",
                phase = QueueItemPhase.PROCESSING
            ) else item
        } }

        viewModelScope.launch {
            postProcessing("Requesting Google authorization...")
            
            val email = settingsRepository.googleEmail.first()
            val folderId = settingsRepository.driveFolderId.first()

            val safePersonName = checkedPersonName.replace(" ", "_").trim()
            val safeDocumentType = checkedDocumentType.replace(" ", "_").trim()
            val suffixId = java.util.UUID.randomUUID().toString().take(4)
            val finalNameBase = if (nameBeforeType.value) {
                "${safePersonName}_${safeDocumentType}_$suffixId"
            } else {
                "${safeDocumentType}_${safePersonName}_$suffixId"
            }
            val finalJpgName = "${finalNameBase}.jpeg"
            val finalPdfName = "${finalNameBase}.pdf"
            val dbFileName = if (format == UploadFormat.PDF || format == UploadFormat.BOTH) finalPdfName else finalJpgName
            
            // Re-compress using active compressionCodec and autoEnhance when source is the raw single page
            val isFromRawSinglePage = pending.pageFiles != null && pending.pageFiles.size == 1 && pending.pageFiles.first().exists()
            val sourceImage = if (isFromRawSinglePage) {
                pending.pageFiles!!.first()
            } else {
                pending.compressedFile
            }

            val localCopy = File(context.filesDir, finalJpgName)
            try {
                if (sourceImage.exists()) {
                    val recompressed = ImageProcessor.compressImage(
                        file = sourceImage,
                        targetSizeKb = activeTargetSize,
                        format = compressionCodec.value,
                        autoEnhance = if (isFromRawSinglePage) autoEnhanceEnabled.value else false
                    )
                    recompressed.copyTo(localCopy, overwrite = true)
                    try { recompressed.delete() } catch (e: Exception) {}
                } else if (pending.compressedFile.exists()) {
                    pending.compressedFile.copyTo(localCopy, overwrite = true)
                }

                if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                    if (localCopy.exists()) {
                        ImageProcessor.exportToPublicDocuments(context, localCopy, finalJpgName, "image/jpeg")
                    }
                }
            } catch (ecop: Exception) {
                ecop.printStackTrace()
                if (!localCopy.exists() && pending.compressedFile.exists()) {
                    try { pending.compressedFile.copyTo(localCopy, overwrite = true) } catch (e: Exception) {}
                }
            }

            // PDF preparation in filesDir (PERSISTENT - never deleted)
            val localPdf = File(context.filesDir, finalPdfName)
            if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                try {
                    postProcessing("Generating and structuring PDF...")
                    updateQueueStatus(queueId, "Generating structured PDF...", QueueItemPhase.PROCESSING)
                    if (pending.pageFiles != null && pending.pageFiles.isNotEmpty()) {
                        ImageProcessor.convertToMultiPagePdf(pending.pageFiles, localPdf, activeTargetSize, autoEnhance = autoEnhanceEnabled.value)
                    } else {
                        ImageProcessor.convertToPdf(localCopy, localPdf, activeTargetSize, autoEnhance = autoEnhanceEnabled.value)
                    }
                    if (localPdf.exists()) {
                        ImageProcessor.exportToPublicDocuments(context, localPdf, finalPdfName, "application/pdf")
                    }
                } catch (epdf: Exception) {
                    epdf.printStackTrace()
                }
            }

            val primaryLocalFile = if ((format == UploadFormat.PDF || format == UploadFormat.BOTH) && localPdf.exists()) localPdf else localCopy

            // Insert locally first with exact matching path
            val insertId = try {
                val newEntity = DocumentEntity(
                    fileName = dbFileName,
                    personName = checkedPersonName,
                    documentType = checkedDocumentType,
                    localFilePath = primaryLocalFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isUploaded = false,
                    drivePath = null
                )
                val id = database.documentDao().insertDocument(newEntity)
                if (id != -1L) {
                    incrementUserScannedCount()
                    updateQueueDocId(queueId, id.toInt())
                }
                id
            } catch (edb: Exception) {
                edb.printStackTrace()
                -1L
            }

            updateQueueStatus(queueId, "Syncing directly...", QueueItemPhase.PROCESSING)

            if (email.isNullOrEmpty()) {
                postStatusMessage("Error: Please sign in to sync with Drive! Saved locally.", StatusBannerTone.WARNING)
                updateQueueStatus(queueId, "Saved locally (Sign-In required)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                try { pending.compressedFile.delete() } catch (el: Exception) {}
                pending.pageFiles?.forEach { file -> try { file.delete() } catch (e: Exception) {} }
                fetchDriveFiles(context)
                return@launch
            }

            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken == null) {
                    postStatusMessage("Failed to retrieve Google token. Saved locally.", StatusBannerTone.WARNING)
                    updateQueueStatus(queueId, "Saved locally (Token failed)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                    try { pending.compressedFile.delete() } catch (el: Exception) {}
                    pending.pageFiles?.forEach { file -> try { file.delete() } catch (e: Exception) {} }
                    fetchDriveFiles(context)
                    return@launch
                }

                var uploadParentId = folderId
                val subFolderName = targetSubfolder.value.trim()
                if (subFolderName.isNotEmpty()) {
                    postProcessing("Creating/finding target folder: $subFolderName...")
                    updateQueueStatus(queueId, "Finding target folder: $subFolderName...", QueueItemPhase.PROCESSING)
                    uploadParentId = getCachedOrFetchSubFolder(obtainedToken, subFolderName, folderId)
                }

                var isJpgUploaded = false
                var isPdfUploaded = false

                if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                    postProcessing("Uploading compressed image to Google Drive...")
                    updateQueueStatus(queueId, "Uploading image standard file...", QueueItemPhase.PROCESSING)
                    val (uploaded, freshToken) = uploadFileWithAuthRetry(
                        context = context,
                        email = email,
                        initialToken = obtainedToken,
                        onStatusUpdate = { updateQueueStatus(queueId, it, QueueItemPhase.PROCESSING) }
                    ) { token ->
                        GoogleDriveClient.uploadFile(
                            accessToken = token,
                            file = localCopy,
                            mimeType = "image/jpeg",
                            fileName = finalJpgName,
                            parentId = uploadParentId
                        )
                    }
                    isJpgUploaded = uploaded
                    if (freshToken != null) obtainedToken = freshToken
                }

                if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                    if (!localPdf.exists()) {
                        throw java.io.FileNotFoundException("PDF file not found: ${localPdf.absolutePath}")
                    }
                    postProcessing("Uploading structured PDF to Google Drive...")
                    updateQueueStatus(queueId, "Uploading PDF to Drive...", QueueItemPhase.PROCESSING)
                    val (uploaded, freshToken) = uploadFileWithAuthRetry(
                        context = context,
                        email = email,
                        initialToken = obtainedToken,
                        onStatusUpdate = { updateQueueStatus(queueId, it, QueueItemPhase.PROCESSING) }
                    ) { token ->
                        GoogleDriveClient.uploadFile(
                            accessToken = token,
                            file = localPdf,
                            mimeType = "application/pdf",
                            fileName = finalPdfName,
                            parentId = uploadParentId
                        )
                    }
                    isPdfUploaded = uploaded
                    if (freshToken != null) obtainedToken = freshToken
                }

                val overallSuccess = if (format == UploadFormat.BOTH) isJpgUploaded && isPdfUploaded 
                                     else if (format == UploadFormat.JPEG) isJpgUploaded
                                     else isPdfUploaded

                val driveFolderNameStr = settingsRepository.driveFolderName.first() ?: "Root"
                val builtDrivePath = "My Drive/$driveFolderNameStr${if (subFolderName.isNotEmpty()) "/$subFolderName" else ""}"

                if (overallSuccess && insertId != -1L) {
                    try {
                        val updatedEntity = DocumentEntity(
                            id = insertId.toInt(),
                            fileName = dbFileName,
                            personName = checkedPersonName,
                            documentType = checkedDocumentType,
                            localFilePath = primaryLocalFile.absolutePath,
                            timestamp = System.currentTimeMillis(),
                            isUploaded = true,
                            drivePath = builtDrivePath
                        )
                        database.documentDao().updateDocument(updatedEntity)
                    } catch (eup: Exception) {
                        eup.printStackTrace()
                    }
                }

                val msg = if (overallSuccess) {
                    "Success! Saved into your Google Drive"
                } else if (isJpgUploaded) {
                    "Uploaded Image to Google Drive, PDF upload failed"
                } else if (isPdfUploaded) {
                    "Uploaded PDF to Google Drive, Image upload failed"
                } else {
                    "Saved locally (Drive Upload Failed)"
                }
                postStatusMessage(
                    msg,
                    if (overallSuccess) StatusBannerTone.SUCCESS else StatusBannerTone.WARNING
                )
                updateQueueStatus(
                    queueId,
                    if (overallSuccess) "Completed" else "Saved locally (Drive fail)",
                    if (overallSuccess) QueueItemPhase.COMPLETED else QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC
                )
                
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (statusMessage.value == msg) {
                        clearStatusMessage()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val errMsg = "Error uploading to Drive: ${e.message}. Saved locally."
                postStatusMessage(errMsg, StatusBannerTone.WARNING)
                updateQueueStatus(queueId, "Saved locally (Drive fail)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                obtainedToken?.let { invalidateCachedToken(context, it) }
                
                viewModelScope.launch {
                    kotlinx.coroutines.delay(4000)
                    if (statusMessage.value == errMsg) {
                        clearStatusMessage()
                    }
                }
            } finally {
                clearProcessingIfActive()
                try { pending.compressedFile.delete() } catch (el: Exception) {}
                pending.pageFiles?.forEach { file ->
                    try { file.delete() } catch (e: Exception) {}
                }
                updatePublicFolderSize()
                fetchDriveFiles(context)
            }
        }
    }

    private suspend fun executeBackgroundUpload(
        context: Context,
        queueId: String,
        compressedFile: File,
        checkedPersonName: String,
        checkedDocumentType: String,
        format: UploadFormat,
        existingDocId: Int? = null,
        pageFiles: List<File>? = null,
        customTargetSizeKb: Int? = null,
        previousFileName: String? = null
    ) {
        var obtainedToken: String? = null
        var finalJpgName = ""
        var finalPdfName = ""
        var oldJpgName: String? = null
        var oldPdfName: String? = null
        val activeTargetSize = (customTargetSizeKb ?: targetSizeKb.value).coerceIn(10, 10000)

        try {
            val isRetry = existingDocId != null
            var existingDoc: DocumentEntity? = null
            if (isRetry) {
                existingDoc = database.documentDao().getAllDocuments().first().find { it.id == existingDocId }
                val refOldName = previousFileName ?: existingDoc?.fileName
                if (refOldName != null) {
                    val refBase = refOldName.substringBeforeLast(".")
                    val refExt = refOldName.substringAfterLast(".", "").lowercase()
                    oldPdfName = "$refBase.pdf"
                    oldJpgName = if (refExt == "webp") "$refBase.webp" else "$refBase.jpeg"
                }
            }

            val safePersonName = checkedPersonName.replace(" ", "_").trim()
            val safeDocumentType = checkedDocumentType.replace(" ", "_").trim()
            
            val finalNameBase: String
            if (isRetry && existingDoc != null) {
                finalNameBase = existingDoc.fileName.substringBeforeLast(".")
            } else {
                val suffixId = java.util.UUID.randomUUID().toString().take(4)
                finalNameBase = if (nameBeforeType.value) {
                    "${safePersonName}_${safeDocumentType}_$suffixId"
                } else {
                    "${safeDocumentType}_${safePersonName}_$suffixId"
                }
            }
            finalJpgName = "${finalNameBase}.jpeg"
            finalPdfName = "${finalNameBase}.pdf"

            val safeLocalJpg = File(context.filesDir, finalJpgName)
            val safeLocalPdf = File(context.filesDir, finalPdfName)

            // 1. Prepare safeLocalJpg if required
            if (!safeLocalJpg.exists() || safeLocalJpg.length() == 0L) {
                try {
                    val isFromRawSinglePage = pageFiles != null && pageFiles.size == 1 && pageFiles.first().exists()
                    val sourceImage = if (isFromRawSinglePage) {
                        pageFiles!!.first()
                    } else if (compressedFile.exists() && compressedFile.name.endsWith(".pdf", ignoreCase = true)) {
                        ImageProcessor.getOrCreateThumbnailFile(context, compressedFile.absolutePath) ?: compressedFile
                    } else {
                        compressedFile
                    }
                    if (sourceImage.exists() && !sourceImage.name.endsWith(".pdf", ignoreCase = true)) {
                        val recompressed = ImageProcessor.compressImage(
                            file = sourceImage,
                            targetSizeKb = activeTargetSize,
                            format = compressionCodec.value,
                            autoEnhance = if (isFromRawSinglePage) autoEnhanceEnabled.value else false
                        )
                        recompressed.copyTo(safeLocalJpg, overwrite = true)
                        try { recompressed.delete() } catch (e: Exception) {}
                    } else if (compressedFile.exists() && !compressedFile.name.endsWith(".pdf", ignoreCase = true)) {
                        compressedFile.copyTo(safeLocalJpg, overwrite = true)
                    }

                    if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                        if (safeLocalJpg.exists()) {
                            ImageProcessor.exportToPublicDocuments(context, safeLocalJpg, finalJpgName, "image/jpeg")
                        }
                    }
                } catch (ecop: Exception) {
                    ecop.printStackTrace()
                }
            }

            // 2. Prepare safeLocalPdf if PDF format
            if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                if (!safeLocalPdf.exists() || safeLocalPdf.length() == 0L) {
                    try {
                        if (compressedFile.exists() && compressedFile.name.endsWith(".pdf", ignoreCase = true) && (pageFiles == null || pageFiles.isEmpty())) {
                            // Already a valid PDF file on disk! Copy directly, never decode with BitmapFactory
                            if (compressedFile.absolutePath != safeLocalPdf.absolutePath) {
                                compressedFile.copyTo(safeLocalPdf, overwrite = true)
                            }
                        } else if (pageFiles != null && pageFiles.isNotEmpty()) {
                            ImageProcessor.convertToMultiPagePdf(pageFiles, safeLocalPdf, activeTargetSize, autoEnhance = autoEnhanceEnabled.value)
                        } else if (safeLocalJpg.exists() && safeLocalJpg.length() > 0L) {
                            ImageProcessor.convertToPdf(safeLocalJpg, safeLocalPdf, activeTargetSize, autoEnhance = autoEnhanceEnabled.value)
                        } else if (compressedFile.exists() && !compressedFile.name.endsWith(".pdf", ignoreCase = true)) {
                            ImageProcessor.convertToPdf(compressedFile, safeLocalPdf, activeTargetSize, autoEnhance = autoEnhanceEnabled.value)
                        }

                        if (safeLocalPdf.exists()) {
                            ImageProcessor.exportToPublicDocuments(context, safeLocalPdf, finalPdfName, "application/pdf")
                        }
                    } catch (ep: Exception) {
                        ep.printStackTrace()
                    }
                }
            }

            val dbFileName = if (format == UploadFormat.PDF || format == UploadFormat.BOTH) finalPdfName else finalJpgName
            val primaryLocalFile = if ((format == UploadFormat.PDF || format == UploadFormat.BOTH) && safeLocalPdf.exists()) safeLocalPdf else safeLocalJpg

            // Clean up obsolete previous local files only after confirming the replacement exists
            if (isRetry && existingDoc != null && primaryLocalFile.exists() && primaryLocalFile.length() > 0L) {
                val previousFile = File(existingDoc.localFilePath)
                if (previousFile.exists() &&
                    previousFile.absolutePath != safeLocalJpg.absolutePath &&
                    previousFile.absolutePath != safeLocalPdf.absolutePath) {
                    try { previousFile.delete() } catch (_: Exception) {}
                }
            }

            // Insert or Update local DB immediately with the actual matching file path
            // so even offline or unauthenticated edits reflect the generated PDF/JPEG path immediately
            val insertId = try {
                if (existingDocId != null) {
                    if (existingDoc != null && primaryLocalFile.exists()) {
                        database.documentDao().updateDocument(
                            existingDoc.copy(
                                fileName = dbFileName,
                                personName = checkedPersonName,
                                documentType = checkedDocumentType,
                                localFilePath = primaryLocalFile.absolutePath,
                                isUploaded = false,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    existingDocId.toLong()
                } else {
                    val newId = database.documentDao().insertDocument(
                        DocumentEntity(
                            fileName = dbFileName,
                            personName = checkedPersonName,
                            documentType = checkedDocumentType,
                            localFilePath = primaryLocalFile.absolutePath,
                            timestamp = System.currentTimeMillis(),
                            isUploaded = false,
                            drivePath = null
                        )
                    )
                    incrementUserScannedCount()
                    newId
                }
            } catch (edb: Exception) {
                edb.printStackTrace()
                existingDocId?.toLong() ?: -1L
            }

            if (insertId != -1L) {
                updateQueueDocId(queueId, insertId.toInt())
            }

            updateQueueStatus(queueId, "Authorizing Google Drive...", QueueItemPhase.PROCESSING)
            
            val email = settingsRepository.googleEmail.first()
            val folderId = settingsRepository.driveFolderId.first()
            val subFolderName = targetSubfolder.value.trim()
            
            if (email.isNullOrEmpty()) {
                updateQueueStatus(queueId, "Saved locally (Sign-In required)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                fetchDriveFiles(context)
                return
            }

            var uploadSuccess = false

            backgroundUploadMutex.withLock {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken == null) {
                    updateQueueStatus(queueId, "Saved locally (Token failed)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                    fetchDriveFiles(context)
                    return@withLock
                }

                var uploadParentId = folderId
                if (subFolderName.isNotEmpty()) {
                    updateQueueStatus(queueId, "Locating folder: $subFolderName...", QueueItemPhase.PROCESSING)
                    uploadParentId = getCachedOrFetchSubFolder(obtainedToken!!, subFolderName, folderId)
                }

                var isJpgUploaded = false
                var isPdfUploaded = false

                if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                    if (!safeLocalJpg.exists()) {
                        throw java.io.FileNotFoundException("JPEG file not found: ${safeLocalJpg.absolutePath}")
                    }
                    updateQueueStatus(queueId, "Uploading image standard file...", QueueItemPhase.PROCESSING)
                    val (uploaded, freshToken) = uploadFileWithAuthRetry(
                        context = context,
                        email = email,
                        initialToken = obtainedToken,
                        onStatusUpdate = { updateQueueStatus(queueId, it, QueueItemPhase.PROCESSING) }
                    ) { token ->
                        GoogleDriveClient.uploadFile(
                            accessToken = token,
                            file = safeLocalJpg,
                            mimeType = "image/jpeg",
                            fileName = finalJpgName,
                            parentId = uploadParentId,
                            oldFileName = oldJpgName
                        )
                    }
                    isJpgUploaded = uploaded
                    if (freshToken != null) obtainedToken = freshToken
                }

                if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                    if (!safeLocalPdf.exists()) {
                        throw java.io.FileNotFoundException("PDF file not found: ${safeLocalPdf.absolutePath}")
                    }
                    updateQueueStatus(queueId, "Uploading PDF to Drive...", QueueItemPhase.PROCESSING)
                    val (uploaded, freshToken) = uploadFileWithAuthRetry(
                        context = context,
                        email = email,
                        initialToken = obtainedToken,
                        onStatusUpdate = { updateQueueStatus(queueId, it, QueueItemPhase.PROCESSING) }
                    ) { token ->
                        GoogleDriveClient.uploadFile(
                            accessToken = token,
                            file = safeLocalPdf,
                            mimeType = "application/pdf",
                            fileName = finalPdfName,
                            parentId = uploadParentId,
                            oldFileName = oldPdfName
                        )
                    }
                    isPdfUploaded = uploaded
                    if (freshToken != null) obtainedToken = freshToken
                }

                val overallSuccess = when (format) {
                    UploadFormat.BOTH -> isJpgUploaded && isPdfUploaded
                    UploadFormat.JPEG -> isJpgUploaded
                    UploadFormat.PDF -> isPdfUploaded
                }

                val driveFolderNameStr = settingsRepository.driveFolderName.first() ?: "Root"
                val builtDrivePath = "My Drive/$driveFolderNameStr${if (subFolderName.isNotEmpty()) "/$subFolderName" else ""}"

                if (overallSuccess) {
                    if (insertId != -1L) {
                        try {
                            val updatedEntity = DocumentEntity(
                                id = insertId.toInt(),
                                fileName = dbFileName,
                                personName = checkedPersonName,
                                documentType = checkedDocumentType,
                                localFilePath = primaryLocalFile.absolutePath,
                                timestamp = System.currentTimeMillis(),
                                isUploaded = true,
                                drivePath = builtDrivePath
                            )
                            database.documentDao().updateDocument(updatedEntity)
                        } catch (eup: Exception) {
                            eup.printStackTrace()
                        }
                    }
                    fetchDriveFiles(context)
                    updateQueueStatus(queueId, "Completed", QueueItemPhase.COMPLETED)
                    uploadSuccess = true
                } else {
                    updateQueueStatus(queueId, "Saved locally (Drive fail)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                }
            }

            if (!uploadSuccess) {
                showUploadFailedNotification(context, checkedPersonName, checkedDocumentType)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            val msg = e.message ?: "Unknown error"
            updateQueueStatus(queueId, "Saved locally (Drive fail: ${msg.take(30)})", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
            showUploadFailedNotification(context, checkedPersonName, checkedDocumentType)
        } finally {
            if (existingDocId == null) {
                try { compressedFile.delete() } catch (ex: Exception) {}
            }
            // Always clean up temporary pageFiles in cacheDir (both for new scans and edited documents)
            pageFiles?.forEach { file ->
                try { file.delete() } catch (ex: Exception) {}
            }
            updatePublicFolderSize()
        }
    }

    private suspend fun uploadFileWithAuthRetry(
        context: Context,
        email: String,
        initialToken: String?,
        onStatusUpdate: (String) -> Unit,
        uploadAction: suspend (token: String) -> Boolean
    ): Pair<Boolean, String?> {
        var currentToken = initialToken ?: getAccessToken(context, email)
        if (currentToken == null) {
            return Pair(false, null)
        }

        var attempts = 0
        val maxAttempts = 3
        var backoffDelay = 1500L

        while (attempts < maxAttempts) {
            val tokenToUse = currentToken ?: break
            attempts++
            try {
                val success = uploadAction(tokenToUse)
                if (success) {
                    return Pair(true, tokenToUse)
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: ""
                android.util.Log.w("HomeViewModel", "Upload attempt $attempts failed: $errMsg", e)

                val isAuthError = errMsg.contains("401") ||
                        errMsg.contains("unauthorized", ignoreCase = true) ||
                        errMsg.contains("invalid_token", ignoreCase = true) ||
                        errMsg.contains("token", ignoreCase = true) ||
                        errMsg.contains("auth", ignoreCase = true)

                if (isAuthError) {
                    onStatusUpdate("Auth expired. Refreshing token...")
                    withContext(Dispatchers.IO) {
                        try {
                            com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, tokenToUse)
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                    val freshToken = getAccessToken(context, email)
                    if (freshToken == null) {
                        android.util.Log.e("HomeViewModel", "Failed to refresh token after auth error")
                        return Pair(false, null)
                    }
                    currentToken = freshToken
                    continue
                }

                if (attempts >= maxAttempts) {
                    throw e
                }

                onStatusUpdate("Network issue. Resuming ($attempts/$maxAttempts)...")
                kotlinx.coroutines.delay(backoffDelay)
                backoffDelay = (backoffDelay * 2).coerceAtMost(6000L)
            }
        }
        return Pair(false, currentToken)
    }

    suspend fun clearGoogleToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        try {
            com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, token)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun <T> retryIO(
        times: Int = 3,
        initialDelay: Long = 1000,
        maxDelay: Long = 6000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times - 1) {
            try {
                return block()
            } catch (e: Exception) {
                e.printStackTrace()
                kotlinx.coroutines.delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            }
        }
        return block()
    }

    fun retryUpload(context: Context, docIds: Set<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            val docs = database.documentDao().getAllDocuments().first()
            val toUpload = docs.filter { it.id in docIds && !it.isUploaded }
            
            for (doc in toUpload) {
                launch {
                    var file = File(doc.localFilePath)
                    if (!file.exists()) {
                        val fallback = File(context.filesDir, doc.fileName)
                        if (fallback.exists()) {
                            file = fallback
                        } else {
                            val baseName = doc.fileName.substringBeforeLast(".")
                            val fallbackJpg = File(context.filesDir, "$baseName.jpeg")
                            val fallbackWebp = File(context.filesDir, "$baseName.webp")
                            val fallbackPdf = File(context.filesDir, "$baseName.pdf")
                            if (fallbackPdf.exists()) {
                                file = fallbackPdf
                            } else if (fallbackWebp.exists()) {
                                file = fallbackWebp
                            } else if (fallbackJpg.exists()) {
                                file = fallbackJpg
                            }
                        }
                    }

                    if (!file.exists()) {
                        android.util.Log.e("HomeViewModel", "Retry failed: file not found on disk for doc ${doc.id}: ${doc.localFilePath}")
                        postStatusMessage("Local file missing for ${doc.personName}. Cannot retry.", StatusBannerTone.ERROR)
                        return@launch
                    }

                    val isPdf = doc.fileName.endsWith(".pdf", ignoreCase = true)
                    val uploadFormat = if (isPdf) UploadFormat.PDF else UploadFormat.JPEG
                    val existingQueueItem = _activeQueue.value.find { it.docId == doc.id }
                    val queueId = existingQueueItem?.id ?: java.util.UUID.randomUUID().toString()

                    if (existingQueueItem != null) {
                        _activeQueue.update { list ->
                            list.map { item ->
                                if (item.id == queueId) {
                                    item.copy(
                                        personName = doc.personName,
                                        documentType = doc.documentType,
                                        status = "Retrying upload...",
                                        phase = QueueItemPhase.PROCESSING,
                                        format = uploadFormat
                                    )
                                } else item
                            }
                        }
                    } else {
                        _activeQueue.update { it + QueueItem(
                            id = queueId,
                            personName = doc.personName,
                            documentType = doc.documentType,
                            status = "Retrying upload...",
                            phase = QueueItemPhase.PROCESSING,
                            format = uploadFormat,
                            docId = doc.id
                        ) }
                    }

                    executeBackgroundUpload(
                        context = context,
                        queueId = queueId,
                        compressedFile = file,
                        checkedPersonName = doc.personName,
                        checkedDocumentType = doc.documentType,
                        format = uploadFormat,
                        existingDocId = doc.id
                    )
                }
            }
        }
    }

    fun mergeDocumentsToPdf(context: Context, docIds: Set<Int>, fileName: String, folderId: String?, folderName: String, targetSizeKb: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val queueId = java.util.UUID.randomUUID().toString()
            try {
                val docs = database.documentDao().getAllDocuments().first()
                val selectedDocs = docs.filter { it.id in docIds }.sortedBy { it.timestamp }
                
                if (selectedDocs.isEmpty()) return@launch
                
                val cleanDisplayTitle = fileName.trim().removeSuffix(".pdf").removeSuffix(".PDF").trim().ifEmpty { "Merged_Document" }
                postProcessing("Merging ${selectedDocs.size} documents into PDF...")
                
                _activeQueue.update { it + QueueItem(
                    id = queueId,
                    personName = cleanDisplayTitle,
                    documentType = "Merged PDF (${selectedDocs.size} docs)",
                    status = "Starting...",
                    phase = QueueItemPhase.PROCESSING,
                    format = UploadFormat.PDF
                )}
                
                val tempDir = File(context.cacheDir, "merge_extract_${System.currentTimeMillis()}").apply { mkdirs() }
                val filesToMerge = mutableListOf<File>()

                for (doc in selectedDocs) {
                    val file = File(doc.localFilePath)
                    if (!file.exists()) continue
                    if (file.name.endsWith(".pdf", ignoreCase = true) || doc.fileName.endsWith(".pdf", ignoreCase = true)) {
                        val extracted = ImageProcessor.extractPagesFromPdf(context, file, tempDir)
                        filesToMerge.addAll(extracted)
                    } else {
                        filesToMerge.add(file)
                    }
                }
                
                if (filesToMerge.isEmpty()) {
                    postStatusMessage("No valid documents selected to merge.", StatusBannerTone.ERROR)
                    updateQueueStatus(queueId, "Failed: No documents", QueueItemPhase.FAILED)
                    try { tempDir.deleteRecursively() } catch (e: Exception) {}
                    return@launch
                }
                
                val finalFileName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
                val pdfFile = File(context.cacheDir, "merged_${java.util.UUID.randomUUID()}.pdf")
                
                updateQueueStatus(queueId, "Generating PDF...", QueueItemPhase.PROCESSING)
                try {
                    val result = ImageProcessor.convertToMultiPagePdf(filesToMerge, pdfFile, targetSizeKb, autoEnhance = autoEnhanceEnabled.value)
                    try { tempDir.deleteRecursively() } catch (e: Exception) {}
                    if (result == null || !pdfFile.exists() || pdfFile.length() == 0L) {
                        postStatusMessage("Failed to create merged PDF (Try increasing target size).", StatusBannerTone.ERROR)
                        updateQueueStatus(queueId, "Failed: Generation error", QueueItemPhase.FAILED)
                        return@launch
                    }
                    
                    val safeLocalCopy = File(context.filesDir, "merged_${java.util.UUID.randomUUID()}.pdf")
                    pdfFile.inputStream().use { input ->
                        safeLocalCopy.outputStream().use { output -> input.copyTo(output) }
                    }
                    
                    ImageProcessor.exportToPublicDocuments(context, safeLocalCopy, finalFileName, "application/pdf")
                    
                    var isUploaded = false
                    val email = googleEmail.value
                    if (!email.isNullOrEmpty()) {
                        updateQueueStatus(queueId, "Uploading PDF to Drive...", QueueItemPhase.PROCESSING)
                        val uploadParentId = folderId ?: driveFolderId.value
                        
                        val (uploaded, _) = uploadFileWithAuthRetry(
                            context = context,
                            email = email,
                            initialToken = null,
                            onStatusUpdate = { updateQueueStatus(queueId, it, QueueItemPhase.PROCESSING) }
                        ) { token ->
                            GoogleDriveClient.uploadFile(
                                accessToken = token,
                                file = safeLocalCopy,
                                mimeType = "application/pdf",
                                fileName = finalFileName,
                                parentId = uploadParentId
                            )
                        }
                        isUploaded = uploaded
                    }
                    
                    val defaultDriveFolderNameStr = settingsRepository.driveFolderName.first() ?: "Root"
                    val effectiveFolderName = folderName.trim().ifEmpty { defaultDriveFolderNameStr }
                    val builtDrivePath = if (isUploaded) "My Drive/$effectiveFolderName" else null

                    val mergedEntity = DocumentEntity(
                        fileName = finalFileName,
                        personName = cleanDisplayTitle,
                        documentType = "Merged PDF",
                        localFilePath = safeLocalCopy.absolutePath,
                        isUploaded = isUploaded,
                        drivePath = builtDrivePath,
                        timestamp = System.currentTimeMillis()
                    )
                    val id = database.documentDao().insertDocument(mergedEntity)
                    if (id != -1L) {
                        incrementUserScannedCount()
                        updateQueueDocId(queueId, id.toInt())
                    }
                    
                    val mergeSuccessPhase = if (isUploaded || email.isNullOrEmpty()) QueueItemPhase.COMPLETED else QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC
                    val mergeStatusLabel = if (isUploaded || email.isNullOrEmpty()) "Completed - Merged successfully!" else "Saved locally (Drive fail)"
                    updateQueueStatus(queueId, mergeStatusLabel, mergeSuccessPhase)
                    postStatusMessage(
                        if (isUploaded || email.isNullOrEmpty()) "Merged successfully!" else "Merged locally (Drive upload failed)",
                        if (isUploaded || email.isNullOrEmpty()) StatusBannerTone.SUCCESS else StatusBannerTone.WARNING
                    )
                    updatePublicFolderSize()
                    if (isUploaded) {
                        fetchDriveFiles(context)
                    }
                } finally {
                    // BUG FIX: always clean up temp pdfFile from cacheDir to avoid cache bloat
                    try { pdfFile.delete() } catch (ed: Exception) {}
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                postStatusMessage("Merge failed: ${e.message}", StatusBannerTone.ERROR)
                updateQueueStatus(queueId, "Failed: ${e.message}", QueueItemPhase.FAILED)
            } finally {
                clearProcessingIfActive()
            }
        }
    }

    fun uploadInBackground(
        context: Context,
        personName: String,
        documentType: String,
        format: UploadFormat,
        customTargetSizeKb: Int? = null
    ) {
        val pending = _pendingDocuments.value.firstOrNull() ?: return
        _pendingDocuments.update { it.drop(1) } // dismiss dialog immediately to allow continued scanning

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

        val queueId = pending.queueId

        // Update existing queue item status
        _activeQueue.update { it.map { item ->
            if (item.id == queueId) item.copy(
                personName = checkedPersonName,
                documentType = checkedDocumentType,
                format = format,
                status = "Queued...",
                phase = QueueItemPhase.PROCESSING
            ) else item
        } }

        viewModelScope.launch {
            executeBackgroundUpload(
                context,
                queueId,
                pending.compressedFile,
                checkedPersonName,
                checkedDocumentType,
                format,
                pageFiles = pending.pageFiles,
                customTargetSizeKb = customTargetSizeKb
            )
        }
    }

    private fun updateQueueStatus(
        id: String,
        status: String,
        phase: QueueItemPhase = inferQueuePhaseFromStatus(status)
    ) {
        _activeQueue.update { currentList ->
            currentList.map { item ->
                if (item.id == id) item.copy(status = status, phase = phase) else item
            }
        }
    }

    private fun inferQueuePhaseFromStatus(status: String): QueueItemPhase = when {
        status.equals("Cancelled", ignoreCase = true) -> QueueItemPhase.CANCELLED
        status.equals("Awaiting Confirmation", ignoreCase = true) -> QueueItemPhase.AWAITING_CONFIRMATION
        status.startsWith("Completed", ignoreCase = true) -> QueueItemPhase.COMPLETED
        status.startsWith("Saved locally", ignoreCase = true) -> QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC
        status.startsWith("Failed", ignoreCase = true) -> QueueItemPhase.FAILED
        else -> QueueItemPhase.PROCESSING
    }

    private fun updateQueueDocId(id: String, docId: Int) {
        _activeQueue.update { currentList ->
            currentList.map { item ->
                if (item.id == id) item.copy(docId = docId) else item
            }
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
        val apiKey = try {
            com.example.BuildConfig::class.java.getField("NVIDIA_API_KEY").get(null) as? String
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: "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"
        
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
            You are an expert document OCR engine. Analyze this document image and classify it precisely.
            Detect common Indian IDs like Voter ID, Aadhaar Card, PAN Card, Passport, Driving License, or generic ones like Bill, Marksheet.
            Crucially distinguish between Voter ID (Election Commission, EPIC) and Aadhaar (UIDAI, Unique Identification).
            Extract the primary person's exact name on the document. Do not capture labels like "Name:", "Father's Name:", or structural text.
            Return a JSON object strictly matching this schema:
            {
                "personName": "Extracted Name",
                "documentType": "Extracted Document Type (e.g. Voter ID, Aadhaar Card)"
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
            "gemini-2.0-flash-lite"  // BUG FIX: was "gemini-3.5-flash" which doesn't exist
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
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            var inSampleSize = 1
            val reqSize = 1024
            val maxDim = maxOf(options.outWidth, options.outHeight)
            while (maxDim / (inSampleSize * 2) >= reqSize) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            var bmp = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bmp == null) {
                val bytes = file.readBytes()
                return Base64.encodeToString(bytes, Base64.NO_WRAP)
            }

            if (bmp.width > reqSize || bmp.height > reqSize) {
                val scale = reqSize.toFloat() / maxOf(bmp.width, bmp.height)
                val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
                val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                if (scaled != bmp) {
                    bmp.recycle()
                    bmp = scaled
                }
            }

            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, stream)
            bmp.recycle()
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    private suspend fun analyzeDocumentLocally(imageFile: File): DocumentAnalysisResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            var bitmap: Bitmap? = null
            try {
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(imageFile.absolutePath, options)

                var inSampleSize = 1
                val reqWidth = 1400
                val reqHeight = 1400
                val height = options.outHeight
                val width = options.outWidth
                if (height > reqHeight || width > reqWidth) {
                    val halfHeight = height / 2
                    val halfWidth = width / 2
                    while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                        inSampleSize *= 2
                    }
                }
                options.inSampleSize = inSampleSize
                options.inJustDecodeBounds = false

                bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)
                if (bitmap == null) {
                    continuation.resume(DocumentAnalysisResult("Unknown", "Document"))
                    return@suspendCancellableCoroutine
                }

                val rotationDegrees = try {
                    val exif = android.media.ExifInterface(imageFile.absolutePath)
                    when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } catch (e: Exception) {
                    0
                }

                val image = InputImage.fromBitmap(bitmap, rotationDegrees)

                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (continuation.isActive) {
                            // Sort OCR lines spatially (top-to-bottom with 16px row tolerance, then left-to-right)
                            // so two-column Indian ID cards (Voter ID, Aadhaar, DL, Passport) read in true visual order.
                            val sortedLines = visionText.textBlocks
                                .flatMap { it.lines }
                                .sortedWith { a, b ->
                                    val boxA = a.boundingBox
                                    val boxB = b.boundingBox
                                    if (boxA != null && boxB != null) {
                                        val topDiff = boxA.top - boxB.top
                                        if (kotlin.math.abs(topDiff) <= 16) {
                                            boxA.left.compareTo(boxB.left)
                                        } else {
                                            topDiff
                                        }
                                    } else {
                                        0
                                    }
                                }
                                .map { it.text.trim() }
                                .filter { it.isNotEmpty() }

                            val spatiallyOrderedText = if (sortedLines.isNotEmpty()) {
                                sortedLines.joinToString("\n")
                            } else {
                                visionText.text
                            }
                            val result = try {
                                extractLocalDetails(spatiallyOrderedText)
                            } catch (e: Exception) {
                                DocumentAnalysisResult("Unknown", "Document")
                            }
                            continuation.resume(result)
                        }
                        try { bitmap?.recycle() } catch (ex: Exception) {}
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        if (continuation.isActive) {
                            continuation.resume(DocumentAnalysisResult("Unknown", "Document"))
                        }
                        try { bitmap?.recycle() } catch (ex: Exception) {}
                    }
            } catch (e: Throwable) {
                e.printStackTrace()
                if (continuation.isActive) {
                    continuation.resume(DocumentAnalysisResult("Unknown", "Document"))
                }
                try { bitmap?.recycle() } catch (ex: Exception) {}
            }
        }
    }

    private fun extractLocalDetails(fullText: String): DocumentAnalysisResult {
        var personName = "Unknown"
        var documentType = "Document"

        val lowercaseText = fullText.lowercase()
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // 1. Multi-Signal Weighted Document Classification
        var aadhaarScore = 0
        var panScore = 0
        var voterScore = 0
        var dlScore = 0
        var passportScore = 0
        var marksheetScore = 0
        var rationScore = 0
        var birthCertScore = 0
        var deathCertScore = 0

        if (lowercaseText.contains("birth certificate") || lowercaseText.contains("certificate of birth")) birthCertScore += 22
        if (lowercaseText.contains("registration of births") || lowercaseText.contains("births & deaths") || lowercaseText.contains("births and deaths")) birthCertScore += 14
        if (lowercaseText.contains("sub-registrar (birth") || lowercaseText.contains("registrar of birth") || lowercaseText.contains("form-5") || lowercaseText.contains("form no. 5")) birthCertScore += 10

        if (lowercaseText.contains("death certificate") || lowercaseText.contains("certificate of death")) deathCertScore += 22
        if (lowercaseText.contains("date of death") || lowercaseText.contains("place of death") || lowercaseText.contains("form-6") || lowercaseText.contains("form no. 6")) deathCertScore += 12

        if (Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b").containsMatchIn(fullText)) aadhaarScore += 12
        if (lowercaseText.contains("aadhaar") || lowercaseText.contains("aadhar") || lowercaseText.contains("adhar")) aadhaarScore += 10
        if (lowercaseText.contains("unique identification") || lowercaseText.contains("uidai")) aadhaarScore += 8
        if (fullText.contains("आधार")) aadhaarScore += 5
        if (lowercaseText.contains("mera aadhaar") || lowercaseText.contains("meri pehchaan") || lowercaseText.contains("enrolment no")) aadhaarScore += 5

        if (Regex("\\b[A-Za-z]{5}[0-9]{4}[A-Za-z]\\b").containsMatchIn(fullText)) panScore += 12
        if (lowercaseText.contains("permanent account number") || lowercaseText.contains("permanent account")) panScore += 10
        if (lowercaseText.contains("income tax department") || lowercaseText.contains("income tax")) panScore += 10

        if (lowercaseText.contains("election commission of india") || lowercaseText.contains("election commission")) voterScore += 12
        if (lowercaseText.contains("elector photo identity card") || lowercaseText.contains("elector's photo identity") || lowercaseText.contains("identity card")) voterScore += 10
        if (lowercaseText.contains("voter id") || lowercaseText.contains("voter card") || lowercaseText.contains("elector's name") || lowercaseText.contains("elector name")) voterScore += 10
        if (Regex("\\b[A-Za-z]{3}[0-9]{7}\\b").containsMatchIn(fullText) || Regex("\\b[A-Za-z]{2}/\\d{2}/\\d{3}/\\d{6}\\b").containsMatchIn(fullText)) voterScore += 8
        if (fullText.contains("निर्वाचन") || fullText.contains("मतदाता")) voterScore += 6

        if (lowercaseText.contains("driving licence") || lowercaseText.contains("driving license")) dlScore += 12
        if (lowercaseText.contains("dl no") || lowercaseText.contains("authorization to drive") || lowercaseText.contains("motor vehicles")) dlScore += 10
        if (lowercaseText.contains("transport department") || lowercaseText.contains("union of india")) dlScore += 6

        if (lowercaseText.contains("republic of india") && lowercaseText.contains("passport")) passportScore += 14
        if (lowercaseText.contains("passport no") || lowercaseText.contains("given name") || Regex("P<[A-Z]{3}[A-Z<]{10,}").containsMatchIn(fullText)) passportScore += 12

        if (lowercaseText.contains("marksheet") || lowercaseText.contains("mark sheet") || lowercaseText.contains("marks statement")) marksheetScore += 12
        if (lowercaseText.contains("board of") && lowercaseText.contains("examination")) marksheetScore += 10

        if (lowercaseText.contains("ration card") || lowercaseText.contains("smart ration card")) rationScore += 12
        if (lowercaseText.contains("department of food") || lowercaseText.contains("food and civil supplies")) rationScore += 8

        val scores = mapOf(
            "Birth Certificate" to birthCertScore,
            "Death Certificate" to deathCertScore,
            "Aadhaar Card" to aadhaarScore,
            "PAN Card" to panScore,
            "Voter ID" to voterScore,
            "Driving License" to dlScore,
            "Passport" to passportScore,
            "Marksheet" to marksheetScore,
            "Ration Card" to rationScore
        )

        val bestMatch = scores.maxByOrNull { it.value }
        if (bestMatch != null && bestMatch.value >= 4) {
            documentType = bestMatch.key
        }

        // Helper: strips leading labels, trailing 2-column form fields, honorifics, and punctuation from a candidate name line
        fun cleanCandidateName(raw: String): String {
            var s = raw.trim(':', '-', ' ', '=', '|', '।', '.', ',', '/', ';', '_')
            // Strip any inline label prefix
            val labelRegex = Regex("^(?:elector'?s?\\s*name|card\\s*holder'?s?\\s*name|holder'?s?\\s*name|name\\s*of\\s*holder|name\\s*of\\s*candidate|name\\s*of\\s*child|child'?s?\\s*name|student'?s?\\s*name|full\\s*name|given\\s*name(?:\\(s\\))?|surname|name)\\s*[:\\-=|।.]*\\s*", RegexOption.IGNORE_CASE)
            s = s.replace(labelRegex, "").trim(':', '-', ' ', '=', '|', '।', '.', ',', '/', ';', '_')
            // Strip any trailing second-column field on the same line (e.g., "RAMJOY ROY   GENDER : MALE" on 2-column certificates)
            val trailingColumnRegex = Regex("\\b(?:gender|sex|male|female|transgender|date\\s*of\\s*birth|dob|yob|place\\s*of\\s*birth|age|blood\\s*group|nationality|religion|caste|certificate\\s*no|registration\\s*no|epic|aadhaar|aadhar)\\b.*$", RegexOption.IGNORE_CASE)
            s = s.replace(trailingColumnRegex, "").trim(':', '-', ' ', '=', '|', '।', '.', ',', '/', ';', '_')
            // Strip common Indian honorific titles at the start so the pure person name remains
            val titleRegex = Regex("^(?:shri|smt|sri|kumari|km|mr|mrs|ms|dr|late)\\.?\\s+", RegexOption.IGNORE_CASE)
            s = s.replace(titleRegex, "").trim()
            // Keep only ASCII letters and spaces
            s = s.replace(Regex("[^a-zA-Z\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            return s
        }

        fun isParentOrRelationLabel(lineLower: String): Boolean {
            return lineLower.contains("father") ||
                   lineLower.contains("mother") ||
                   lineLower.contains("husband") ||
                   lineLower.contains("spouse") ||
                   lineLower.contains("wife") ||
                   lineLower.contains("guardian") ||
                   lineLower.contains("relation") ||
                   lineLower.contains("care of") ||
                   Regex("\\b(?:s/o|d/o|w/o|c/o|s/w/d)\\b").containsMatchIn(lineLower)
        }

        // 2. Document-Specific High-Precision Name Extraction
        var foundName = ""

        // ─── A. PASSPORT (MRZ + Given Name / Surname Combiner) ───
        if (documentType == "Passport" || Regex("P<[A-Z]{3}[A-Z<]{10,}").containsMatchIn(fullText)) {
            // 1. Check ICAO MRZ line: P<INDPAUL<<SUBHOJIT<<<<<<<<<<<<
            val mrzMatch = Regex("P<[A-Z]{3}([A-Z]+)<<([A-Z<]+)").find(fullText.replace(" ", ""))
            if (mrzMatch != null) {
                val surname = mrzMatch.groupValues[1].replace("<", " ").trim()
                val givenNames = mrzMatch.groupValues[2].split("<").filter { it.isNotBlank() }.joinToString(" ").trim()
                val combined = "$givenNames $surname".trim()
                if (isValidLatinName(combined) && !isBlacklistedLine(combined)) {
                    foundName = combined
                }
            }
            // 2. Visual Inspection Zone fallback: Surname + Given Name(s)
            if (foundName.isEmpty()) {
                var surname = ""
                var givenName = ""
                for (i in lines.indices) {
                    val low = lines[i].lowercase()
                    if (low.contains("surname") && !low.contains("given")) {
                        val after = cleanCandidateName(lines[i].substringAfterLast("surname", lines[i].substringAfterLast("Surname", "")))
                        if (isValidLatinName(after) && !isBlacklistedLine(after)) {
                            surname = after
                        } else if (i + 1 < lines.size) {
                            val next = cleanCandidateName(lines[i + 1])
                            if (isValidLatinName(next) && !isBlacklistedLine(next)) surname = next
                        }
                    } else if (low.contains("given name")) {
                        val after = cleanCandidateName(lines[i].substringAfterLast("name(s)", lines[i].substringAfterLast("name", "")))
                        if (isValidLatinName(after) && !isBlacklistedLine(after)) {
                            givenName = after
                        } else if (i + 1 < lines.size) {
                            val next = cleanCandidateName(lines[i + 1])
                            if (isValidLatinName(next) && !isBlacklistedLine(next)) givenName = next
                        }
                    }
                }
                val combined = listOf(givenName, surname).filter { it.isNotBlank() }.joinToString(" ").trim()
                if (isValidLatinName(combined) && !isBlacklistedLine(combined)) {
                    foundName = combined
                }
            }
        }

        // ─── B. VOTER ID (Old & New EPIC Layouts) ───
        if (foundName.isEmpty() && documentType == "Voter ID") {
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                // Strictly match the Elector's Name / Name field and NEVER Father's / Husband's / Mother's / Relation's Name
                val isElectorNameField = (lineLower.contains("elector's name") ||
                                          lineLower.contains("elector name") ||
                                          lineLower.contains("electors name") ||
                                          (lineLower.contains("name") && !isParentOrRelationLabel(lineLower)))
                if (isElectorNameField && !isParentOrRelationLabel(lineLower)) {
                    val idx = lineLower.lastIndexOf("name")
                    if (idx != -1) {
                        val rawAfter = lines[i].substring(idx + 4)
                        val candidateAfter = cleanCandidateName(rawAfter)
                        if (isValidLatinName(candidateAfter) && !isBlacklistedLine(candidateAfter)) {
                            foundName = candidateAfter
                            break
                        }
                    }
                    // Look ahead up to 3 lines (in case line i+1 is ':' or Hindi/regional script, and line i+2 is the English name)
                    for (offset in 1..3) {
                        if (i + offset >= lines.size) break
                        val lookaheadLine = lines[i + offset]
                        val lookaheadLower = lookaheadLine.lowercase()
                        // Stop looking ahead if we hit Father's/Husband's/Mother's Name or Sex/Age/DOB
                        if (isParentOrRelationLabel(lookaheadLower) ||
                            lookaheadLower.contains("sex") ||
                            lookaheadLower.contains("age") ||
                            lookaheadLower.contains("date of birth") ||
                            lookaheadLower.contains("dob")) {
                            break
                        }
                        val candidateNext = cleanCandidateName(lookaheadLine)
                        if (isValidLatinName(candidateNext) && !isBlacklistedLine(candidateNext)) {
                            foundName = candidateNext
                            break
                        }
                    }
                    if (foundName.isNotEmpty()) break
                }
            }
        }

        // ─── C. PAN CARD (Labeled & Unlabeled Positional Layouts) ───
        if (foundName.isEmpty() && documentType == "PAN Card") {
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                if (lineLower.contains("name") && !isParentOrRelationLabel(lineLower)) {
                    val idx = lineLower.lastIndexOf("name")
                    val after = cleanCandidateName(lines[i].substring(idx + 4))
                    if (isValidLatinName(after) && !isBlacklistedLine(after)) {
                        foundName = after
                        break
                    }
                    for (offset in 1..2) {
                        if (i + offset < lines.size) {
                            val nextLine = lines[i + offset]
                            if (isParentOrRelationLabel(nextLine.lowercase())) break
                            val candidate = cleanCandidateName(nextLine)
                            if (isValidLatinName(candidate) && !isBlacklistedLine(candidate)) {
                                foundName = candidate
                                break
                            }
                        }
                    }
                    if (foundName.isNotEmpty()) break
                }
            }
            // Positional fallback for older PAN cards without "Name" label:
            // Collect valid uppercase English name lines above DOB / Permanent Account Number; first is Holder, second is Father
            if (foundName.isEmpty()) {
                val candidateNames = mutableListOf<String>()
                for (line in lines) {
                    val low = line.lowercase()
                    if (low.contains("permanent account") || low.contains("date of birth") || Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b").containsMatchIn(line)) {
                        break
                    }
                    val cleaned = cleanCandidateName(line)
                    if (isValidLatinName(cleaned) && !isBlacklistedLine(cleaned) && !isParentOrRelationLabel(low)) {
                        candidateNames.add(cleaned)
                    }
                }
                if (candidateNames.isNotEmpty()) {
                    foundName = candidateNames.first()
                }
            }
        }

        // ─── D. AADHAAR CARD (Bilingual English Line Above DOB / YOB / S/O) ───
        if (foundName.isEmpty() && documentType == "Aadhaar Card") {
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                if (lineLower.contains("dob") ||
                    lineLower.contains("date of birth") ||
                    lineLower.contains("year of birth") ||
                    lineLower.contains("yob") ||
                    Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b").containsMatchIn(lines[i])) {
                    // Search 1 to 3 lines upward for the English Latin name (skipping regional script lines)
                    for (up in 1..3) {
                        if (i - up >= 0) {
                            val prevLine = lines[i - up]
                            if (isParentOrRelationLabel(prevLine.lowercase())) continue
                            val candidate = cleanCandidateName(prevLine)
                            if (isValidLatinName(candidate) && !isBlacklistedLine(candidate)) {
                                foundName = candidate
                                break
                            }
                        }
                    }
                    if (foundName.isNotEmpty()) break
                }
            }
            // Secondary Aadhaar check: line immediately above S/O, D/O, W/O, C/O
            if (foundName.isEmpty()) {
                for (i in 1 until lines.size) {
                    val lineLower = lines[i].lowercase()
                    if (Regex("\\b(?:s/o|d/o|w/o|c/o)\\b").containsMatchIn(lineLower)) {
                        for (up in 1..2) {
                            if (i - up >= 0) {
                                val candidate = cleanCandidateName(lines[i - up])
                                if (isValidLatinName(candidate) && !isBlacklistedLine(candidate)) {
                                    foundName = candidate
                                    break
                                }
                            }
                        }
                        if (foundName.isNotEmpty()) break
                    }
                }
            }
        }

        // ─── E. UNIVERSAL LABELED FIELD SEARCH (DL, Marksheet, Ration Card, Fallback) ───
        if (foundName.isEmpty()) {
            val nameIndicators = listOf(
                "elector's name", "elector name", "card holder name", "holder's name", "holder name",
                "name of holder", "candidate's name", "name of candidate", "student's name", "student name",
                "full name", "given name(s)", "given name", "name"
            )
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                if (isParentOrRelationLabel(lineLower)) continue

                for (ind in nameIndicators) {
                    if (lineLower.startsWith(ind) || lineLower.contains("$ind:") || lineLower.contains("$ind :") || lineLower.contains("$ind-") || lineLower.contains("$ind =")) {
                        val idx = lineLower.indexOf(ind)
                        if (idx != -1) {
                            val afterInd = cleanCandidateName(lines[i].substring(idx + ind.length))
                            if (isValidLatinName(afterInd) && !isBlacklistedLine(afterInd)) {
                                foundName = afterInd
                                break
                            }
                        }
                        for (offset in 1..2) {
                            if (i + offset < lines.size) {
                                val nextLine = lines[i + offset]
                                if (isParentOrRelationLabel(nextLine.lowercase())) break
                                val candidate = cleanCandidateName(nextLine)
                                if (isValidLatinName(candidate) && !isBlacklistedLine(candidate)) {
                                    foundName = candidate
                                    break
                                }
                            }
                        }
                        if (foundName.isNotEmpty()) break
                    }
                }
                if (foundName.isNotEmpty()) break
            }
        }

        // ─── F. POSITIONAL UPPERCASE / TITLECASE NAME HEURISTIC FALLBACK ───
        if (foundName.isEmpty()) {
            for (line in lines) {
                if (isParentOrRelationLabel(line.lowercase())) continue
                val cleaned = cleanCandidateName(line)
                val words = cleaned.split("\\s+".toRegex()).filter { it.isNotBlank() }
                if (words.size in 2..4 && cleaned.length in 5..36) {
                    if (isValidLatinName(cleaned) && !isBlacklistedLine(cleaned)) {
                        foundName = cleaned
                        break
                    }
                }
            }
        }

        if (foundName.isNotEmpty()) {
            personName = foundName.split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { char -> char.titlecase() }
                }
        }

        val sanName = sanitizePersonName(personName)
        val sanType = sanitizeDocumentType(documentType, fullText)
        return DocumentAnalysisResult(personName = sanName, documentType = sanType)
    }

    private fun sanitizePersonName(detectedName: String): String {
        var cleaned = detectedName.trim()
        if (cleaned.isEmpty() || cleaned.lowercase() == "unknown") {
            return "Unknown"
        }

        val prefixes = listOf(
            "card holder name", "cardholder name", "name of holder", "holder name",
            "card holder", "cardholder", "head of family", "relation name", "full name",
            "father's name", "father name", "mother's name", "mother name", "husband's name",
            "husband name", "नाम", "elector's name", "elector name", "electors name", "name"
        )

        for (pref in prefixes) {
            val lowerCleaned = cleaned.lowercase()
            if (lowerCleaned.startsWith(pref)) {
                cleaned = cleaned.substring(pref.length).trim(':', '-', ' ', '=', '।', '/', ',', '.')
                break
            }
        }

        cleaned = cleaned.replace(Regex("^(?:shri|smt|sri|kumari|km|mr|mrs|ms|dr|late)\\.?\\s+", RegexOption.IGNORE_CASE), "").trim()

        val nameLower = cleaned.lowercase()
        if (nameLower.isEmpty() || nameLower == "unknown") {
            return "Unknown"
        }

        val blacklist = listOf(
            "card holder", "cardholder", "holder name", "name of holder", "head of family", "father's name", "father name", "father", "mother", "mother's name", "husband", "spouse", "parent",
            "voter id", "aadhaar", "aadhar", "pan card", "passport", "driving license", "driving licence", "marksheet", "ration card",
            "signature", "photo", "thumb impression", "impression", "relation", "relationship", "address", "government", "government of india",
            "income tax", "department", "election commission", "male", "female", "transgender", "sex", "sex male", "sex female", "yob", "dob", "date of birth", "place of birth",
            "authority", "uidai", "epic", "serial no", "card no", "unique", "identification", "republic of india", "resident of", "to permanent",
            "permanent account", "tax department", "transport department", "school board", "elector photo", "identity card", "marks statement", "roll no", "examination"
        )

        for (black in blacklist) {
            if (nameLower == black || nameLower.startsWith("$black ") || (nameLower.length <= black.length + 4 && nameLower.contains(black))) {
                return "Unknown"
            }
        }

        if (!isValidLatinName(cleaned)) return "Unknown"

        val words = cleaned.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return "Unknown"
        if (cleaned.length <= 2) return "Unknown"

        return words.joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { c -> c.titlecase() }
        }
    }

    private fun sanitizeDocumentType(detectedType: String, keywordText: String = ""): String {
        val typeLower = detectedType.trim().lowercase()
        val textLower = keywordText.lowercase()

        // Check Birth / Death Certificate first because certificates often list parents' EPIC / Aadhaar numbers as identity proof
        if (typeLower.contains("birth certificate") || typeLower.contains("certificate of birth") ||
            textLower.contains("birth certificate") || textLower.contains("certificate of birth") ||
            (textLower.contains("registration of births") && !textLower.contains("death certificate"))) {
            return "Birth Certificate"
        }
        if (typeLower.contains("death certificate") || typeLower.contains("certificate of death") ||
            textLower.contains("death certificate") || textLower.contains("certificate of death")) {
            return "Death Certificate"
        }
        if (typeLower.contains("aadhaar") || typeLower.contains("aadhar") || textLower.contains("aadhaar") || textLower.contains("aadhar") || textLower.contains("unique identification") || textLower.contains("uidai")) {
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

        if (typeLower.contains("identity")) {
            return "Voter ID"
        }

        return detectedType.trim().split(" ").filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.ifEmpty { "Document" }
    }

    private fun isBlacklistedLine(line: String): Boolean {
        val lower = line.lowercase().trim()
        if (lower.length < 3) return true
        val blacklists = listOf(
            "government", "india", "income tax", "permanent", "department", "election", "commission", "signature", "card",
            "father", "mother", "husband", "spouse", "address", "photo", "licence", "license", "republic", "citizen", "national",
            "state", "district", "union", "authority", "unique", "identification", "school", "board", "voter", "birth", "date", "no",
            "delhi", "mumbai", "kolkata", "chennai", "road", "street", "lane", "floor", "house", "flat", "office", "post", "bazar", "nagar",
            "city", "town", "village", "taluk", "tehsil", "dist", "pin", "code", "phone", "mobile", "tel", "email", "web", "site",
            "issue", "expiry", "holder", "assembly", "elector", "marksheet", "certificate", "examined", "roll", "marks", "grades",
            "uidai", "mera aadhaar", "meri pehchaan", "sex", "male", "female", "transgender", "gender", "age", "dob", "yob", "blood", "group",
            "valid", "till", "from", "non", "transport", "enrolment", "download", "virtual", "vid", "west", "bengal", "maharashtra", "bihar", "uttar", "pradesh"
        )
        return blacklists.any { Regex("\\b$it\\b").containsMatchIn(lower) } ||
               lower.contains("help@") || lower.contains("www.") ||
               lower.contains("elector's") || lower.contains("father's") || lower.contains("mother's") || lower.contains("husband's")
    }

    /**
     * Validates that a candidate name consists of English Latin letters (a-z, A-Z)
     * so regional script lines (Hindi/Bengali/Tamil) are skipped in favor of the English name line.
     */
    private fun isValidLatinName(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3 || trimmed.length > 42) return false
        if (trimmed.any { it.isDigit() }) return false
        val invalidChars = listOf('@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '=', '[', ']', '{', '}', ';', ':', '"', '<', '>', '/', '\\', '|')
        if (trimmed.any { it in invalidChars }) return false

        val asciiLetters = trimmed.count { it in 'a'..'z' || it in 'A'..'Z' }
        val nonSpaceChars = trimmed.count { !it.isWhitespace() }
        if (nonSpaceChars == 0 || asciiLetters < 3) return false

        // Require at least 80% of non-whitespace characters to be Latin ASCII letters
        if (asciiLetters.toDouble() / nonSpaceChars.toDouble() < 0.80) return false

        // Require at least one vowel in the name to reject random OCR consonant noise like "XBCDF"
        val hasVowel = trimmed.any { it.lowercaseChar() in "aeiouy" }
        return hasVowel
    }

    fun deleteSelectedDocuments(docIds: Set<Int>) {
        if (docIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allDocs = database.documentDao().getAllDocuments().first()
                val toDelete = allDocs.filter { it.id in docIds }
                for (doc in toDelete) {
                    try {
                        val file = File(doc.localFilePath)
                        if (file.exists()) file.delete()
                        val basePath = doc.localFilePath.substringBeforeLast(".")
                        val siblingJpg = File("$basePath.jpeg")
                        if (siblingJpg.exists()) siblingJpg.delete()
                        val siblingWebp = File("$basePath.webp")
                        if (siblingWebp.exists()) siblingWebp.delete()
                        val siblingPdf = File("$basePath.pdf")
                        if (siblingPdf.exists()) siblingPdf.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                database.documentDao().deleteDocumentsByIds(docIds.toList())
                updatePublicFolderSize()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearAllData(deletePublicFiles: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Wipe Room database and clear in-memory queues
                database.documentDao().deleteAllDocuments()
                _activeQueue.value = emptyList()
                _pendingDocuments.value = emptyList()

                // Delete cached files
                context.cacheDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
                
                context.externalCacheDir?.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }

                // Delete internally saved document files while strictly preserving the DataStore preferences directory
                context.filesDir.listFiles()?.forEach { file ->
                    if (!file.name.equals("datastore", ignoreCase = true)) {
                        file.deleteRecursively()
                    }
                }

                if (deletePublicFiles) {
                    // Delete public documents
                    com.example.data.ImageProcessor.clearPublicDocuments(context)
                }
                updatePublicFolderSize()
                postStatusMessage("Local storage & cache cleaned successfully.", StatusBannerTone.SUCCESS)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUploadFailedNotification(context: Context, personName: String, docType: String) {
        val channelId = "upload_failures_channel"
        val channelName = "Upload Status"
        val notificationId = (System.currentTimeMillis() % 100000).toInt()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for failed document uploads to Google Drive"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Upload Failed")
            .setContentText("Failed uploading $personName's $docType. Tap to open app and retry manually.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notificationId, notificationBuilder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveAndUploadPassportPhoto(
        context: Context,
        sourceTempFile: File,
        customClientName: String,
        isPrintSheet: Boolean,
        targetFolderId: String?,
        targetFolderName: String,
        uploadToDrive: Boolean,
        onResult: (Boolean, String, Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val cleanClient = customClientName.trim()
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .replace(Regex("\\s+"), "_")
                .ifEmpty { "Client_${System.currentTimeMillis() % 10000}" }
            val docTypeLabel = if (isPrintSheet) "Passport_8Sheet" else "Passport_Photo"
            val suffix = java.util.UUID.randomUUID().toString().take(4)
            val finalFileName = if (nameBeforeType.value) {
                "${cleanClient}_${docTypeLabel}_$suffix.jpeg"
            } else {
                "${docTypeLabel}_${cleanClient}_$suffix.jpeg"
            }

            val queueId = java.util.UUID.randomUUID().toString()
            val humanDocType = if (isPrintSheet) "Passport 4×6 Sheet" else "Passport Photo"

            if (uploadToDrive) {
                _activeQueue.update {
                    it + QueueItem(
                        id = queueId,
                        personName = cleanClient.replace("_", " "),
                        documentType = humanDocType,
                        status = "Preparing passport JPEG...",
                        phase = QueueItemPhase.PROCESSING,
                        format = UploadFormat.JPEG
                    )
                }
            }

            try {
                val persistentFile = withContext(Dispatchers.IO) {
                    val dest = File(context.filesDir, finalFileName)
                    sourceTempFile.copyTo(dest, overwrite = true)
                    ImageProcessor.exportToPublicDocuments(context, dest, finalFileName, "image/jpeg")
                    try { sourceTempFile.delete() } catch (_: Exception) {}
                    dest
                }

                var isUploadedToDrive = false
                val email = googleEmail.value
                val effectiveFolderId = targetFolderId?.ifBlank { "root" } ?: "root"
                val effectiveFolderName = targetFolderName.trim().ifEmpty { "My Drive" }

                if (uploadToDrive && !email.isNullOrBlank()) {
                    postProcessing("Uploading $humanDocType to Drive ($effectiveFolderName)...")
                    updateQueueStatus(queueId, "Uploading to $effectiveFolderName...", QueueItemPhase.PROCESSING)

                    val (uploaded, _) = uploadFileWithAuthRetry(
                        context = context,
                        email = email,
                        initialToken = null,
                        onStatusUpdate = { statusText ->
                            updateQueueStatus(queueId, statusText, QueueItemPhase.PROCESSING)
                        }
                    ) { token ->
                        GoogleDriveClient.uploadFile(
                            accessToken = token,
                            file = persistentFile,
                            mimeType = "image/jpeg",
                            fileName = finalFileName,
                            parentId = effectiveFolderId
                        )
                    }
                    isUploadedToDrive = uploaded
                }

                val builtDrivePath = if (isUploadedToDrive) "My Drive/$effectiveFolderName" else null
                val entity = DocumentEntity(
                    fileName = finalFileName,
                    personName = cleanClient.replace("_", " "),
                    documentType = humanDocType,
                    localFilePath = persistentFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isUploaded = isUploadedToDrive,
                    drivePath = builtDrivePath
                )
                val insertedId = withContext(Dispatchers.IO) {
                    database.documentDao().insertDocument(entity)
                }
                if (insertedId != -1L) {
                    incrementUserScannedCount()
                    if (uploadToDrive) {
                        updateQueueDocId(queueId, insertedId.toInt())
                    }
                }

                updatePublicFolderSize()

                if (uploadToDrive) {
                    if (isUploadedToDrive) {
                        updateQueueStatus(queueId, "Uploaded to $effectiveFolderName", QueueItemPhase.COMPLETED)
                        postStatusMessage("Uploaded $finalFileName to Drive ($effectiveFolderName)", StatusBannerTone.SUCCESS)
                        fetchDriveFiles(context)
                        onResult(true, "Uploaded to Google Drive ($effectiveFolderName) & saved to device ($finalFileName)", true)
                    } else if (email.isNullOrBlank()) {
                        updateQueueStatus(queueId, "Saved locally (Sign in to Drive)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                        postStatusMessage("Saved locally. Sign in with Google to upload to Drive.", StatusBannerTone.WARNING)
                        onResult(false, "Saved to phone ($finalFileName), but Google Drive account is not signed in.", false)
                    } else {
                        updateQueueStatus(queueId, "Saved locally (Drive upload failed)", QueueItemPhase.SAVED_LOCALLY_NEEDS_SYNC)
                        postStatusMessage("Saved locally; Drive upload failed (can retry from Captures).", StatusBannerTone.WARNING)
                        onResult(false, "Saved to phone ($finalFileName). Drive upload failed — you can retry from Captures.", false)
                    }
                } else {
                    postStatusMessage("Saved $finalFileName to Documents/Dasmo Scan", StatusBannerTone.SUCCESS)
                    onResult(true, "Saved to Documents/Dasmo Scan/$finalFileName", false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (uploadToDrive) {
                    updateQueueStatus(queueId, "Failed: ${e.message}", QueueItemPhase.FAILED)
                }
                postStatusMessage("Passport save failed: ${e.message}", StatusBannerTone.ERROR)
                onResult(false, "Failed: ${e.localizedMessage ?: e.message ?: "Unknown error"}", false)
            }
        }
    }
}


