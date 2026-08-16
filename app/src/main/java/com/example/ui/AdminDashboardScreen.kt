package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    allUsers: List<AppUser>,
    onApprove: (String) -> Unit,
    onDecline: (String) -> Unit,
    onToggleApproval: (String, Boolean) -> Unit,
    onResetDevice: (String) -> Unit = {},
    onDeleteUser: (String) -> Unit = {},
    onUpdateExpiry: (String, Long) -> Unit = { _, _ -> },
    onCreateUser: (String, String, String, Long) -> Unit = { _, _, _, _ -> },
    statusMessage: String,
    onClearStatus: () -> Unit,
    onRunDiagnostics: () -> Unit = {},
    dbLogs: List<String> = emptyList(),
    onClearLogs: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, PENDING, APPROVED, ADMINS
    var showCreateDialog by remember { mutableStateOf(false) }
    var userToResetDevice by remember { mutableStateOf<AppUser?>(null) }
    var userToDelete by remember { mutableStateOf<AppUser?>(null) }
    var userToSetExpiry by remember { mutableStateOf<AppUser?>(null) }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val pendingUsers = allUsers.filter { it.status == "pending" && !it.isAdmin }
    val approvedUsers = allUsers.filter { it.isApproved && !it.isAdmin }
    val admins = allUsers.filter { it.isAdmin }

    val filteredUsers = allUsers.filter { user ->
        val matchesQuery = user.email.contains(searchQuery, ignoreCase = true) ||
                user.deviceModel.contains(searchQuery, ignoreCase = true) ||
                user.deviceId.contains(searchQuery, ignoreCase = true)

        val matchesFilter = when (selectedFilter) {
            "PENDING" -> user.status == "pending" && !user.isAdmin
            "APPROVED" -> user.isApproved && !user.isAdmin
            "ADMINS" -> user.isAdmin
            else -> true
        }
        matchesQuery && matchesFilter
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage.isNotEmpty()) {
            kotlinx.coroutines.delay(4000)
            onClearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Admin Security Control",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Manage Users, Hardware Lock & Approvals",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Pre-Approve User",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Status Notification
            AnimatedVisibility(
                visible = statusMessage.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearStatus, modifier = Modifier.size(20.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Stats Cards Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = "Total",
                    count = allUsers.size,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Pending",
                    count = pendingUsers.size,
                    color = MaterialTheme.colorScheme.errorContainer,
                    textColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Approved",
                    count = approvedUsers.size,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Admins",
                    count = admins.size,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by email or device model...") },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            )

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("All (${allUsers.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "PENDING",
                    onClick = { selectedFilter = "PENDING" },
                    label = { Text("Pending (${pendingUsers.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "APPROVED",
                    onClick = { selectedFilter = "APPROVED" },
                    label = { Text("Approved (${approvedUsers.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "ADMINS",
                    onClick = { selectedFilter = "ADMINS" },
                    label = { Text("Admins (${admins.size})") }
                )
            }

            // Users List
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                if (filteredUsers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PeopleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No matching users found" else "No users registered yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredUsers, key = { it.email }) { user ->
                            AdminUserCard(
                                user = user,
                                onApprove = { onApprove(user.email) },
                                onDecline = { onDecline(user.email) },
                                onToggleApproval = { onToggleApproval(user.email, user.isApproved) },
                                onResetDevice = { userToResetDevice = user },
                                onSetExpiry = { userToSetExpiry = user },
                                onDelete = { userToDelete = user }
                            )
                        }
                    }
                }
            }
        }
    }

    // Pre-Approve User Dialog
    if (showCreateDialog) {
        var emailInput by remember { mutableStateOf("") }
        var selectedRole by remember { mutableStateOf("user") }
        var selectedDays by remember { mutableStateOf("30") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text("Pre-Approve New User", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter the user's Google email address. Once created, they will be instantly approved on the first device they log into.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Google Account Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { selectedRole = if (selectedRole == "admin") "user" else "admin" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Role: ${selectedRole.uppercase()}")
                        }
                        OutlinedButton(
                            onClick = {
                                selectedDays = when (selectedDays) {
                                    "7" -> "30"
                                    "30" -> "90"
                                    "90" -> "365"
                                    "365" -> "0"
                                    else -> "7"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (selectedDays == "0") "Lifetime" else "$selectedDays Days")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (emailInput.isNotBlank() && emailInput.contains("@")) {
                            val days = selectedDays.toLongOrNull() ?: 0L
                            val expiry = if (days > 0) System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L) else 0L
                            onCreateUser(emailInput, selectedRole, "approved", expiry)
                            showCreateDialog = false
                        } else {
                            Toast.makeText(context, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Pre-Approve")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Reset Device Binding Dialog
    if (userToResetDevice != null) {
        val user = userToResetDevice!!
        AlertDialog(
            onDismissRequest = { userToResetDevice = null },
            icon = { Icon(imageVector = Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Reset Device Binding?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will disconnect '${user.email}' from their currently registered device (${user.deviceModel.ifEmpty { user.deviceId.take(12) }}). They will be able to bind and log in from a new phone upon approval."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetDevice(user.email)
                        userToResetDevice = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Unbind Device")
                }
            },
            dismissButton = {
                TextButton(onClick = { userToResetDevice = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Set Expiry Plan Dialog
    if (userToSetExpiry != null) {
        val user = userToSetExpiry!!
        AlertDialog(
            onDismissRequest = { userToSetExpiry = null },
            icon = { Icon(imageVector = Icons.Default.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Set Access Duration", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select how long '${user.email}' should have access before needing renewal:")
                    val options = listOf(
                        "7 Days" to (7L * 24 * 60 * 60 * 1000L),
                        "30 Days (1 Month)" to (30L * 24 * 60 * 60 * 1000L),
                        "90 Days (3 Months)" to (90L * 24 * 60 * 60 * 1000L),
                        "1 Year (365 Days)" to (365L * 24 * 60 * 60 * 1000L),
                        "Lifetime Access" to 0L
                    )
                    options.forEach { (label, duration) ->
                        OutlinedButton(
                            onClick = {
                                val timestamp = if (duration > 0L) System.currentTimeMillis() + duration else 0L
                                onUpdateExpiry(user.email, timestamp)
                                userToSetExpiry = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { userToSetExpiry = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete User Dialog
    if (userToDelete != null) {
        val user = userToDelete!!
        AlertDialog(
            onDismissRequest = { userToDelete = null },
            icon = { Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete User Account?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Are you sure you want to permanently delete '${user.email}' from the database? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteUser(user.email)
                        userToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { userToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AdminUserCard(
    user: AppUser,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onToggleApproval: () -> Unit,
    onResetDevice: () -> Unit,
    onSetExpiry: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val isExpired = user.expiryTimestamp > 0L && System.currentTimeMillis() > user.expiryTimestamp

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                user.isAdmin -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                isExpired -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                user.isApproved -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            when {
                user.isAdmin -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                isExpired -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                user.isApproved -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val initial = user.email.take(1).uppercase()
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                user.isAdmin -> MaterialTheme.colorScheme.primary
                                user.isApproved -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                ) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (user.isAdmin) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                Text("SUPER ADMIN", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (isExpired) {
                            Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) {
                                Text("EXPIRED", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (user.isApproved) {
                            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                                Text("APPROVED", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Badge(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                                Text("PENDING APPROVAL", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (user.deviceModel.isNotBlank()) {
                            Text(
                                text = user.deviceModel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Hardware Device Binding Section
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = if (user.deviceId.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (user.deviceId.isNotBlank()) "Bound: ${user.deviceModel.ifEmpty { user.deviceId.take(12) }}" else "Hardware Device: Unbound",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!user.isAdmin && user.deviceId.isNotBlank()) {
                        TextButton(
                            onClick = onResetDevice,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Reset Lock", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Expiry & Activity Details
            if (!user.isAdmin) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (user.expiryTimestamp > 0L) "Expires: ${sdf.format(Date(user.expiryTimestamp))}" else "Plan: Lifetime Access",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (user.lastActiveTimestamp > 0L) {
                        Text(
                            text = "Active: ${sdf.format(Date(user.lastActiveTimestamp))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!user.isAdmin) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))

                // Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!user.isApproved || user.status == "pending") {
                        // Quick 1-Click Approve / Decline
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedButton(
                                onClick = onDecline,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp).weight(1f)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reject", fontSize = 12.sp)
                            }
                            Button(
                                onClick = onApprove,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp).weight(1f)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Approve", fontSize = 12.sp)
                            }
                        }
                    } else {
                        // Switch toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Access:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = user.isApproved,
                                onCheckedChange = { onToggleApproval() },
                                thumbContent = {
                                    Icon(
                                        imageVector = if (user.isApproved) Icons.Default.Check else Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            )
                        }

                        // Duration / Delete buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = onSetExpiry, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Set Expiry",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete User",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    count: Int,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = textColor
            )
        }
    }
}
