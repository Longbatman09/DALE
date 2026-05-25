package com.example.dale

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.dale.ui.theme.DALETheme
import com.example.dale.ui.theme.Purple80
import com.example.dale.utils.SharedPreferencesManager
import kotlinx.coroutines.delay
import java.util.Locale

class GroupSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val groupId = intent.getStringExtra("GROUP_ID") ?: ""
        val groupName = intent.getStringExtra("GROUP_NAME") ?: ""

        setContent {
            DALETheme {
                GroupSettingsScreen(
                    groupId = groupId,
                    groupName = groupName,
                    activity = this
                )
            }
        }
    }
}

@Composable
fun GroupSettingsScreen(
    groupId: String,
    groupName: String,
    activity: ComponentActivity
) {
    val sharedPrefs = SharedPreferencesManager.getInstance(activity)
    var group by remember(groupId, groupName) { mutableStateOf(
        when {
            groupId.isNotBlank() -> sharedPrefs.getAppGroup(groupId)
            groupName.isNotBlank() -> sharedPrefs.getAllAppGroups().firstOrNull { it.groupName == groupName }
            else -> null
        }
    ) }

    val currentGroup = group

    val resolvedGroupName = when {
        currentGroup != null && currentGroup.groupName.isNotBlank() -> currentGroup.groupName
        groupName.isNotBlank() -> groupName
        else -> "Unknown Group"
    }

    val showAppSelection = remember { mutableStateOf(false) }
    val showDeleteConfirmation = remember { mutableStateOf(false) }
    val showDestroyingLoader = remember { mutableStateOf(false) }
    val showRenameDialog = remember { mutableStateOf(false) }
    val showFingerprintDialog = remember { mutableStateOf(false) }
    val showUninstallProtectionDialog = remember { mutableStateOf(false) }
    val groupLockType = remember(currentGroup) {
        val app1Type = currentGroup?.app1LockType?.uppercase(Locale.ROOT)
        val app2Type = currentGroup?.app2LockType?.uppercase(Locale.ROOT)
        when {
            app1Type == "PATTERN" || app2Type == "PATTERN" -> "PATTERN"
            app1Type == "PASSWORD" || app2Type == "PASSWORD" -> "PASSWORD"
            else -> "PIN"
        }
    }
    val hasFingerprintSensor = remember {
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    }
    val isFingerprintAvailable = remember {
        if (!hasFingerprintSensor) {
            false
        } else {
            BiometricManager.from(activity).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1a1a2e),
                        Color(0xFF16213e)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF0f3460))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { activity.finish() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Text(
                    text = "Group Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text(
                text = resolvedGroupName,
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
            )

            HorizontalDivider(
                color = Color(0xFF0f3460),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Settings Options
            if (currentGroup != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Change Password Option
                    SettingsCard(
                        title = when (groupLockType) {
                            "PATTERN" -> "Change Pattern"
                            "PASSWORD" -> "Change Password"
                            else -> "Change PIN"
                        },
                        subtitle = when (groupLockType) {
                            "PATTERN" -> "Update pattern for this group"
                            "PASSWORD" -> "Update password for this group"
                            else -> "Update PIN for this group"
                        },
                        iconResourceId = R.drawable.change,
                        onClick = { showAppSelection.value = true }
                    )

                    SettingsCard(
                        title = "Biometric Unlock",
                        subtitle = when {
                            !hasFingerprintSensor -> "Fingerprint sensor not available on this device"
                            !isFingerprintAvailable -> "Add a fingerprint in device settings first"
                            else -> "Enable biometric unlock per app"
                        },
                        iconResourceId = R.drawable.ic_bio,
                        enabled = hasFingerprintSensor,
                        onClick = {
                            showFingerprintDialog.value = true
                        }
                    )

                    SettingsCard(
                        title = "Uninstall Protection",
                        subtitle = "Enable uninstall protection per app",
                        iconResourceId = R.drawable.anti_uninstall,
                        onClick = {
                            showUninstallProtectionDialog.value = true
                        }
                    )

                    SettingsCard(
                        title = "Change Group Name",
                        subtitle = "Rename this group",
                        iconResourceId = R.drawable.pen,
                        onClick = { showRenameDialog.value = true }
                    )

                    // App Logs Option
                    SettingsCard(
                        title = "App Logs",
                        subtitle = "Activity logs & usage statistics",
                        iconResourceId = R.drawable.logs,
                        onClick = {
                            val intent = Intent(activity, AppLogsActivity::class.java)
                            intent.putExtra("GROUP_ID", currentGroup.id)
                            intent.putExtra("GROUP_NAME", currentGroup.groupName)
                            activity.startActivity(intent)
                        }
                    )

                    // Delete Group Option
                    SettingsCard(
                        title = "Delete Group",
                        subtitle = "Remove this group permanently",
                        iconResourceId = R.drawable.bin,
                        onClick = { showDeleteConfirmation.value = true }
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Group data not found. Please go back and open the group again.",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // App Selection Dialog
        if (showAppSelection.value && currentGroup != null) {
            AppSelectionDialog(
                group = currentGroup,
                activity = activity,
                onDismiss = { showAppSelection.value = false },
                onAppSelected = { selectedApp, isBackupRegistration ->
                    showAppSelection.value = false
                    val intent = Intent(activity, ChangePasswordActivity::class.java)
                    intent.putExtra("GROUP_ID", currentGroup.id)
                    intent.putExtra("GROUP_NAME", currentGroup.groupName)
                    intent.putExtra("APP_PACKAGE", selectedApp)
                    intent.putExtra("IS_BACKUP_REGISTRATION", isBackupRegistration)
                    activity.startActivity(intent)
                }
            )
        }

        if (showRenameDialog.value && currentGroup != null) {
            RenameGroupDialog(
                currentGroup = currentGroup,
                sharedPrefs = sharedPrefs,
                onDismiss = { showRenameDialog.value = false },
                onRenamed = { updatedGroup ->
                    group = updatedGroup
                    showRenameDialog.value = false
                }
            )
        }

        if (showFingerprintDialog.value && currentGroup != null) {
            FingerprintSelectionDialog(
                currentGroup = currentGroup,
                sharedPrefs = sharedPrefs,
                activity = activity,
                isFingerprintAvailable = isFingerprintAvailable,
                onDismiss = { showFingerprintDialog.value = false },
                onSaved = { updatedGroup ->
                    group = updatedGroup
                    showFingerprintDialog.value = false
                }
            )
        }

        if (showUninstallProtectionDialog.value && currentGroup != null) {
            UninstallProtectionDialog(
                currentGroup = currentGroup,
                sharedPrefs = sharedPrefs,
                activity = activity,
                onDismiss = { showUninstallProtectionDialog.value = false },
                onSaved = { updatedGroup ->
                    group = updatedGroup
                    showUninstallProtectionDialog.value = false
                }
            )
        }

        // Delete Confirmation Dialog
        if (showDeleteConfirmation.value) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation.value = false },
                title = {
                    Text(
                        text = "Delete Group?",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text("Are you sure you want to delete the group \"$resolvedGroupName\"? This action cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmation.value = false
                            showDestroyingLoader.value = true
                        }
                    ) {
                        Text("Delete", color = Color(0xFFFF5252))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation.value = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Destroying Loader
        AnimatedVisibility(
            visible = showDestroyingLoader.value,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            DestroyingLoader(
                onComplete = {
                    val groupId = group?.id ?: groupId


                    sharedPrefs.deleteAppGroup(groupId)
                    activity.finish()
                }
            )
        }
    }
}

@Composable
fun FingerprintSelectionDialog(
    currentGroup: AppGroup,
    sharedPrefs: SharedPreferencesManager,
    activity: ComponentActivity,
    isFingerprintAvailable: Boolean,
    onDismiss: () -> Unit,
    onSaved: (AppGroup) -> Unit
) {
    var selectedApp by remember(currentGroup.id) {
        mutableStateOf(when {
            currentGroup.app1FingerprintEnabled -> "app1"
            currentGroup.app2FingerprintEnabled -> "app2"
            else -> ""
        })
    }

    fun getAuthTypeDisplay(lockType: String): String {
        return when (lockType.uppercase(Locale.ROOT)) {
            "PATTERN" -> "Pattern"
            "PASSWORD" -> "Password"
            else -> "PIN"
        }
    }

    // Load app icons
    val app1Icon = remember {
        try {
            activity.packageManager.getApplicationIcon(currentGroup.app1PackageName)
        } catch (_: Exception) {
            null
        }
    }

    val app2Icon = remember {
        try {
            activity.packageManager.getApplicationIcon(currentGroup.app2PackageName)
        } catch (_: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF03193B),
        title = {
            Text(
                text = "Biometric Unlock",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Select one app to enable biometric unlock. The backup authentication method will be the same as the group's lock type.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )

                if (!isFingerprintAvailable) {
                    Text(
                        text = "No biometrics enrolled on this device.",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp
                    )

                    TextButton(onClick = { openBiometricEnrollmentSettings(activity) }) {
                        Text("Register Biometric")
                    }
                }

                // Policy text
                Text(
                    text = "Policy: ${getAuthTypeDisplay(currentGroup.app1LockType)} + Biometric",
                    color = Color(0xFFB0B0B0),
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )

                Spacer(modifier = Modifier.height(8.dp))

                // OFF Option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isFingerprintAvailable) {
                            selectedApp = ""
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedApp == "") Color(0xFF0F4A8F) else Color(0xFF0F2A54)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "OFF",
                            fontSize = 14.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = if (selectedApp == "") Color(0xFF5DADE2) else Color(0xFF546E7A),
                                    shape = RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedApp == "") {
                                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // App 1 Option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isFingerprintAvailable) {
                            selectedApp = "app1"
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedApp == "app1") Color(0xFF0F4A8F) else Color(0xFF0F2A54)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (app1Icon != null) {
                                Image(
                                    bitmap = app1Icon.toBitmap().asImageBitmap(),
                                    contentDescription = currentGroup.app1Name,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = currentGroup.app1Name,
                                fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = if (selectedApp == "app1") Color(0xFF5DADE2) else Color(0xFF546E7A),
                                    shape = RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedApp == "app1") {
                                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // App 2 Option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isFingerprintAvailable) {
                            selectedApp = "app2"
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedApp == "app2") Color(0xFF0F4A8F) else Color(0xFF0F2A54)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (app2Icon != null) {
                                Image(
                                    bitmap = app2Icon.toBitmap().asImageBitmap(),
                                    contentDescription = currentGroup.app2Name,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = currentGroup.app2Name,
                                fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = if (selectedApp == "app2") Color(0xFF5DADE2) else Color(0xFF546E7A),
                                    shape = RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedApp == "app2") {
                                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Helper text
                if (selectedApp.isNotEmpty()) {
                    Text(
                        text = "When authenticating via biometric fails, user will use ${getAuthTypeDisplay(currentGroup.app1LockType)} as backup.",
                        color = Color(0xFF7DB8DE),
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = currentGroup.copy(
                        app1FingerprintEnabled = selectedApp == "app1",
                        app2FingerprintEnabled = selectedApp == "app2",
                        app1FingerprintBiometricOnly = false,
                        app2FingerprintBiometricOnly = false
                    )
                    sharedPrefs.saveAppGroup(updated)
                    onSaved(updated)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun UninstallProtectionDialog(
    currentGroup: AppGroup,
    sharedPrefs: SharedPreferencesManager,
    activity: ComponentActivity,
    onDismiss: () -> Unit,
    onSaved: (AppGroup) -> Unit
) {
    var app1Enabled by remember(currentGroup.id) {
        mutableStateOf(currentGroup.app1UninstallProtectionEnabled != false)
    }
    var app2Enabled by remember(currentGroup.id) {
        mutableStateOf(currentGroup.app2UninstallProtectionEnabled != false)
    }

    val app1Icon = remember {
        try {
            activity.packageManager.getApplicationIcon(currentGroup.app1PackageName)
        } catch (_: Exception) {
            null
        }
    }

    val app2Icon = remember {
        try {
            activity.packageManager.getApplicationIcon(currentGroup.app2PackageName)
        } catch (_: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF03193B),
        title = {
            Text(
                text = "Uninstall Protection",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enable or disable uninstall protection for each app.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A54))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app1Icon != null) {
                            Image(
                                bitmap = app1Icon.toBitmap().asImageBitmap(),
                                contentDescription = currentGroup.app1Name,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = currentGroup.app1Name.ifBlank { currentGroup.app1PackageName },
                            fontSize = 14.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )

                        Switch(
                            checked = app1Enabled,
                            onCheckedChange = { app1Enabled = it }
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A54))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app2Icon != null) {
                            Image(
                                bitmap = app2Icon.toBitmap().asImageBitmap(),
                                contentDescription = currentGroup.app2Name,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = currentGroup.app2Name.ifBlank { currentGroup.app2PackageName },
                            fontSize = 14.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )

                        Switch(
                            checked = app2Enabled,
                            onCheckedChange = { app2Enabled = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = currentGroup.copy(
                        app1UninstallProtectionEnabled = app1Enabled,
                        app2UninstallProtectionEnabled = app2Enabled
                    )
                    sharedPrefs.saveAppGroup(updated)
                    onSaved(updated)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Suppress("NewApi")
private fun openBiometricEnrollmentSettings(activity: ComponentActivity) {
    try {
        val enrollIntent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
            putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
        }
        activity.startActivity(enrollIntent)
    } catch (_: Exception) {
        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
}

private const val MAX_GROUP_NAME_LENGTH = 30

@Composable
fun RenameGroupDialog(
    currentGroup: AppGroup,
    sharedPrefs: SharedPreferencesManager,
    onDismiss: () -> Unit,
    onRenamed: (AppGroup) -> Unit
) {
    var nameInput by remember(currentGroup.id, currentGroup.groupName) {
        mutableStateOf(currentGroup.groupName)
    }

    val trimmedName = nameInput.trim()
    val normalizedName = trimmedName.lowercase(Locale.ROOT)
    val existingNames = remember(currentGroup.id) {
        sharedPrefs.getAllAppGroups()
            .asSequence()
            .filter { it.id != currentGroup.id }
            .mapNotNull { it.groupName.trim().takeIf { name -> name.isNotEmpty() }?.lowercase(Locale.ROOT) }
            .toSet()
    }

    val isBlank = trimmedName.isEmpty()
    val isDuplicate = normalizedName in existingNames
    val isLengthValid = trimmedName.length <= MAX_GROUP_NAME_LENGTH
    val isChanged = trimmedName != currentGroup.groupName.trim()
    val canSave = !isBlank && !isDuplicate && isLengthValid && isChanged

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF03193B),
        title = {
            Text(
                "Change Group Name",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column {
                TextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(MAX_GROUP_NAME_LENGTH) },
                    singleLine = true,
                    placeholder = { Text("Enter group name") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFB3C5DC),
                        unfocusedContainerColor = Color(0xFF062752),
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFB0B0B0),
                        cursorColor = Purple80
                    )
                )

                Text(
                    text = "${trimmedName.length}/$MAX_GROUP_NAME_LENGTH",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )

                val errorText = when {
                    isBlank -> "Group name cannot be blank"
                    isDuplicate -> "Group name already exists"
                    !isLengthValid -> "Maximum 30 characters"
                    else -> null
                }

                if (errorText != null) {
                    Text(
                        text = errorText,
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val updatedGroup = currentGroup.copy(groupName = trimmedName)
                    sharedPrefs.saveAppGroup(updatedGroup)
                    onRenamed(updatedGroup)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


@Composable
fun AppSelectionDialog(
    group: AppGroup,
    activity: ComponentActivity,
    onDismiss: () -> Unit,
    onAppSelected: (String, Boolean) -> Unit
) {
    val app1HasBiometric = group.app1FingerprintEnabled
    val app2HasBiometric = group.app2FingerprintEnabled

    // Load app icons and names
    val app1Icon = remember {
        try {
            activity.packageManager.getApplicationIcon(group.app1PackageName)
        } catch (_: Exception) {
            null
        }
    }

    val app2Icon = remember {
        try {
            activity.packageManager.getApplicationIcon(group.app2PackageName)
        } catch (_: Exception) {
            null
        }
    }

    val app1Name = remember {
        try {
            activity.packageManager.getApplicationLabel(
                activity.packageManager.getApplicationInfo(group.app1PackageName, 0)
            ).toString()
        } catch (_: Exception) {
            group.app1PackageName
        }
    }

    val app2Name = remember {
        try {
            activity.packageManager.getApplicationLabel(
                activity.packageManager.getApplicationInfo(group.app2PackageName, 0)
            ).toString()
        } catch (_: Exception) {
            group.app2PackageName
        }
    }

    fun prettyAuthType(lockType: String): String {
        return when (lockType.uppercase(Locale.ROOT)) {
            "PATTERN" -> "Pattern"
            "PASSWORD" -> "Password"
            else -> "PIN"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select App",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Choose which app lock you want to change:")

                // App 1 Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !app1HasBiometric) {
                            onAppSelected(group.app1PackageName, false)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (app1HasBiometric) Color(0xFF1b2a40) else Color(0xFF0f3460)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app1Icon != null) {
                            Image(
                                bitmap = app1Icon.toBitmap().asImageBitmap(),
                                contentDescription = app1Name,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .alpha(if (app1HasBiometric) 0.35f else 1f)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = app1Name,
                                fontSize = 16.sp,
                                color = if (app1HasBiometric) Color(0xFFB0BEC5) else Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            if (app1HasBiometric) {
                                Text(
                                    text = "Biometric + ${prettyAuthType(group.app1LockType)} Backup",
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }

                // App 2 Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !app2HasBiometric) {
                            onAppSelected(group.app2PackageName, false)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (app2HasBiometric) Color(0xFF1b2a40) else Color(0xFF0f3460)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app2Icon != null) {
                            Image(
                                bitmap = app2Icon.toBitmap().asImageBitmap(),
                                contentDescription = app2Name,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .alpha(if (app2HasBiometric) 0.35f else 1f)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = app2Name,
                                fontSize = 16.sp,
                                color = if (app2HasBiometric) Color(0xFFB0BEC5) else Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            if (app2HasBiometric) {
                                Text(
                                    text = "Biometric + ${prettyAuthType(group.app2LockType)} Backup",
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DestroyingLoader(onComplete: () -> Unit) {
    var dotCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        // Animate dots
        while (true) {
            delay(400)
            dotCount = (dotCount + 1) % 4
        }
    }

    LaunchedEffect(Unit) {
        delay(2000)
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Destroying" + ".".repeat(dotCount),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
