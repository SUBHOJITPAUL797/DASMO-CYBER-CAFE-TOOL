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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

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

data class PendingDocument(
    val queueId: String,
    val compressedFile: File,
    val initialPersonName: String,
    val initialDocumentType: String,
    val pageFiles: List<File>? = null
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
    APPROVED
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
    val lastActiveTimestamp: Long = 0L
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
        scannedDocsListenerRegistration?.remove()
    }


    val documents = database.documentDao().getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val targetSizeKb = settingsRepository.targetSizeKb
        .stateIn(viewModelScope, SharingStarted.Lazily, 500)

    val imageFormat = settingsRepository.imageFormat
        .stateIn(viewModelScope, SharingStarted.Lazily, "WEBP")

    val autoEnhanceEnabled = settingsRepository.autoEnhanceEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val firestore = FirebaseFirestore.getInstance()
    private var authListenerRegistration: ListenerRegistration? = null
    private var usersListenerRegistration: ListenerRegistration? = null
    private var currentSessionToken: String? = null

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
        viewModelScope.launch {
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
                    if (scannedDocsListenerRegistration == null) {
                        startFirestoreSync()
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
                        _authState.value = AuthState.DEVICE_MISMATCH
                        return@addSnapshotListener
                    }

                    // 4. Approved & Device Matched: Full Access!
                    addDbLog("ACCESS GRANTED: User $normalizedEmail approved on bound device ($safeDeviceId).")
                    _authState.value = AuthState.APPROVED
                    if (scannedDocsListenerRegistration == null) {
                        startFirestoreSync()
                    }
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
                }

                if (isAdminEmail) {
                    _authState.value = AuthState.APPROVED
                    _isAdmin.value = true
                    if (usersListenerRegistration == null) {
                        listenToAllUsers()
                    }
                    if (scannedDocsListenerRegistration == null) {
                        startFirestoreSync()
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
                _statusMessage.value = "Error loading users: ${e.message}"
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
                        lastActiveTimestamp = lastActiveVal
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
            _statusMessage.value = "Unauthorized action!"
            return
        }
        val newApproved = !currentStatus
        val newStatus = if (newApproved) "approved" else "rejected"
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            "isApproved", newApproved,
            "dasmo_isApproved", newApproved,
            "status", newStatus,
            "dasmo_status", newStatus
        )
    }

    fun approveUser(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            _statusMessage.value = "Unauthorized action!"
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            "isApproved", true,
            "dasmo_isApproved", true,
            "status", "approved",
            "dasmo_status", "approved"
        ).addOnSuccessListener {
            _statusMessage.value = "User $email approved successfully!"
        }.addOnFailureListener { e ->
            _statusMessage.value = "Failed to approve $email: ${e.localizedMessage}"
        }
    }

    fun declineUser(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            _statusMessage.value = "Unauthorized action!"
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update(
            "isApproved", false,
            "dasmo_isApproved", false,
            "status", "rejected",
            "dasmo_status", "rejected"
        ).addOnSuccessListener {
            _statusMessage.value = "User $email access revoked."
        }
    }

    /**
     * Unbinds the user's hardware device so they can register a new phone upon approval.
     */
    fun revokeUserDevice(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            _statusMessage.value = "Unauthorized action!"
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
                "currentSessionToken" to ""
            )
        ).addOnSuccessListener {
            _statusMessage.value = "Device lock reset for $email. User can now bind a new device."
        }
    }

    fun updateUserExpiry(email: String, expiryTimestamp: Long) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            _statusMessage.value = "Unauthorized action!"
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).update("expiryTimestamp", expiryTimestamp)
            .addOnSuccessListener {
                _statusMessage.value = "Updated access plan duration for $email."
            }
    }

    fun deleteUser(email: String) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            _statusMessage.value = "Unauthorized action!"
            return
        }
        firestore.collection("dasmo_scanner_users").document(email.trim().lowercase()).delete()
            .addOnSuccessListener {
                _statusMessage.value = "Deleted user $email from database."
            }
    }

    fun createUserManually(email: String, role: String, status: String, expiryTimestamp: Long = 0L) {
        val currentLoggedInEmail = googleEmail.value?.trim()?.lowercase() ?: ""
        if (currentLoggedInEmail != "subhojitpaul26042004@gmail.com") {
            _statusMessage.value = "Unauthorized action!"
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
            "appTag" to "admin_preapproved"
        )
        firestore.collection("dasmo_scanner_users").document(trimmed).set(doc, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                _statusMessage.value = "Created and pre-approved account for $trimmed."
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

    private var scannedDocsListenerRegistration: ListenerRegistration? = null

    fun startFirestoreSync() {
        scannedDocsListenerRegistration?.remove()
        addDbLog("startFirestoreSync: Subscribing to snapshots for 'dasmo_doc_scanner_documents' collection.")
        scannedDocsListenerRegistration = firestore.collection("dasmo_doc_scanner_documents")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    addDbLog("scannedDocsListener ERROR: ${e.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    addDbLog("scannedDocsListener: Received snapshot. Total cloud documents = ${snapshot.size()}")
                    viewModelScope.launch(Dispatchers.IO) {
                        for (doc in snapshot.documents) {
                            val fileName = doc.getString("fileName") ?: continue
                            val personName = doc.getString("personName") ?: ""
                            val documentType = doc.getString("documentType") ?: ""
                            val timestamp = doc.getLong("timestamp") ?: 0L
                            val isUploaded = doc.getBoolean("isUploaded") ?: false
                            val drivePath = doc.getString("drivePath")
                            val firebaseUrl = doc.getString("firebaseUrl")

                            // Check if this exists locally
                            val localDocs = database.documentDao().getAllDocuments().first()
                            val existingLocal = localDocs.find { it.fileName == fileName }

                            if (existingLocal == null) {
                                // Save it locally
                                val localFile = File(context.filesDir, fileName)
                                val newEntity = DocumentEntity(
                                    fileName = fileName,
                                    personName = personName,
                                    documentType = documentType,
                                    localFilePath = localFile.absolutePath,
                                    timestamp = timestamp,
                                    isUploaded = isUploaded,
                                    drivePath = drivePath
                                )
                                database.documentDao().insertDocument(newEntity)

                                // Download the file if firebaseUrl is available
                                if (!firebaseUrl.isNullOrEmpty()) {
                                    downloadAndSaveFile(firebaseUrl, localFile)
                                }
                            } else {
                                // If the local file doesn't exist but we have a firebaseUrl, download it
                                val localFile = File(existingLocal.localFilePath)
                                if (!localFile.exists() && !firebaseUrl.isNullOrEmpty()) {
                                    downloadAndSaveFile(firebaseUrl, localFile)
                                }
                            }
                        }
                    }
                }
            }
    }

    private suspend fun downloadAndSaveFile(url: String, targetFile: File) {
        withContext(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun uploadDocToFirestoreAndStorage(localFile: File, entity: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                addDbLog("Firestore: Writing metadata directly to Firestore for ${entity.fileName} (Storage bypassed per setup)...")
                val userEmail = googleEmail.value ?: "anonymous"
                val firestoreDoc = mapOf(
                    "fileName" to entity.fileName,
                    "personName" to entity.personName,
                    "documentType" to entity.documentType,
                    "timestamp" to entity.timestamp,
                    "isUploaded" to entity.isUploaded,
                    "drivePath" to entity.drivePath,
                    "firebaseUrl" to "",
                    "creatorEmail" to userEmail
                )
                firestore.collection("dasmo_doc_scanner_documents")
                    .document(entity.fileName)
                    .set(firestoreDoc)
                    .addOnSuccessListener {
                        addDbLog("Firestore: Direct write SUCCESS for ${entity.fileName}")
                    }
                    .addOnFailureListener { err ->
                        addDbLog("Firestore ERROR: Direct write FAILED for ${entity.fileName}: ${err.message}")
                    }
            } catch (e: Exception) {
                addDbLog("ERROR in uploadDocToFirestoreAndStorage: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    val useA4Format = settingsRepository.useA4Format
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

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
        val status: String, // "Analyzing (NVIDIA AI)...", "Uploading PDF/JPEG...", "Completed", "Failed"
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _activeQueue = MutableStateFlow<List<QueueItem>>(emptyList())
    val activeQueue = _activeQueue.asStateFlow()

    fun clearActiveQueue() {
        _activeQueue.update { q -> q.filter { 
            !it.status.contains("Completed") && !it.status.contains("locally") && !it.status.contains("Failed") 
        } }
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

    fun clearStatusMessage() {
        _statusMessage.value = ""
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
                    
                    // 3. For each subfolder, retrieve its files (limit to at most 10 recent folders to prevent timeouts if placed in root)
                    val limitedSubfolders = subFolderDefs.take(10)
                    for (folder in limitedSubfolders) {
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
            _subfolderAtLastSizeChange.value = _targetSubfolder.value
        }
    }

    fun updateImageFormat(format: String) {
        viewModelScope.launch {
            settingsRepository.setImageFormat(format)
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

    fun cancelPendingDocument() {
        val pending = _pendingDocuments.value.firstOrNull() ?: return
        try { pending.compressedFile.delete() } catch (e: Exception) {}
        pending.pageFiles?.forEach { file ->
            try { file.delete() } catch (e: Exception) {}
        }
        _pendingDocuments.update { it.drop(1) }
        updateQueueStatus(pending.queueId, "Cancelled")
    }

    fun setGoogleEmail(email: String?) {
        viewModelScope.launch {
            settingsRepository.setGoogleEmail(email)
            if (email == null) {
                currentSessionToken = null
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
                    val newId = GoogleDriveClient.getOrCreateFolder(obtainedToken, name, _currentFolderId.value)
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

    private suspend fun isImageIdCard(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val options = android.graphics.BitmapFactory.Options()
            options.inJustDecodeBounds = true
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input, null, options)
            }
            val width = options.outWidth
            val height = options.outHeight
            
            // Landscape documents are almost always ID cards
            if (width > height) return@withContext true
            
            // For portrait documents (like Voter ID), check if it's smaller than a typical A4 page.
            // A full A4 document is typically > 5 Megapixels.
            // If the area is less than 4.5 Megapixels, we consider it an ID card.
            val area = width * height
            if (area < 4_500_000) return@withContext true
            
            return@withContext false
        } catch (e: Exception) {
            false
        }
    }

    fun processBatchScannedImages(imageUris: List<Uri>) {
        if (imageUris.isEmpty()) return
        
        viewModelScope.launch {
            _isProcessing.value = true
            _statusMessage.value = "Processing batch..."

            try {
                val groupedUris = mutableListOf<BatchGroup>()
                var i = 0
                while (i < imageUris.size) {
                    if (i + 1 < imageUris.size) {
                        groupedUris.add(BatchGroup(uris = listOf(imageUris[i], imageUris[i+1]), isIdCard = false))
                        i += 2
                    } else {
                        groupedUris.add(BatchGroup(uris = listOf(imageUris[i]), isIdCard = false))
                        i += 1
                    }
                }

                _isProcessing.value = false
                _statusMessage.value = ""
                
                if (showConfirmation.value) {
                    _batchVerificationGroups.value = groupedUris
                } else {
                    for (group in groupedUris) {
                        processScannedImages(group.uris, null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isProcessing.value = false
            }
        }
    }

    fun dismissBatchVerification() {
        _batchVerificationGroups.value = null
    }

    fun confirmBatchVerification(groups: List<BatchGroup>) {
        _batchVerificationGroups.value = null
        for (group in groups) {
            processScannedImages(group.uris, null)
        }
    }

    fun updateBatchGroups(newGroups: List<BatchGroup>) {
        _batchVerificationGroups.value = newGroups
    }

    fun processMultiScannedImages(imageUris: List<Uri>) {
        if (imageUris.isEmpty()) return
        
        viewModelScope.launch {
            _isProcessing.value = true
            val queueId = java.util.UUID.randomUUID().toString()
            val format = UploadFormat.PDF
            
            val initialItem = QueueItem(
                id = queueId,
                personName = "New Multi-Scan",
                documentType = "Document",
                format = format,
                status = "Processing multi-scan pages..."
            )
            _activeQueue.update { it + initialItem }

            try {
                _statusMessage.value = "Copying page images..."
                updateQueueStatus(queueId, "Copying page images...")
                val pageFiles = imageUris.mapIndexed { index, uri ->
                    val file = File(context.cacheDir, "multi_page_${java.util.UUID.randomUUID()}_$index.jpeg")
                    ImageProcessor.fixImageOrientation(context, uri, file)
                }

                _statusMessage.value = "Creating preview image..."
                updateQueueStatus(queueId, "Creating preview image...")
                val combinedFile = File(context.cacheDir, "combined_multi_${java.util.UUID.randomUUID()}.jpeg")
                val isId = imageUris.isNotEmpty() && useA4Format.value && isImageIdCard(context, imageUris.first())
                val resultFile = if (isId) {
                    ImageProcessor.combineImagesToA4(pageFiles.map { it.absolutePath }, combinedFile)
                } else {
                    ImageProcessor.combineImages(pageFiles.map { it.absolutePath }, combinedFile)
                }
                
                if (resultFile == null) {
                    _statusMessage.value = "Failed to combine images"
                    updateQueueStatus(queueId, "Failed: Combined empty")
                    _isProcessing.value = false
                    return@launch
                }

                val targetKb = targetSizeKb.value
                _statusMessage.value = "Compressing preview..."
                updateQueueStatus(queueId, "Compressing preview...")
                val compressedPreviewFile = ImageProcessor.compressImage(resultFile, targetKb, imageFormat.value)
                
                try { resultFile.delete() } catch (e: Exception) {}

                _statusMessage.value = "Analyzing first page with AI..."
                updateQueueStatus(queueId, "Analyzing first page...")
                val firstPageFile = pageFiles.first()
                var personName = "Unknown"
                var documentType = "Document"
                
                if (enableAiAnalysis.value) {
                    try {
                        val base64Image = encodeFileToBase64(firstPageFile)
                        val analysis = analyzeDocumentWithNvidia(base64Image)
                        personName = sanitizePersonName(analysis?.personName ?: "Unknown")
                        documentType = sanitizeDocumentType(analysis?.documentType ?: "Document", "")
                    } catch (e: Exception) {
                        e.printStackTrace()
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

                if (showConfirmation.value) {
                    _statusMessage.value = "Multi-scan complete. Please confirm."
                    updateQueueStatus(queueId, "Awaiting Confirmation")
                    val item = PendingDocument(
                        queueId = queueId,
                        compressedFile = compressedPreviewFile,
                        initialPersonName = personName,
                        initialDocumentType = documentType,
                        pageFiles = if (!isId) pageFiles else null
                    )
                    _pendingDocuments.update { it + item }
                } else {
                    _statusMessage.value = ""
                    updateQueueStatus(queueId, "Saving automatically...")
                    _isProcessing.value = false

                    viewModelScope.launch {
                        executeBackgroundUpload(
                            context,
                            queueId,
                            compressedPreviewFile,
                            personName,
                            documentType,
                            format,
                            pageFiles = pageFiles
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isProcessing.value = false
                _statusMessage.value = "Failed: ${e.message}"
                updateQueueStatus(queueId, "Failed: ${e.message}")
            }
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
            _isProcessing.value = true
            val queueId = java.util.UUID.randomUUID().toString()
            
            val initialItem = QueueItem(
                id = queueId,
                personName = personName,
                documentType = documentType,
                format = format,
                status = "Updating document..."
            )
            _activeQueue.update { it + initialItem }

            try {
                val existingDoc = database.documentDao().getAllDocuments().first().find { it.id == docId }
                if (existingDoc == null) {
                    _statusMessage.value = "Error: Document not found in database."
                    updateQueueStatus(queueId, "Failed: Not found")
                    _isProcessing.value = false
                    return@launch
                }

                val checkedPersonName = personName.trim().ifEmpty { "Client_${System.currentTimeMillis() % 100000}" }
                val checkedDocumentType = documentType.trim().ifEmpty { "Document" }

                var finalLocalFile = File(existingDoc.localFilePath)
                var tempPageFiles: List<File>? = null
                var isId = false

                if (newPageUris != null && newPageUris.isNotEmpty()) {
                    _statusMessage.value = "Processing new page scans..."
                    updateQueueStatus(queueId, "Processing new scans...")
                    
                    tempPageFiles = newPageUris.mapIndexed { index, uri ->
                        val file = File(context.cacheDir, "edit_page_${java.util.UUID.randomUUID()}_$index.jpeg")
                        ImageProcessor.fixImageOrientation(context, uri, file)
                    }

                    val combinedFile = File(context.cacheDir, "edit_combined_${java.util.UUID.randomUUID()}.jpeg")
                    isId = newPageUris.isNotEmpty() && useA4Format.value && isImageIdCard(context, newPageUris.first())
                    val resultFile = if (isId) {
                        ImageProcessor.combineImagesToA4(tempPageFiles.map { it.absolutePath }, combinedFile)
                    } else {
                        ImageProcessor.combineImages(tempPageFiles.map { it.absolutePath }, combinedFile)
                    }
                    if (resultFile == null) {
                        _statusMessage.value = "Failed to combine images."
                        updateQueueStatus(queueId, "Failed: Combined empty")
                        _isProcessing.value = false
                        return@launch
                    }

                    val targetKb = targetSizeKb.value
                    val compressedFile = ImageProcessor.compressImage(resultFile, targetKb, imageFormat.value)
                    try { resultFile.delete() } catch (e: Exception) {}

                    compressedFile.copyTo(finalLocalFile, overwrite = true)
                    try { compressedFile.delete() } catch (e: Exception) {}
                }

                val finalJpgName = existingDoc.fileName.replace(".pdf", ".jpeg").replace(".PDF", ".jpeg")
                val finalPdfName = existingDoc.fileName.replace(".jpeg", ".pdf").replace(".jpg", ".pdf")
                val dbFileName = if (format == UploadFormat.PDF || format == UploadFormat.BOTH) finalPdfName else finalJpgName

                val updatedEntity = existingDoc.copy(
                    fileName = dbFileName,
                    personName = checkedPersonName,
                    documentType = checkedDocumentType,
                    isUploaded = false,
                    timestamp = System.currentTimeMillis()
                )
                database.documentDao().updateDocument(updatedEntity)

                _statusMessage.value = ""
                updateQueueStatus(queueId, "Syncing changes...")

                _isProcessing.value = false
                viewModelScope.launch {
                    executeBackgroundUpload(
                        context = context,
                        queueId = queueId,
                        compressedFile = finalLocalFile,
                        checkedPersonName = checkedPersonName,
                        checkedDocumentType = checkedDocumentType,
                        format = format,
                        existingDocId = docId,
                        pageFiles = if (tempPageFiles != null && !isId) tempPageFiles else null
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _isProcessing.value = false
                _statusMessage.value = "Update failed: ${e.message}"
                updateQueueStatus(queueId, "Failed: ${e.message}")
            }
        }
    }

    fun processScannedImages(imageUris: List<Uri>, pdfUri: Uri?) {
        if (imageUris.isEmpty()) return
        
        viewModelScope.launch {
            _isProcessing.value = true
            val queueId = java.util.UUID.randomUUID().toString()
            val format = UploadFormat.PDF
            
            // Immediately add to queue monitor representing live status from start to finish
            val initialItem = QueueItem(
                id = queueId,
                personName = "New Scan",
                documentType = "Document",
                format = format,
                status = "Combining images..."
            )
            _activeQueue.update { it + initialItem }

            try {
                // 1. Resolve paths
                _statusMessage.value = "Combining images..."
                val paths = imageUris.mapIndexed { index, uri ->
                    val file = File(context.cacheDir, "scan_${java.util.UUID.randomUUID()}_$index.jpeg")
                    ImageProcessor.fixImageOrientation(context, uri, file)
                    file.absolutePath
                }

                // 2. Combine images (respecting A4 scan modes)
                val combinedFile = File(context.cacheDir, "combined_${java.util.UUID.randomUUID()}.jpeg")
                val isId = imageUris.isNotEmpty() && useA4Format.value && isImageIdCard(context, imageUris.first())
                val resultFile = if (isId) {
                    ImageProcessor.combineImagesToA4(paths, combinedFile)
                } else {
                    ImageProcessor.combineImages(paths, combinedFile)
                }
                if (resultFile == null) {
                    _statusMessage.value = "Failed to combine images"
                    updateQueueStatus(queueId, "Failed: Combined empty")
                    _isProcessing.value = false
                    return@launch
                }

                // 3. Compress
                val targetKb = targetSizeKb.value
                _statusMessage.value = "Compressing to ${targetKb}KB..."
                updateQueueStatus(queueId, "Compressing to ${targetKb}KB...")
                val compressedFile = ImageProcessor.compressImage(resultFile, targetKb, imageFormat.value)

                // High efficiency cache cleanup: delete the original separate page images and the uncompressed raw combined image
                paths.forEach { path ->
                    try { File(path).delete() } catch (e: Exception) {}
                }
                try { resultFile.delete() } catch (e: Exception) {}

                if (enableAiAnalysis.value) {
                    _statusMessage.value = "Analyzing with AI..."
                    updateQueueStatus(queueId, "Analyzing with Cloud AI...")
                    
                    val analysis = try {
                        val base64Image = encodeFileToBase64(compressedFile)
                        analyzeDocumentWithNvidia(base64Image)
                    } catch (e: Exception) {
                        null
                    }
                    
                    val checkedPersonName = sanitizePersonName(analysis?.personName ?: "Unknown")
                    val checkedDocumentType = sanitizeDocumentType(analysis?.documentType ?: "Document", "")
                    
                    _activeQueue.update { it.map { item ->
                        if (item.id == queueId) item.copy(personName = checkedPersonName, documentType = checkedDocumentType) else item
                    } }

                    if (showConfirmation.value) {
                        _statusMessage.value = "AI Analysis complete. Please confirm."
                        updateQueueStatus(queueId, "Awaiting Confirmation")
                        val item = PendingDocument(
                            queueId = queueId,
                            compressedFile = compressedFile,
                            initialPersonName = checkedPersonName,
                            initialDocumentType = checkedDocumentType
                        )
                        _pendingDocuments.update { it + item }
                    } else {
                        // Cloud AI Mode WITHOUT confirmation screen:
                        _statusMessage.value = ""
                        updateQueueStatus(queueId, "Saving automatically...")
                        _isProcessing.value = false

                        viewModelScope.launch {
                            executeBackgroundUpload(context, queueId, compressedFile, checkedPersonName, checkedDocumentType, format)
                        }
                        return@launch
                    }
                } else {
                    // Local OCR Mode:
                    _statusMessage.value = "Running on-device local text recognition..."
                    updateQueueStatus(queueId, "Running local text recognition...")
                    
                    var personName = "Unknown"
                    var documentType = "Document"
                    try {
                        val localAnalysis = analyzeDocumentLocally(compressedFile)
                        personName = sanitizePersonName(localAnalysis.personName)
                        documentType = sanitizeDocumentType(localAnalysis.documentType)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    _activeQueue.update { it.map { item ->
                        if (item.id == queueId) item.copy(personName = personName, documentType = documentType) else item
                    } }

                    if (showConfirmation.value) {
                        _statusMessage.value = "Processing complete. Please confirm document details."
                        updateQueueStatus(queueId, "Awaiting Confirmation")
                        val item = PendingDocument(
                            queueId = queueId,
                            compressedFile = compressedFile,
                            initialPersonName = personName,
                            initialDocumentType = documentType
                        )
                        _pendingDocuments.update { it + item }
                    } else {
                        // Local OCR WITHOUT confirmation screen (Instant direct upload):
                        _statusMessage.value = ""
                        updateQueueStatus(queueId, "Saving automatically...")
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
                updateQueueStatus(queueId, "Failed: ${e.localizedMessage ?: e.message}")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun confirmAndUpload(context: Context, personName: String, documentType: String, format: UploadFormat) {
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

        // Update queue item info & status
        _activeQueue.update { it.map { item ->
            if (item.id == queueId) item.copy(
                personName = checkedPersonName,
                documentType = checkedDocumentType,
                status = "Authorizing Google..."
            ) else item
        } }

        viewModelScope.launch {
            _isProcessing.value = true
            _statusMessage.value = "Requesting Google authorization..."
            
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
            
            // Save locally first to guarantee offline record!
            val localCopy = File(context.filesDir, finalJpgName)
            try {
                pending.compressedFile.copyTo(localCopy, overwrite = true)
                ImageProcessor.exportToPublicDocuments(context, localCopy, finalJpgName, "image/jpeg")
            } catch (ecop: Exception) {
                ecop.printStackTrace()
            }

            // Insert locally first
            val insertId = try {
                val newEntity = DocumentEntity(
                    fileName = dbFileName,
                    personName = checkedPersonName,
                    documentType = checkedDocumentType,
                    localFilePath = localCopy.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    isUploaded = false,
                    drivePath = null
                )
                val id = database.documentDao().insertDocument(newEntity)
                if (id != -1L) {
                    uploadDocToFirestoreAndStorage(localCopy, newEntity.copy(id = id.toInt()))
                }
                id
            } catch (edb: Exception) {
                edb.printStackTrace()
                -1L
            }

            updateQueueStatus(queueId, "Syncing directly...")

            if (email.isNullOrEmpty()) {
                _statusMessage.value = "Error: Please sign in to sync with Drive! Saved locally."
                updateQueueStatus(queueId, "Saved locally (Sign-In required)")
                _isProcessing.value = false
                try { pending.compressedFile.delete() } catch (el: Exception) {}
                fetchDriveFiles(context)
                return@launch
            }

            var obtainedToken: String? = null
            try {
                obtainedToken = getAccessToken(context, email)
                if (obtainedToken == null) {
                    _statusMessage.value = "Failed to retrieve Google token. Saved locally."
                    updateQueueStatus(queueId, "Saved locally (Token failed)")
                    _isProcessing.value = false
                    try { pending.compressedFile.delete() } catch (el: Exception) {}
                    fetchDriveFiles(context)
                    return@launch
                }

                var uploadParentId = folderId
                val subFolderName = targetSubfolder.value.trim()
                if (subFolderName.isNotEmpty()) {
                    _statusMessage.value = "Creating/finding target folder: $subFolderName..."
                    updateQueueStatus(queueId, "Finding target folder: $subFolderName...")
                    uploadParentId = getCachedOrFetchSubFolder(obtainedToken, subFolderName, folderId)
                }

                var isJpgUploaded = false
                var isPdfUploaded = false

                if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                    _statusMessage.value = "Uploading compressed image to Google Drive..."
                    updateQueueStatus(queueId, "Uploading image standard file...")
                    isJpgUploaded = retryIO(times = 3) {
                        GoogleDriveClient.uploadFile(
                            accessToken = obtainedToken,
                            file = pending.compressedFile,
                            mimeType = "image/jpeg",
                            fileName = finalJpgName,
                            parentId = uploadParentId
                        )
                    }
                }

                if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                    _statusMessage.value = "Generating and structuring PDF..."
                    updateQueueStatus(queueId, "Generating structured PDF...")
                    val pdfFile = File(context.cacheDir, "${java.util.UUID.randomUUID()}_pdf.pdf")
                    try {
                        if (pending.pageFiles != null && pending.pageFiles.isNotEmpty()) {
                            ImageProcessor.convertToMultiPagePdf(pending.pageFiles, pdfFile, targetSizeKb.value)
                        } else {
                            ImageProcessor.convertToPdf(pending.compressedFile, pdfFile, targetSizeKb.value)
                        }
                        ImageProcessor.exportToPublicDocuments(context, pdfFile, finalPdfName, "application/pdf")

                        _statusMessage.value = "Uploading structured PDF to Google Drive..."
                        updateQueueStatus(queueId, "Uploading PDF to Drive...")
                        isPdfUploaded = retryIO(times = 3) {
                            GoogleDriveClient.uploadFile(
                                accessToken = obtainedToken,
                                file = pdfFile,
                                mimeType = "application/pdf",
                                fileName = finalPdfName,
                                parentId = uploadParentId
                            )
                        }
                    } finally {
                        try { pdfFile.delete() } catch (ep: Exception) {}
                    }
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
                            localFilePath = localCopy.absolutePath,
                            timestamp = System.currentTimeMillis(),
                            isUploaded = true,
                            drivePath = builtDrivePath
                        )
                        database.documentDao().updateDocument(updatedEntity)
                        uploadDocToFirestoreAndStorage(localCopy, updatedEntity)
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
                _statusMessage.value = msg
                updateQueueStatus(queueId, if (overallSuccess) "Completed" else "Saved locally (Drive fail)")
                
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (_statusMessage.value == msg) {
                        _statusMessage.value = ""
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val errMsg = "Error uploading to Drive: ${e.message}. Saved locally."
                _statusMessage.value = errMsg
                updateQueueStatus(queueId, "Saved locally (Drive fail)")
                obtainedToken?.let { invalidateCachedToken(context, it) }
                
                viewModelScope.launch {
                    kotlinx.coroutines.delay(4000)
                    if (_statusMessage.value == errMsg) {
                        _statusMessage.value = ""
                    }
                }
            } finally {
                _isProcessing.value = false
                try { pending.compressedFile.delete() } catch (el: Exception) {}
                pending.pageFiles?.forEach { file ->
                    try { file.delete() } catch (e: Exception) {}
                }
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
        pageFiles: List<File>? = null
    ) {
        var obtainedToken: String? = null
        var finalJpgName = ""
        var finalPdfName = ""
        var localCopy: File? = null
        var oldJpgName: String? = null
        var oldPdfName: String? = null

        try {
            val isRetry = existingDocId != null
            if (isRetry) {
                val existingDoc = database.documentDao().getAllDocuments().first().find { it.id == existingDocId }
                if (existingDoc != null) {
                    if (existingDoc.fileName.endsWith(".pdf", ignoreCase = true)) {
                        oldPdfName = existingDoc.fileName
                        oldJpgName = existingDoc.fileName.replace(".pdf", ".jpeg").replace(".PDF", ".jpeg")
                    } else {
                        oldJpgName = existingDoc.fileName
                        oldPdfName = existingDoc.fileName.replace(".jpeg", ".pdf").replace(".jpg", ".pdf")
                    }
                    localCopy = File(existingDoc.localFilePath)
                }
            }

            val safePersonName = checkedPersonName.replace(" ", "_").trim()
            val safeDocumentType = checkedDocumentType.replace(" ", "_").trim()
            val suffixId = java.util.UUID.randomUUID().toString().take(4)
            val finalNameBase = if (nameBeforeType.value) {
                "${safePersonName}_${safeDocumentType}_$suffixId"
            } else {
                "${safeDocumentType}_${safePersonName}_$suffixId"
            }
            finalJpgName = "${finalNameBase}.jpeg"
            finalPdfName = "${finalNameBase}.pdf"
            
            val safeLocalCopy = localCopy ?: File(context.filesDir, finalJpgName)
            if (localCopy == null || localCopy.absolutePath != safeLocalCopy.absolutePath) {
                try {
                    compressedFile.copyTo(safeLocalCopy, overwrite = true)
                    if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                        ImageProcessor.exportToPublicDocuments(context, safeLocalCopy, finalJpgName, "image/jpeg")
                    }
                } catch (ecop: Exception) {
                    ecop.printStackTrace()
                }
            }
            val dbFileName = if (format == UploadFormat.PDF || format == UploadFormat.BOTH) finalPdfName else finalJpgName

            // Insert or Update local DB
            val insertId = try {
                if (existingDocId != null) {
                    existingDocId.toLong()
                } else {
                    val newId = database.documentDao().insertDocument(
                        DocumentEntity(
                            fileName = dbFileName,
                            personName = checkedPersonName,
                            documentType = checkedDocumentType,
                            localFilePath = safeLocalCopy.absolutePath,
                            timestamp = System.currentTimeMillis(),
                            isUploaded = false,
                            drivePath = null
                        )
                    )
                    val newEntity = DocumentEntity(
                        id = newId.toInt(),
                        fileName = dbFileName,
                        personName = checkedPersonName,
                        documentType = checkedDocumentType,
                        localFilePath = safeLocalCopy.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        isUploaded = false,
                        drivePath = null
                    )
                    uploadDocToFirestoreAndStorage(safeLocalCopy, newEntity)
                    newId
                }
            } catch (edb: Exception) {
                edb.printStackTrace()
                existingDocId?.toLong() ?: -1L
            }

            updateQueueStatus(queueId, "Authorizing Google Drive...")
            
            val email = settingsRepository.googleEmail.first()
            val folderId = settingsRepository.driveFolderId.first()
            val subFolderName = targetSubfolder.value.trim()
            
            if (email.isNullOrEmpty()) {
                updateQueueStatus(queueId, "Saved locally (Sign-In required)")
                fetchDriveFiles(context)
                return
            }

            var uploadSuccess = false
            var attempts = 0
            val maxAttempts = 3

            while (!uploadSuccess && attempts < maxAttempts) {
                attempts++
                if (attempts > 1) {
                    updateQueueStatus(queueId, "Drive upload failed. Auto-retrying (Attempt $attempts of $maxAttempts)...")
                    kotlinx.coroutines.delay(5000L)
                }

                backgroundUploadMutex.lock()
                try {
                    obtainedToken = getAccessToken(context, email)
                    if (obtainedToken == null) {
                        updateQueueStatus(queueId, "Saved locally (Token failed)")
                        fetchDriveFiles(context)
                        continue
                    }

                    var uploadParentId = folderId
                    var isJpgUploaded = false
                    var isPdfUploaded = false

                    var tokenAttempts = 0
                    val maxTokenAttempts = 2

                    while (tokenAttempts < maxTokenAttempts) {
                        try {
                            val token = obtainedToken ?: break
                            
                            if (subFolderName.isNotEmpty()) {
                                updateQueueStatus(queueId, "Locating subfolder: $subFolderName...")
                                uploadParentId = getCachedOrFetchSubFolder(token, subFolderName, folderId)
                            }

                            if (format == UploadFormat.JPEG || format == UploadFormat.BOTH) {
                                updateQueueStatus(queueId, "Uploading image standard file...")
                                isJpgUploaded = retryIO(times = 3) {
                                    GoogleDriveClient.uploadFile(
                                        accessToken = token,
                                        file = safeLocalCopy,
                                        mimeType = "image/jpeg",
                                        fileName = finalJpgName,
                                        parentId = uploadParentId,
                                        oldFileName = oldJpgName
                                    )
                                }
                            }

                            if (format == UploadFormat.PDF || format == UploadFormat.BOTH) {
                                updateQueueStatus(queueId, "Generating structured PDF...")
                                val pdfFile = File(context.cacheDir, "${java.util.UUID.randomUUID()}_pdf.pdf")
                                try {
                                    if (pageFiles != null && pageFiles.isNotEmpty()) {
                                        ImageProcessor.convertToMultiPagePdf(pageFiles, pdfFile, targetSizeKb.value)
                                    } else {
                                        ImageProcessor.convertToPdf(safeLocalCopy, pdfFile, targetSizeKb.value)
                                    }
                                    ImageProcessor.exportToPublicDocuments(context, pdfFile, finalPdfName, "application/pdf")

                                    updateQueueStatus(queueId, "Uploading PDF to Drive...")
                                    isPdfUploaded = retryIO(times = 3) {
                                        GoogleDriveClient.uploadFile(
                                            accessToken = token,
                                            file = pdfFile,
                                            mimeType = "application/pdf",
                                            fileName = finalPdfName,
                                            parentId = uploadParentId,
                                            oldFileName = oldPdfName
                                        )
                                    }
                                } finally {
                                    try { pdfFile.delete() } catch (ed: Exception) {}
                                }
                            }

                            break

                        } catch (e: Exception) {
                            val errorMsg = e.message ?: ""
                            android.util.Log.e("HomeViewModel", "Background upload try failed: $errorMsg", e)
                            
                            val isAuthError = errorMsg.contains("401") || 
                                              errorMsg.contains("unauthorized", ignoreCase = true) || 
                                              errorMsg.contains("token", ignoreCase = true) || 
                                              errorMsg.contains("auth", ignoreCase = true)
                            
                            if (isAuthError && tokenAttempts < maxTokenAttempts - 1) {
                                tokenAttempts++
                                updateQueueStatus(queueId, "Token expired. Refreshing...")
                                
                                obtainedToken?.let { staleToken ->
                                    withContext(Dispatchers.IO) {
                                        try {
                                            com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, staleToken)
                                        } catch (ex: Exception) {
                                            ex.printStackTrace()
                                        }
                                    }
                                }
                                
                                obtainedToken = getAccessToken(context, email)
                                if (obtainedToken == null) {
                                    updateQueueStatus(queueId, "Saved locally (Token refresh failed)")
                                    break
                                }
                            } else {
                                throw e
                            }
                        }
                    }

                    val overallSuccess = if (format == UploadFormat.BOTH) isJpgUploaded && isPdfUploaded 
                                         else if (format == UploadFormat.JPEG) isJpgUploaded
                                         else isPdfUploaded

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
                                    localFilePath = safeLocalCopy.absolutePath,
                                    timestamp = System.currentTimeMillis(),
                                    isUploaded = true,
                                    drivePath = builtDrivePath
                                )
                                database.documentDao().updateDocument(updatedEntity)
                                uploadDocToFirestoreAndStorage(safeLocalCopy, updatedEntity)
                            } catch (eup: Exception) {
                                eup.printStackTrace()
                            }
                        }
                        fetchDriveFiles(context)
                        updateQueueStatus(queueId, "Completed")
                        uploadSuccess = true
                    } else {
                        updateQueueStatus(queueId, "Saved locally (Drive fail)")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    val msg = e.message ?: "Unknown error"
                    updateQueueStatus(queueId, "Saved locally (Drive fail: ${msg.take(30)})")
                    obtainedToken?.let { invalidateCachedToken(context, it) }
                } finally {
                    backgroundUploadMutex.unlock()
                }
            }

            if (!uploadSuccess) {
                showUploadFailedNotification(context, checkedPersonName, checkedDocumentType)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            val msg = e.message ?: "Unknown error"
            updateQueueStatus(queueId, "Saved locally (Drive fail: ${msg.take(30)})")
            obtainedToken?.let { invalidateCachedToken(context, it) }
            showUploadFailedNotification(context, checkedPersonName, checkedDocumentType)
        } finally {
            if (existingDocId == null) {
                try { compressedFile.delete() } catch (ex: Exception) {}
                pageFiles?.forEach { file ->
                    try { file.delete() } catch (ex: Exception) {}
                }
            }
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
        viewModelScope.launch {
            val docs = database.documentDao().getAllDocuments().first()
            val toUpload = docs.filter { it.id in docIds && !it.isUploaded }
            
            for (doc in toUpload) {
                val file = File(doc.localFilePath)
                if (!file.exists()) continue
                
                val queueId = java.util.UUID.randomUUID().toString()
                _activeQueue.update { it + QueueItem(
                    id = queueId,
                    personName = doc.personName,
                    documentType = doc.documentType,
                    status = "Retrying upload...",
                    format = if (doc.fileName.endsWith(".pdf", ignoreCase = true)) UploadFormat.PDF else UploadFormat.JPEG
                ) }
                
                executeBackgroundUpload(
                    context = context,
                    queueId = queueId,
                    compressedFile = file,
                    checkedPersonName = doc.personName,
                    checkedDocumentType = doc.documentType, 
                    format = if (doc.fileName.endsWith(".pdf", ignoreCase = true)) UploadFormat.PDF else UploadFormat.JPEG,
                    existingDocId = doc.id
                )
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
                
                _statusMessage.value = "Merging documents to PDF..."
                _isProcessing.value = true
                
                _activeQueue.update { it + QueueItem(
                    id = queueId,
                    personName = "Merged",
                    documentType = "PDF",
                    status = "Starting...",
                    format = UploadFormat.PDF
                )}
                
                val imagePaths = selectedDocs.filter { 
                    it.localFilePath.endsWith(".jpeg", ignoreCase = true) || it.localFilePath.endsWith(".jpg", ignoreCase = true)
                }.map { it.localFilePath }
                
                if (imagePaths.isEmpty()) {
                    _statusMessage.value = "No images selected to merge."
                    _isProcessing.value = false
                    updateQueueStatus(queueId, "Failed: No images")
                    return@launch
                }
                
                val finalFileName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
                val pdfFile = File(context.cacheDir, "merged_${java.util.UUID.randomUUID()}.pdf")
                val filesToMerge = imagePaths.map { File(it) }
                
                updateQueueStatus(queueId, "Generating PDF...")
                ImageProcessor.convertToMultiPagePdf(filesToMerge, pdfFile, targetSizeKb)
                
                val safeLocalCopy = File(context.filesDir, "merged_${java.util.UUID.randomUUID()}.pdf")
                pdfFile.inputStream().use { input ->
                    safeLocalCopy.outputStream().use { output -> input.copyTo(output) }
                }
                
                ImageProcessor.exportToPublicDocuments(context, safeLocalCopy, finalFileName, "application/pdf")
                
                var isUploaded = false
                val email = googleEmail.value
                val token = if (email != null) getAccessToken(context, email) else null
                if (token != null) {
                    updateQueueStatus(queueId, "Uploading PDF to Drive...")
                    val uploadParentId = folderId ?: driveFolderId.value
                    
                    isUploaded = retryIO(times = 3) {
                        GoogleDriveClient.uploadFile(
                            accessToken = token,
                            file = pdfFile,
                            mimeType = "application/pdf",
                            fileName = finalFileName,
                            parentId = uploadParentId
                        )
                    }
                }
                
                val mergedEntity = DocumentEntity(
                    fileName = finalFileName,
                    personName = "Merged",
                    documentType = "PDF",
                    localFilePath = safeLocalCopy.absolutePath,
                    isUploaded = isUploaded,
                    timestamp = System.currentTimeMillis()
                )
                val id = database.documentDao().insertDocument(mergedEntity)
                if (id != -1L) {
                    uploadDocToFirestoreAndStorage(safeLocalCopy, mergedEntity.copy(id = id.toInt()))
                }
                
                updateQueueStatus(queueId, "Completed - Merged successfully!")
                _statusMessage.value = "Merged successfully!"
                updatePublicFolderSize()
                
            } catch (e: Exception) {
                e.printStackTrace()
                _statusMessage.value = "Merge failed: ${e.message}"
                updateQueueStatus(queueId, "Failed: ${e.message}")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun uploadInBackground(context: Context, personName: String, documentType: String, format: UploadFormat) {
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
                status = "Queued..."
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
                pageFiles = pending.pageFiles
            )
        }
    }

    private fun updateQueueStatus(id: String, status: String) {
        _activeQueue.update { currentList ->
            currentList.map { item ->
                if (item.id == id) item.copy(status = status) else item
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

    private suspend fun analyzeDocumentLocally(imageFile: File): DocumentAnalysisResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            var bitmap: Bitmap? = null
            try {
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(imageFile.absolutePath, options)
                
                var inSampleSize = 1
                val reqWidth = 1024
                val reqHeight = 1024
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
                val image = InputImage.fromBitmap(bitmap, 0)
                
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (continuation.isActive) {
                            val fullText = visionText.text
                            val result = try { extractLocalDetails(fullText) } catch (e: Exception) { DocumentAnalysisResult("Unknown", "Document") }
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
        
        // 1. Smart Scoring Based Document Detection
        var aadhaarScore = 0
        var panScore = 0
        var voterScore = 0
        var dlScore = 0
        var passportScore = 0
        var marksheetScore = 0
        var rationScore = 0

        if (Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b").containsMatchIn(fullText)) aadhaarScore += 10
        if (lowercaseText.contains("aadhaar") || lowercaseText.contains("aadhar") || lowercaseText.contains("adhar")) aadhaarScore += 10
        if (lowercaseText.contains("unique identification") || lowercaseText.contains("uidai")) aadhaarScore += 5
        if (fullText.contains("आधार")) aadhaarScore += 4
        if (lowercaseText.contains("mera aadhaar") || lowercaseText.contains("meri pehchaan")) aadhaarScore += 4

        if (Regex("\\b[A-Za-z]{5}[0-9]{4}[A-Za-z]\\b").containsMatchIn(fullText)) panScore += 10
        if (lowercaseText.contains("permanent account number")) panScore += 10
        if (lowercaseText.contains("income tax department")) panScore += 10

        if (lowercaseText.contains("election commission of india")) voterScore += 10
        if (lowercaseText.contains("elector photo identity card") || lowercaseText.contains("epic")) voterScore += 10
        if (lowercaseText.contains("voter id") || lowercaseText.contains("voter card")) voterScore += 10
        if (Regex("\\b[A-Za-z]{3}[0-9]{7}\\b").containsMatchIn(fullText)) voterScore += 4 // EPIC
        if (lowercaseText.contains("निर्वाचन आयोग")) voterScore += 5

        if (lowercaseText.contains("driving licence") || lowercaseText.contains("driving license")) dlScore += 10
        if (lowercaseText.contains("dl no") || lowercaseText.contains("authorization to drive")) dlScore += 10
        if (lowercaseText.contains("transport department")) dlScore += 5

        if (lowercaseText.contains("republic of india") && lowercaseText.contains("passport")) passportScore += 10
        if (lowercaseText.contains("passport no")) passportScore += 10

        if (lowercaseText.contains("marksheet") || lowercaseText.contains("mark sheet") || lowercaseText.contains("marks statement")) marksheetScore += 10
        if (lowercaseText.contains("board of") && lowercaseText.contains("examination")) marksheetScore += 10

        if (lowercaseText.contains("ration card") || lowercaseText.contains("smart ration card")) rationScore += 10
        if (lowercaseText.contains("department of food") || lowercaseText.contains("food and civil supplies")) rationScore += 5

        val scores = mapOf(
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

        // 2. Exact on-device name extraction logic
        var foundName = ""
        
        if (documentType == "PAN Card") {
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                if (lineLower.contains("name") && !lineLower.contains("father")) {
                    if (i + 1 < lines.size) {
                        val potentialName = lines[i + 1]
                        if (isValidName(potentialName) && !isBlacklistedLine(potentialName)) {
                            foundName = potentialName
                            break
                        }
                    }
                }
                if (foundName.isEmpty() && lineLower.contains("permanent account number")) {
                    for(j in Math.max(0, i-4) until i) {
                        val potentialName = lines[j]
                        if (isValidName(potentialName) && potentialName.uppercase() == potentialName && !isBlacklistedLine(potentialName)) {
                            foundName = potentialName
                        }
                    }
                }
            }
        } else if (documentType == "Voter ID") {
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                if (lineLower.contains("elector's name") || lineLower.contains("name")) {
                    val idx = lineLower.indexOf("name")
                    val after = lines[i].substring(idx + 4).trim(':', '-', ' ', '=', '|', '।')
                    if (after.length >= 3 && isValidName(after) && !isBlacklistedLine(after)) {
                        foundName = after
                        break
                    } else if (i + 1 < lines.size) {
                        val nextLine = lines[i+1].trim(':', '-', ' ', '=', '|', '।')
                        if (isValidName(nextLine) && !isBlacklistedLine(nextLine)) {
                            foundName = nextLine
                            break
                        }
                    }
                }
            }
        } else if (documentType == "Aadhaar Card") {
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                if (lineLower.contains("dob") || lineLower.contains("year of birth") || lineLower.contains("yob")) {
                    if (i - 1 >= 0) {
                        val prev = lines[i-1]
                        if (isValidName(prev) && !isBlacklistedLine(prev)) {
                            foundName = prev
                            break
                        }
                    }
                    if (i - 2 >= 0 && foundName.isEmpty()) {
                         val prev = lines[i-2]
                         if (isValidName(prev) && !isBlacklistedLine(prev)) {
                             foundName = prev
                             break
                         }
                    }
                }
            }
        }

        if (foundName.isEmpty()) {
            val nameIndicators = listOf("elector's name", "name", "full name", "नाम", "holder name", "name of holder", "card holder", "given name", "given name(s)", "surname")
            for (i in lines.indices) {
                val lineLower = lines[i].lowercase()
                for (ind in nameIndicators) {
                    if (lineLower.startsWith(ind) || lineLower.contains("$ind:") || lineLower.contains("$ind :") || lineLower.contains("$ind=")) {
                        var afterInd = ""
                        val idx = lineLower.indexOf(ind)
                        if (idx != -1) {
                            afterInd = lines[i].substring(idx + ind.length).trim(':', '-', ' ', '=', '।', '/', '.')
                        }
                        if (afterInd.length >= 3 && isValidName(afterInd) && !isBlacklistedLine(afterInd)) {
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
        }
        
        if (foundName.isEmpty()) {
            for (line in lines) {
                val cleaned = line.replace(Regex("[^a-zA-Z\\s]"), "").trim()
                val words = cleaned.split("\\s+".toRegex())
                if ((words.size in 2..4) && (cleaned.uppercase() == cleaned) && cleaned.length >= 5) {
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
        var cleaned = detectedName.trim()
        if (cleaned.isEmpty() || cleaned.lowercase() == "unknown") {
            return "Unknown"
        }

        val prefixes = listOf(
            "card holder name", "cardholder name", "name of holder", "holder name", 
            "card holder", "cardholder", "head of family", "relation name", "full name", 
            "father's name", "father name", "mother's name", "mother name", "husband's name",
            "husband name", "नाम", "name", "elector's name", "elector name"
        )
        
        for (pref in prefixes) {
            val lowerCleaned = cleaned.lowercase()
            if (lowerCleaned.startsWith(pref)) {
                cleaned = cleaned.substring(pref.length).trim(':', '-', ' ', '=', '।', '/', ',')
                break
            }
        }

        val nameLower = cleaned.lowercase()
        if (nameLower.isEmpty() || nameLower == "unknown") {
            return "Unknown"
        }

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

        if (cleaned.any { it.isDigit() }) return "Unknown"
        val invalidChars = listOf('@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '=', '[', ']', '{', '}', ';', ':', '"', '<', '>', '/', '\\', '|')
        if (cleaned.any { it in invalidChars }) return "Unknown"
        
        val words = cleaned.split("\\s+".toRegex())
        if (words.size > 5) return "Unknown"
        if (cleaned.length <= 2) return "Unknown"

        return cleaned
    }

    private fun sanitizeDocumentType(detectedType: String, keywordText: String = ""): String {
        val typeLower = detectedType.trim().lowercase()
        val textLower = keywordText.lowercase()

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

        return detectedType.trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun isBlacklistedLine(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.length < 3) return true
        val blacklists = listOf(
            "government", "india", "income tax", "permanent", "department", "election", "commission", "signature", "card",
            "father", "mother", "husband", "spouse", "address", "photo", "licence", "license", "republic", "citizen", "national",
            "state", "district", "union", "authority", "unique", "identification", "school", "board", "voter", "birth", "date", "no",
            "delhi", "mumbai", "kolkata", "chennai", "road", "street", "lane", "floor", "house", "flat", "office", "post", "bazar", "nagar",
            "city", "town", "village", "taluk", "tehsil", "dist", "pin", "code", "phone", "mobile", "tel", "email", "web", "site",
            "issue", "expiry", "holder", "assembly", "elector", "marksheet", "certificate", "examined", "roll", "marks", "grades",
            "uidai", "mera aadhaar", "meri pehchaan", "sex", "male", "female", "gender", "age", "dob", "yob"
        )
        return blacklists.any { Regex("\\b$it\\b").containsMatchIn(lower) } || 
               lower.contains("help@") || lower.contains("www.") || 
               lower.contains("elector's") || lower.contains("father's")
    }

    private fun isValidName(line: String): Boolean {
        if (line.any { it.isDigit() }) return false
        val invalidChars = listOf('@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '=', '[', ']', '{', '}', ';', ':', '"', '<', '>', '/', '\\', '|')
        if (line.any { it in invalidChars }) return false
        val letters = line.filter { it.isLetter() }.length
        val total = line.length
        return total > 0 && (letters.toDouble() / total.toDouble()) > 0.6 && line.trim().length > 2
    }

    fun clearAllData(deletePublicFiles: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Wipe Room database
                database.documentDao().deleteAllDocuments()

                // Delete cached files
                context.cacheDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
                
                context.externalCacheDir?.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }

                // Delete internally saved files
                context.filesDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }

                if (deletePublicFiles) {
                    // Delete public documents
                    com.example.data.ImageProcessor.clearPublicDocuments(context)
                }
                updatePublicFolderSize()
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
}


