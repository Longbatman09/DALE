package com.example.dale

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import com.example.dale.utils.HashUtils.hashPin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dale.ui.theme.DALETheme
import com.example.dale.ui.theme.Purple40
import com.example.dale.utils.performKeypadHaptic
import com.example.dale.utils.MonitorStartupHelper
import com.example.dale.utils.SharedPreferencesManager
import java.security.MessageDigest
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

class PasswordSetupActivity : ComponentActivity() {
    private var groupId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        groupId = intent.getStringExtra("groupId") ?: ""

        setContent {
            DALETheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PasswordSetupScreen(
                        modifier = Modifier.padding(innerPadding),
                        groupId = groupId,
                        activity = this
                    )
                }
            }
        }
    }

    // Save hashed PIN for specific app index (1 or 2)
    fun savePinForApp(groupId: String, appIndex: Int, pin: String) {
        val sharedPrefsManager = SharedPreferencesManager.getInstance(this)
        val appGroup = sharedPrefsManager.getAppGroupForSetup(groupId)
        if (appGroup != null) {
            val hashedPin = hashPin(pin)
            val pinLength = pin.length
            val newGroup = when (appIndex) {
                1 -> appGroup.copy(app1LockPin = hashedPin, app1PinLength = pinLength, isLocked = true)
                2 -> appGroup.copy(app2LockPin = hashedPin, app2PinLength = pinLength, isLocked = true)
                else -> appGroup
            }
            sharedPrefsManager.saveAppGroupForSetup(newGroup)
        }
    }

    // Save hashed credentials for both apps at the final step (only finalize at the end)
    fun saveBothCredentials(
        groupId: String,
        app1AuthType: String,
        app1RawCredential: String,
        app2AuthType: String,
        app2RawCredential: String
    ) {
        val sharedPrefsManager = SharedPreferencesManager.getInstance(this)
        val appGroup = sharedPrefsManager.getAppGroupForSetup(groupId)
        if (appGroup != null) {
            val app1HashedCredential = hashPin(app1RawCredential)
            val app2HashedCredential = hashPin(app2RawCredential)
            val app1NormalizedType = app1AuthType.uppercase()
            val app2NormalizedType = app2AuthType.uppercase()
            val groupPinLength = if (app1NormalizedType == "PIN") app1RawCredential.length else 0
            
            val newGroup = appGroup.copy(
                app1LockPin = app1HashedCredential,
                app1LockType = app1NormalizedType,
                app1PinLength = groupPinLength,
                app2LockPin = app2HashedCredential,
                app2LockType = app2NormalizedType,
                app2PinLength = if (app2NormalizedType == "PIN") groupPinLength else 0,
                isLocked = true
            )
            sharedPrefsManager.saveAppGroupForSetup(newGroup)
        }
    }

    // Save hashed credential for specific app index (1 or 2) - DEPRECATED - use saveBothCredentials instead
    fun saveCredentialForApp(groupId: String, appIndex: Int, authType: String, rawCredential: String) {
        // This method is now deprecated. Credentials are saved at the final step only.
    }

    fun saveBiometricForApps(
        groupId: String,
        app1Enabled: Boolean,
        app2Enabled: Boolean,
        app1BiometricOnly: Boolean = false,
        app2BiometricOnly: Boolean = false,
        app1BackupType: String = "PIN",
        app2BackupType: String = "PIN",
        app1BackupPin: String = "",
        app2BackupPin: String = ""
    ) {
        val sharedPrefsManager = SharedPreferencesManager.getInstance(this)
        val appGroup = sharedPrefsManager.getAppGroupForSetup(groupId)
        if (appGroup != null) {
            val resolvedBackupType = app1BackupType.ifBlank {
                app2BackupType.ifBlank { "PIN" }
            }.uppercase()

            // With new policy: Always use backup credentials (never biometric-only)
            val app1BackupFinal = if (app1BackupPin.isNotEmpty()) hashPin(app1BackupPin) else appGroup.app1LockPin
            val app2BackupFinal = if (app2BackupPin.isNotEmpty()) hashPin(app2BackupPin) else appGroup.app2LockPin
            val groupPinLength = if (resolvedBackupType == "PIN") {
                app1BackupPin.length.takeIf { it > 0 }
                    ?: appGroup.app1PinLength.takeIf { it > 0 }
                    ?: app2BackupPin.length.takeIf { it > 0 }
                    ?: appGroup.app2PinLength
            } else {
                0
            }

            // Always set lock type to the backup type (never just "BIOMETRIC")
            val app1TypeFinal = resolvedBackupType
            val app2TypeFinal = resolvedBackupType

            val newGroup = appGroup.copy(
                app1FingerprintEnabled = app1Enabled,
                app2FingerprintEnabled = app2Enabled,
                app1FingerprintBiometricOnly = false,  // Always false with new policy
                app2FingerprintBiometricOnly = false,  // Always false with new policy
                app1LockType = app1TypeFinal,
                app2LockType = app2TypeFinal,
                app1LockPin = app1BackupFinal,
                app2LockPin = app2BackupFinal,
                app1PinLength = if (app1TypeFinal == "PIN") groupPinLength else 0,
                app2PinLength = if (app2TypeFinal == "PIN") groupPinLength else 0,
                isLocked = true
            )
            sharedPrefsManager.saveAppGroupForSetup(newGroup)
        }
    }

    fun completePasswordSetup(groupId: String) {
        val sharedPrefsManager = SharedPreferencesManager.getInstance(this)
        val appGroup = sharedPrefsManager.getAppGroupForSetup(groupId)

        if (appGroup != null) {
            // mark locked if any pin exists
            val locked = appGroup.app1LockPin.isNotEmpty() || appGroup.app2LockPin.isNotEmpty()
            sharedPrefsManager.saveAppGroupForSetup(appGroup.copy(isLocked = locked))
            sharedPrefsManager.commitPendingAppGroup(groupId)
        }

        // Mark setup as completed
        sharedPrefsManager.setSetupCompleted(true)

        // Accessibility service is now the only detection method
        // It will automatically start if enabled in accessibility settings

        // Navigate to main app
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    fun navigateBackToGroupName() {
        val intent = Intent(this, AppSelectionActivity::class.java).apply {
            putExtra(AppSelectionActivity.Companion.EXTRA_RETURN_TO_GROUP_NAME, true)
            putExtra(AppSelectionActivity.Companion.EXTRA_EDIT_GROUP_ID, groupId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }
}

@Composable
fun PasswordSetupScreen(
    modifier: Modifier = Modifier,
    groupId: String = "",
    activity: ComponentActivity? = null
) {
    val selectedAuthType = remember { mutableStateOf<String?>(null) }
    val targetAppIndex = remember { mutableStateOf(1) } // 1 or 2
    val showBiometricAppsDialog = remember { mutableStateOf(false) }
    val showBiometricBackupDialog = remember { mutableStateOf(false) }
    val showBiometricBackupPinDialog = remember { mutableStateOf<Int?>(null) }
    val pendingCredentialApps = remember { mutableStateListOf<Int>() }
    val activeCredentialApp = remember { mutableStateOf<Int?>(null) }
    
    // Store credentials temporarily until final confirmation
    val app1Credential = remember { mutableStateOf<String?>(null) }
    val app2Credential = remember { mutableStateOf<String?>(null) }
    val app1AuthType = remember { mutableStateOf<String?>(null) }
    val app2AuthType = remember { mutableStateOf<String?>(null) }
    val app1PinLength = remember { mutableStateOf(0) }  // Track App1's PIN digit count

    // Store the first app's credential temporarily to compare with the second
    val firstAppCredential = remember { mutableStateOf<String?>(null) }
    
    // Biometric settings
    val app1BiometricEnabled = remember { mutableStateOf(false) }
    val app2BiometricEnabled = remember { mutableStateOf(false) }
    val app1BiometricOnly = remember { mutableStateOf(true) }
    val app2BiometricOnly = remember { mutableStateOf(true) }
    val groupBackupType = remember { mutableStateOf<String?>(null) }
    val app1BackupType = remember { mutableStateOf("PIN") }
    val app2BackupType = remember { mutableStateOf("PIN") }
    val app1BackupPin = remember { mutableStateOf("") }
    val app2BackupPin = remember { mutableStateOf("") }

    fun finalizeBiometricFlow() {
        (activity as? PasswordSetupActivity)?.saveBiometricForApps(
            groupId = groupId,
            app1Enabled = app1BiometricEnabled.value,
            app2Enabled = app2BiometricEnabled.value,
            app1BiometricOnly = app1BiometricOnly.value,
            app2BiometricOnly = app2BiometricOnly.value,
            app1BackupType = groupBackupType.value ?: app1BackupType.value,
            app2BackupType = groupBackupType.value ?: app1BackupType.value,
            app1BackupPin = app1BackupPin.value,
            app2BackupPin = app2BackupPin.value
        )
        (activity as? PasswordSetupActivity)?.completePasswordSetup(groupId)
    }

    // resetKey forces PinEntryScreen recomposition when changed
    val resetKey = remember { mutableStateOf(0) }

    // Load app names for UI
    val app1Name = remember { mutableStateOf("App 1") }
    val app2Name = remember { mutableStateOf("App 2") }
    val groupName = remember { mutableStateOf("Group") }

    // Load actual names from SharedPreferences
    val sharedPrefs = SharedPreferencesManager.getInstance(activity as ComponentActivity)
    val appGroup = remember { mutableStateOf(sharedPrefs.getAppGroupForSetup(groupId)) }
    appGroup.value?.let { group ->
        if (group.app1Name.isNotEmpty()) app1Name.value = group.app1Name
        if (group.app2Name.isNotEmpty()) app2Name.value = group.app2Name
        if (group.groupName.isNotEmpty()) groupName.value = group.groupName
    }
    val hapticIntensity = sharedPrefs.getGlobalVibrationIntensity().coerceIn(0, 100)
    
    val isSelectingAuth = selectedAuthType.value == null
    val navigateBackToGroupName: () -> Unit = {
        (activity as? PasswordSetupActivity)?.navigateBackToGroupName()
        Unit
    }

    // Keep hardware back consistent with toolbar back during setup flow.
    BackHandler {
        navigateBackToGroupName()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1a1a2e),
                        Color(0xFF16213e)
                    )
                )
            )
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = navigateBackToGroupName,
                    modifier = Modifier.weight(0.1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Lock Authentication",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9575CD),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(0.8f)
                )
                Spacer(modifier = Modifier.weight(0.1f))
            }

            // Info text
            if (isSelectingAuth) {
                Text(
                    text = "Choose your authentication method to secure your dual apps",
                    fontSize = 13.sp,
                    color = Color(0xFFB0B0B0),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Content container that takes remaining space
            Box(modifier = Modifier.weight(1f)) {
                // When not chosen auth type yet, show selection
                if (selectedAuthType.value == null) {
                    AuthenticationTypeSelection(
                        onAuthTypeSelected = { authType ->
                            selectedAuthType.value = authType
                            app1AuthType.value = authType
                            app2AuthType.value = authType
                        }
                    )
                } else {
                    // Show Pin entry for the current target app
                    val appName = if (targetAppIndex.value == 1) app1Name.value else app2Name.value
                    val appPackageName = if (targetAppIndex.value == 1) {
                        appGroup.value?.app1PackageName
                    } else {
                        appGroup.value?.app2PackageName
                    }
                    // Use key with resetKey and target index to force a fresh PinEntryScreen when switching
                    key(resetKey.value, targetAppIndex.value, selectedAuthType.value) {
                        CredentialEntryScreen(
                            authType = selectedAuthType.value ?: "PIN",
                            forAppName = appName,
                            appPackageName = appPackageName,
                            forbiddenCredential = if (targetAppIndex.value == 2) firstAppCredential.value else null,
                            appIndex = targetAppIndex.value,
                            app1PinLength = app1PinLength.value,
                            groupCreatedName = groupName.value,
                            hapticIntensity = hapticIntensity,
                            onCredentialConfirmed = { credential ->
                                val authType = selectedAuthType.value ?: "PIN"
                                
                                // Store credential temporarily
                                if (targetAppIndex.value == 1) {
                                    app1Credential.value = credential
                                    app1PinLength.value = if (authType.uppercase() == "PIN") credential.length else 0
                                    app1AuthType.value = authType
                                    app1BackupType.value = authType
                                    app2BackupType.value = authType
                                    groupBackupType.value = authType
                                    app1BackupPin.value = credential
                                    firstAppCredential.value = credential
                                    if (activeCredentialApp.value != null || pendingCredentialApps.isNotEmpty()) {
                                        if (pendingCredentialApps.isNotEmpty()) {
                                            activeCredentialApp.value = pendingCredentialApps.removeAt(0)
                                            val sharedType = groupBackupType.value
                                            if (sharedType != null) {
                                                showBiometricBackupPinDialog.value = null
                                                selectedAuthType.value = sharedType
                                                targetAppIndex.value = activeCredentialApp.value ?: 1
                                                resetKey.value = resetKey.value + 1
                                            } else {
                                                showBiometricBackupPinDialog.value = activeCredentialApp.value
                                                selectedAuthType.value = null
                                            }
                                        } else {
                                            activeCredentialApp.value = null
                                            selectedAuthType.value = null
                                            finalizeBiometricFlow()
                                        }
                                    } else {
                                        targetAppIndex.value = 2
                                        resetKey.value = resetKey.value + 1
                                    }
                                } else {
                                    // Both apps done, now save at final step
                                    app2Credential.value = credential
                                    app2AuthType.value = authType
                                    app2BackupType.value = authType
                                    app1BackupType.value = authType
                                    groupBackupType.value = authType
                                    app2BackupPin.value = credential

                                    if (activeCredentialApp.value != null || pendingCredentialApps.isNotEmpty()) {
                                        if (pendingCredentialApps.isNotEmpty()) {
                                            activeCredentialApp.value = pendingCredentialApps.removeAt(0)
                                            val sharedType = groupBackupType.value
                                            if (sharedType != null) {
                                                showBiometricBackupPinDialog.value = null
                                                selectedAuthType.value = sharedType
                                                targetAppIndex.value = activeCredentialApp.value ?: 2
                                                resetKey.value = resetKey.value + 1
                                            } else {
                                                showBiometricBackupPinDialog.value = activeCredentialApp.value
                                                selectedAuthType.value = null
                                            }
                                        } else {
                                            activeCredentialApp.value = null
                                            selectedAuthType.value = null
                                            finalizeBiometricFlow()
                                        }
                                    } else {
                                        // Save both credentials together
                                        (activity as? PasswordSetupActivity)?.saveBothCredentials(
                                            groupId = groupId,
                                            app1AuthType = app1AuthType.value ?: "PIN",
                                            app1RawCredential = app1Credential.value ?: "",
                                            app2AuthType = app2AuthType.value ?: "PIN",
                                            app2RawCredential = app2Credential.value ?: ""
                                        )

                                        (activity as? PasswordSetupActivity)?.completePasswordSetup(groupId)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // Biometric Apps Selection Dialog
        if (showBiometricAppsDialog.value) {
            val app1Icon = remember {
                try {
                    activity.packageManager.getApplicationIcon(appGroup.value?.app1PackageName ?: "")
                } catch (_: Exception) {
                    null
                }
            }

            val app2Icon = remember {
                try {
                    activity.packageManager.getApplicationIcon(appGroup.value?.app2PackageName ?: "")
                } catch (_: Exception) {
                    null
                }
            }

            BiometricAppsSelectionDialog(
                app1Name = app1Name.value,
                app2Name = app2Name.value,
                app1Enabled = app1BiometricEnabled.value,
                app2Enabled = app2BiometricEnabled.value,
                onApp1Changed = { app1BiometricEnabled.value = it },
                onApp2Changed = { app2BiometricEnabled.value = it },
                onDismiss = { showBiometricAppsDialog.value = false },
                onConfirm = {
                    showBiometricAppsDialog.value = false
                    showBiometricBackupDialog.value = true
                },
                app1Icon = app1Icon,
                app2Icon = app2Icon
            )
        }

        // Skip BiometricPolicyDialog - always use backup policy
        if (showBiometricBackupDialog.value) {
            showBiometricBackupDialog.value = false
            pendingCredentialApps.clear()
            groupBackupType.value = null
            app1BackupType.value = "PIN"
            app2BackupType.value = "PIN"

            // With the new single-app biometric policy, BOTH apps always need credentials
            // - The biometric app needs a backup credential
            // - The non-biometric app needs its regular credential
            pendingCredentialApps.add(1)
            pendingCredentialApps.add(2)

            if (pendingCredentialApps.isNotEmpty()) {
                activeCredentialApp.value = pendingCredentialApps.removeAt(0)
                showBiometricBackupPinDialog.value = activeCredentialApp.value
            }
        }

        // Backup PIN/PASSWORD/PATTERN Dialogs
        if (showBiometricBackupPinDialog.value == 1) {
            BiometricBackupCredentialDialog(
                groupName = groupName.value,
                onBackupTypeSelected = { backupType ->
                    app1BackupType.value = backupType
                    app2BackupType.value = backupType
                    groupBackupType.value = backupType
                    showBiometricBackupPinDialog.value = null
                    activeCredentialApp.value = 1
                    // Show PIN entry for backup
                    selectedAuthType.value = backupType
                    targetAppIndex.value = 1
                    resetKey.value = resetKey.value + 1
                }
            )
        }

        if (showBiometricBackupPinDialog.value == 2) {
            BiometricBackupCredentialDialog(
                groupName = groupName.value,
                onBackupTypeSelected = { backupType ->
                    app2BackupType.value = backupType
                    app1BackupType.value = backupType
                    groupBackupType.value = backupType
                    showBiometricBackupPinDialog.value = null
                    activeCredentialApp.value = 2
                    // Show PIN entry for backup
                    selectedAuthType.value = backupType
                    targetAppIndex.value = 2
                    resetKey.value = resetKey.value + 1
                }
            )
        }

    }
}

@Composable
fun AuthenticationTypeSelection(
    onAuthTypeSelected: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val authTypes = listOf(
            AuthType("PIN", "4 digit PIN", "PIN", iconResourceId = R.drawable.ic_pin, enabled = true),
            AuthType("PASSWORD", "Alphanumeric password", "PWD", iconResourceId = R.drawable.ic_pwd, enabled = true),
            AuthType("PATTERN", "Draw a pattern", "PAT", iconResourceId = R.drawable.ic_pat, enabled = true)
        )

        authTypes.forEach { authType ->
            AuthenticationTypeSelectionCard(
                authType = authType,
                onSelected = { if (authType.enabled) onAuthTypeSelected(authType.name) }
            )
        }

        // Note section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.bulb),
                contentDescription = "Note",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Note: You can add biometric unlock after this setup",
                fontSize = 12.sp,
                color = Color(0xFF7DB8DE),
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

data class AuthType(
    val name: String,
    val description: String,
    val icon: String,
    val iconResourceId: Int = 0,
    val enabled: Boolean = true
)

@Composable
fun AuthenticationTypeSelectionCard(
    authType: AuthType,
    onSelected: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(8.dp))
            .then(if (authType.enabled) Modifier.clickable { onSelected() } else Modifier)
            .background(
                if (authType.enabled) Color(0xFF2a2a3e) else Color(0xFF1a1a1a)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (authType.enabled) Color(0xFF2a2a3e) else Color(0xFF1a1a1a))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = authType.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (authType.enabled) Color(0xFF9575CD) else Color(0xFF555555)
                )
                Text(
                    text = if (authType.enabled) authType.description else "Device Does not support Fingerprint",
                    fontSize = 11.sp,
                    color = if (authType.enabled) Color(0xFFB0B0B0) else Color.White,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (authType.iconResourceId != 0) {
                Image(
                    painter = painterResource(id = authType.iconResourceId),
                    contentDescription = authType.name,
                    modifier = Modifier
                        .size(56.dp)
                        .padding(start = 12.dp),
                    alpha = if (authType.enabled) 1f else 0.5f
                )
            }
        }
    }
}

@Composable
fun CredentialEntryScreen(
    authType: String = "PIN",
    forAppName: String = "App",
    appPackageName: String? = null,
    forbiddenCredential: String? = null,
    appIndex: Int = 1,
    app1PinLength: Int = 0,
    groupCreatedName: String = "",
    hapticIntensity: Int = 100,
    onCredentialConfirmed: (String) -> Unit = {}
) {
    val normalizedType = authType.uppercase()
    val isPinMode = normalizedType == "PIN"
    val isPatternMode = normalizedType == "PATTERN"
    val minLength = when {
        isPatternMode -> if (appIndex == 1) 1 else (if (app1PinLength > 0) app1PinLength else 1)
        isPinMode -> if (appIndex == 1) 1 else (if (app1PinLength > 0) app1PinLength else 4)
        else -> 6
    }
    val maxLength = when {
        isPinMode -> if (appIndex == 1) 10 else (if (app1PinLength > 0) app1PinLength else 10)
        isPatternMode -> 9
        else -> 32
    }

    val firstInput = remember { mutableStateOf("") }
    val confirmInput = remember { mutableStateOf("") }
    val confirmationPinLength = remember { mutableStateOf(0) }
    val step = remember { mutableStateOf(0) }
    val errorMessage = remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var isClearingPin by remember { mutableStateOf(false) }
    var pinDotsAlphaTarget by remember { mutableStateOf(1f) }
    val pinDotsAlpha by animateFloatAsState(
        targetValue = pinDotsAlphaTarget,
        animationSpec = tween(180),
        label = "SetupPinDotsAlpha"
    )

    val setupAppIcon = remember(appPackageName) {
        try {
            appPackageName?.let { pkg ->
                context.packageManager.getApplicationIcon(pkg).toBitmap().asImageBitmap()
            }
        } catch (_: Exception) {
            null
        }
    }

    val currentState = if (step.value == 0) firstInput else confirmInput
    val currentValue = currentState.value
    val isButtonEnabled = currentValue.length >= minLength
    fun currentPinEntryMaxLength(): Int = when {
        isPinMode && step.value == 1 && confirmationPinLength.value > 0 -> confirmationPinLength.value
        else -> maxLength
    }

    fun advanceWithValue(inputValue: String) {
        val stepMaxLength = when {
            isPinMode && step.value == 1 && confirmationPinLength.value > 0 -> confirmationPinLength.value
            else -> maxLength
        }
        if (appIndex == 1 && isPinMode) {
            if (inputValue.isEmpty()) {
                errorMessage.value = "Please enter a PIN"
                return
            }
        } else if (inputValue.length < minLength) {
            errorMessage.value = if (isPatternMode) {
                "Please draw a pattern"
            } else if (isPinMode) {
                if (appIndex == 2 && app1PinLength > 0) {
                    "PIN must be $app1PinLength digits"
                } else {
                    "PIN must be at least 4 digits"
                }
            } else {
                "Password must be at least 6 characters"
            }
            return
        }

        when (step.value) {
            0 -> {
                if (forbiddenCredential != null && inputValue == forbiddenCredential) {
                    errorMessage.value = if (isPatternMode) {
                        "Same pattern cant be used for 2 apps"
                    } else if (isPinMode) {
                        "Same PIN cant be used for 2 apps"
                    } else {
                        "Same password cant be used for 2 apps"
                    }
                    firstInput.value = ""
                    confirmInput.value = ""
                    confirmationPinLength.value = 0
                } else {
                    firstInput.value = inputValue.take(stepMaxLength)
                    confirmationPinLength.value = firstInput.value.length
                    confirmInput.value = ""
                    step.value = 1
                    errorMessage.value = ""
                }
            }

            1 -> {
                confirmInput.value = inputValue.take(stepMaxLength)
                if (isPinMode && confirmationPinLength.value > 0 && confirmInput.value.length != confirmationPinLength.value) {
                    errorMessage.value = "PIN must be ${confirmationPinLength.value} digits"
                } else if (firstInput.value == confirmInput.value) {
                    onCredentialConfirmed(firstInput.value)
                } else {
                    errorMessage.value = if (isPatternMode) {
                        "Patterns do not match. Please try again."
                    } else if (isPinMode) {
                        "PINs do not match. Please try again."
                    } else {
                        "Passwords do not match. Please try again."
                    }
                    step.value = 0
                    firstInput.value = ""
                    confirmInput.value = ""
                    confirmationPinLength.value = 0
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Crossfade(
            targetState = setupAppIcon,
            animationSpec = tween(300),
            label = "SetupAppIconFade"
        ) { icon ->
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = "$forAppName icon",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x334A77B6), RoundedCornerShape(16.dp))
                )
            } else {
                Spacer(modifier = Modifier.size(72.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (step.value == 0) {
                "Enter " + when {
                    isPinMode -> "PIN"
                    isPatternMode -> "Pattern"
                    else -> "Password"
                } + " for $forAppName"
            } else {
                "Confirm " + when {
                    isPinMode -> "PIN"
                    isPatternMode -> "Pattern"
                    else -> "Password"
                } + " for $forAppName"
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF9575CD),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 4.dp),
            lineHeight = 22.sp
        )

        Text(
            text = "Step ${step.value + 1} of 2",
            fontSize = 12.sp,
            color = Color(0xFFB0B0B0),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (isPatternMode) {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (isPinMode) {
            PinDisplayBox(
                pin = currentValue,
                appIndex = appIndex,
                app1PinLength = app1PinLength,
                step = step.value,
                firstInputLength = confirmationPinLength.value,
                dotsAlpha = pinDotsAlpha,
                modifier = Modifier
                    .padding(top = 24.dp, bottom = 12.dp)
                    .height(72.dp)
                    .fillMaxWidth()
            )
        } else if (isPatternMode) {
            PatternCredentialBox(
                onPatternDrawn = { drawnPattern ->
                    if (step.value == 0) {
                        firstInput.value = drawnPattern.take(maxLength)
                    } else {
                        confirmInput.value = drawnPattern.take(maxLength)
                    }
                    advanceWithValue(drawnPattern)
                },
                hapticIntensity = hapticIntensity,
                modifier = Modifier
                    .size(280.dp)
            )
            Text(
                text = "Draw a pattern",
                fontSize = 12.sp,
                color = Color(0xFFB0B0B0),
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )
        }

        if (errorMessage.value.isNotEmpty()) {
            Text(
                text = errorMessage.value,
                fontSize = 12.sp,
                color = Color(0xFFFF6B6B),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (!isPinMode && !isPatternMode) {
            OutlinedTextField(
                value = currentState.value,
                onValueChange = { value ->
                    currentState.value = value.take(maxLength)
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                label = { Text("Password (min 6 chars)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .focusRequester(focusRequester)
            )

            LaunchedEffect(step.value) {
                delay(120)
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { advanceWithValue(currentValue) },
                enabled = isButtonEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Purple40,
                    disabledContainerColor = Purple40.copy(alpha = 0.38f),
                    disabledContentColor = Color.White.copy(alpha = 0.38f)
                )
            ) {
                Text(
                    text = if (step.value == 0) "Next" else "Confirm",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isButtonEnabled) Color.White else Color.White.copy(alpha = 0.38f)
                )
            }
        }

        if (isPinMode) {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (isPinMode) {
            VirtualNumberKeypad(
                onNumberClick = { number ->
                    val targetState = if (step.value == 0) firstInput else confirmInput
                    if (!isClearingPin && targetState.value.length < currentPinEntryMaxLength()) {
                        targetState.value += number
                        errorMessage.value = ""
                    }
                },
                onBackspace = {
                    val targetState = if (step.value == 0) firstInput else confirmInput
                    if (!isClearingPin && targetState.value.isNotEmpty()) {
                        targetState.value = targetState.value.dropLast(1)
                        errorMessage.value = ""
                    }
                },
                onBackspaceLongPress = {
                    val targetState = if (step.value == 0) firstInput else confirmInput
                    if (!isClearingPin && targetState.value.isNotEmpty()) {
                        isClearingPin = true
                        pinDotsAlphaTarget = 0f
                        scope.launch {
                            delay(140)
                            targetState.value = ""
                            errorMessage.value = ""
                            pinDotsAlphaTarget = 1f
                            delay(120)
                            isClearingPin = false
                        }
                    }
                },
                onConfirm = { advanceWithValue(currentValue) },
                isConfirmEnabled = isButtonEnabled,
                confirmLabel = if (step.value == 0) "Next" else "Confirm",
                hapticIntensity = hapticIntensity
            )
        }
    }
}

@Composable
fun VirtualNumberKeypad(
    onNumberClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLongPress: () -> Unit = {},
    onConfirm: () -> Unit,
    isConfirmEnabled: Boolean = true,
    confirmLabel: String = "Confirm",
    hapticIntensity: Int = 100,
    buttonSize: androidx.compose.ui.unit.Dp = 76.dp,
    buttonPadding: androidx.compose.ui.unit.Dp = 6.dp,
    numberFontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    confirmArrowFontSize: androidx.compose.ui.unit.TextUnit = 24.sp,
    rowSpacing: androidx.compose.ui.unit.Dp = 0.dp
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
        // Rows 1-3: Numbers 1-9 – centered like lock screen keypad
        for (row in 0..2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                for (col in 1..3) {
                    val number = (row * 3) + col
                    NumberPadButton(
                        number = number.toString(),
                        onClick = { onNumberClick(number.toString()) },
                        enabled = true,
                        buttonSize = buttonSize,
                        buttonPadding = buttonPadding,
                        fontSize = numberFontSize,
                        hapticIntensity = hapticIntensity
                    )
                }
            }
        }

        // Bottom row: Backspace, 0, Continue arrow
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            // Backspace button on the left
            NumberPadButton(
                number = "⌫",
                onClick = onBackspace,
                onLongClick = onBackspaceLongPress,
                enabled = true,
                buttonSize = buttonSize,
                buttonPadding = buttonPadding,
                fontSize = numberFontSize,
                hapticIntensity = hapticIntensity
            )

            // 0 in the middle
            NumberPadButton(
                number = "0",
                onClick = { onNumberClick("0") },
                enabled = true,
                buttonSize = buttonSize,
                buttonPadding = buttonPadding,
                fontSize = numberFontSize,
                hapticIntensity = hapticIntensity
            )

            // Continue button with arrow on the right
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .padding(buttonPadding)
                    .shadow(
                        elevation = if (isConfirmEnabled) 3.dp else 0.dp,
                        shape = CircleShape
                    )
                    .clip(CircleShape)
                    .background(if (isConfirmEnabled) Color(0xFF9575CD) else Color(0xFF4A3B66))
                    .clickable(enabled = isConfirmEnabled, onClick = {
                        performKeypadHaptic(context, intensityPercent = hapticIntensity)
                        onConfirm()
                    }),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.back_arrow),
                    contentDescription = "Continue",
                    modifier = Modifier.size(36.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        if (isConfirmEnabled) Color.White else Color.White.copy(alpha = 0.7f)
                    )
                )
            }
        }
    }
}

@Composable
fun NumberPadButton(
    number: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    buttonSize: androidx.compose.ui.unit.Dp = 76.dp,
    buttonPadding: androidx.compose.ui.unit.Dp = 6.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
    hapticIntensity: Int = 100
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(buttonSize)
            .padding(buttonPadding)
            .shadow(
                elevation = if (enabled) 3.dp else 0.dp,
                shape = CircleShape
            )
            .clip(CircleShape)
            .background(if (enabled) Color(0xFF0F315C) else Color(0xFF0A213F))
            .pointerInput(enabled, onLongClick) {
                if (enabled) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val releasedBeforeTimeout = withTimeoutOrNull(500) {
                            waitForUpOrCancellation() != null
                        } == true

                        if (releasedBeforeTimeout) {
                            performKeypadHaptic(context, intensityPercent = hapticIntensity)
                            onClick()
                        } else if (onLongClick != null) {
                            performKeypadHaptic(context, intensityPercent = 60)
                            onLongClick()
                            waitForUpOrCancellation()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color(0xFF6D7B8F)
        )
    }
}

@Composable
fun PinDisplayBox(
    pin: String,
    appIndex: Int = 1,
    app1PinLength: Int = 0,
    step: Int = 0,
    firstInputLength: Int = 0,
    dotsAlpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    // Determine the total number of dots to display
    val totalDots = when {
        appIndex == 1 && step == 0 -> pin.length  // App1 Step 1: only show filled dots (dynamic)
        appIndex == 1 && step == 1 -> firstInputLength  // App1 Step 2: show firstInputLength total (from Step 1)
        appIndex == 2 && app1PinLength > 0 -> app1PinLength  // App2: show app1PinLength total
        else -> 10  // Default to 10
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2a2a3e))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.alpha(dotsAlpha),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Show dots: filled for entered digits, dark for empty positions
                repeat(totalDots) { index ->
                    val isFilled = index < pin.length
                    PinDot(isFilled = isFilled)
                }
            }
        }
    }
}

@Composable
fun PinDot(isFilled: Boolean) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(
                color = if (isFilled) Color(0xFF9575CD) else Color(0xFF3a3a4e),  // Dark gray for empty
                shape = RoundedCornerShape(50)
            )
            .then(
                if (!isFilled) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color(0xFF4a4a5e),
                        shape = RoundedCornerShape(50)
                    )
                } else {
                    Modifier
                }
            )
    )
}

@Composable
fun PatternCredentialBox(
    onPatternDrawn: (String) -> Unit,
    hapticIntensity: Int = 100,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2a2a3e))
    ) {
        PatternLockPad(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            enabled = true,
            hapticIntensity = hapticIntensity,
            onPatternDrawn = onPatternDrawn
        )
    }
}

@Composable
fun BiometricAppsSelectionDialog(
    app1Name: String,
    app2Name: String,
    app1Enabled: Boolean,
    app2Enabled: Boolean,
    onApp1Changed: (Boolean) -> Unit,
    onApp2Changed: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    app1Icon: android.graphics.drawable.Drawable? = null,
    app2Icon: android.graphics.drawable.Drawable? = null
) {
    var selectedApp by remember {
        mutableStateOf(when {
            app1Enabled -> "app1"
            app2Enabled -> "app2"
            else -> ""
        })
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
                    text = "Select one app to enable biometric unlock. The other app will use the backup authentication method.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // OFF Option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedApp = ""
                            onApp1Changed(false)
                            onApp2Changed(false)
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
                        .clickable {
                            selectedApp = "app1"
                            onApp1Changed(true)
                            onApp2Changed(false)
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
                                    contentDescription = app1Name,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = app1Name,
                                fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
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
                        .clickable {
                            selectedApp = "app2"
                            onApp1Changed(false)
                            onApp2Changed(true)
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
                                    contentDescription = app2Name,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = app2Name,
                                fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
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

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Policy: <Auth Type> + Biometric for selected app",
                    color = Color(0xFF7DB8DE),
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = true,
                onClick = {
                    onConfirm()
                }
            ) {
                Text("Next")
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
fun BiometricBackupCredentialDialog(
    groupName: String,
    onBackupTypeSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Color(0xFF03193B),
        title = {
            Text(
                text = "Backup for $groupName",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Choose authentication method for this app:",
                    color = Color(0xFFB0B0B0),
                    fontSize = 12.sp
                )

                val backupTypes = listOf(
                    AuthType("PIN", "4 digit PIN", "PIN", enabled = true),
                    AuthType("PASSWORD", "Alphanumeric password", "PWD", enabled = true),
                    AuthType("PATTERN", "Draw a pattern", "PAT", enabled = true)
                )

                backupTypes.forEach { authType ->
                    AuthenticationTypeSelectionCard(
                        authType = authType,
                        onSelected = { onBackupTypeSelected(authType.name) }
                    )
                }
            }
        },
        confirmButton = {}
    )
}


