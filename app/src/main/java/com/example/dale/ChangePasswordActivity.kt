package com.example.dale

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dale.ui.theme.DALETheme
import com.example.dale.ui.theme.Purple80
import com.example.dale.utils.SharedPreferencesManager
import com.example.dale.utils.HashUtils
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChangePasswordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val groupId = intent.getStringExtra("GROUP_ID") ?: ""
        val groupName = intent.getStringExtra("GROUP_NAME") ?: ""
        val appPackage = intent.getStringExtra("APP_PACKAGE") ?: ""
        val isBackupRegistration = intent.getBooleanExtra("IS_BACKUP_REGISTRATION", false)

        setContent {
            DALETheme {
                ChangePasswordScreen(
                    groupId = groupId,
                    groupName = groupName,
                    appPackage = appPackage,
                    isBackupRegistration = isBackupRegistration,
                    activity = this,
                    hashPin = { pin -> HashUtils.hashPin(pin) },
                    verifyPin = { input, stored -> HashUtils.verifyPin(input, stored) }
                )
            }
        }
    }
}

@Composable
fun ChangePasswordScreen(
    groupId: String,
    groupName: String,
    appPackage: String,
    isBackupRegistration: Boolean,
    activity: ComponentActivity,
    hashPin: (String) -> String,
    verifyPin: (String, String) -> Boolean
) {
    val sharedPrefs = SharedPreferencesManager.getInstance(activity)
    val group = remember(groupId, groupName) {
        when {
            groupId.isNotBlank() -> sharedPrefs.getAppGroup(groupId)
            groupName.isNotBlank() -> sharedPrefs.getAllAppGroups().firstOrNull { it.groupName == groupName }
            else -> null
        }
    }
    val hapticIntensity = sharedPrefs.getGlobalVibrationIntensity().coerceIn(0, 100)

    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(if (isBackupRegistration) 2 else 1) } // 1: current, 2: new, 3: confirm
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var isClearingPin by remember { mutableStateOf(false) }
    var pinDotsAlphaTarget by remember { mutableStateOf(1f) }
    val pinDotsAlpha by animateFloatAsState(
        targetValue = pinDotsAlphaTarget,
        animationSpec = tween(180),
        label = "ChangePinDotsAlpha"
    )

    val selectedLockType = remember(group, appPackage) {
        when (appPackage) {
            group?.app1PackageName -> group.app1LockType
            group?.app2PackageName -> group.app2LockType
            else -> "PIN"
        }
    }
    val isPatternMode = selectedLockType.equals("PATTERN", ignoreCase = true)
    val isPasswordMode = selectedLockType.equals("PASSWORD", ignoreCase = true)
    val isPinMode = !isPatternMode && !isPasswordMode
    val credentialLabel = when {
        isPatternMode -> "Pattern"
        isPasswordMode -> "Password"
        else -> "PIN"
    }

    val appName = remember {
        try {
            activity.packageManager.getApplicationLabel(
                activity.packageManager.getApplicationInfo(appPackage, 0)
            ).toString()
        } catch (e: Exception) {
            appPackage
        }
    }

    val storedPinLength = remember(group, appPackage) {
        when (appPackage) {
            group?.app1PackageName -> group.app1PinLength
            group?.app2PackageName -> group.app2PinLength
            else -> 0
        }
    }
    val groupPinLength = remember(group) {
        group?.app1PinLength?.takeIf { it > 0 }
            ?: group?.app2PinLength?.takeIf { it > 0 }
            ?: 0
    }
    val pinMaxLength = if (storedPinLength > 0) storedPinLength else if (groupPinLength > 0) groupPinLength else 10
    val totalPinSteps = if (isBackupRegistration) 2 else 3
    val displayStepNumber = if (isBackupRegistration) step - 1 else step

    val appIcon = remember(appPackage) {
        try {
            activity.packageManager.getApplicationIcon(appPackage).toBitmap().asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    val currentPinValue = when (step) {
        1 -> currentPin
        2 -> newPin
        else -> confirmPin
    }

    fun processCredentialAttempt(attempt: String) {
        val minLength = when {
            isPatternMode -> 1
            isPasswordMode -> 6
            groupPinLength > 0 -> groupPinLength
            else -> 4
        }

        if (attempt.length < minLength) {
            errorMessage = when {
                isPatternMode -> "Please draw a pattern"
                isPasswordMode -> "Password must be at least 6 characters"
                groupPinLength > 0 -> "PIN must be $groupPinLength digits"
                else -> "PIN must be at least 4 digits"
            }
            return
        }

        if (isPinMode && groupPinLength > 0 && attempt.length != groupPinLength) {
            errorMessage = "PIN must be $groupPinLength digits"
            return
        }

        when (step) {
            1 -> {
                val storedPin = if (appPackage == group?.app1PackageName) {
                    group?.app1LockPin
                } else {
                    group?.app2LockPin
                }

                if (storedPin != null && verifyPin(attempt, storedPin)) {
                    errorMessage = ""
                    currentPin = attempt
                    step = 2
                } else {
                    errorMessage = when {
                        isPatternMode -> "Incorrect pattern"
                        isPasswordMode -> "Incorrect password"
                        else -> "Incorrect PIN"
                    }
                    currentPin = ""
                }
            }

            2 -> {
                val oldPin = if (appPackage == group?.app1PackageName) {
                    group?.app1LockPin
                } else {
                    group?.app2LockPin
                }

                val otherAppPin = if (appPackage == group?.app1PackageName) {
                    group?.app2LockPin
                } else {
                    group?.app1LockPin
                }

                val otherAppName = if (appPackage == group?.app1PackageName) {
                    group?.app2Name
                } else {
                    group?.app1Name
                }

                val isOldPin = oldPin != null && verifyPin(attempt, oldPin)
                val isOtherAppPin = otherAppPin != null && verifyPin(attempt, otherAppPin)

                when {
                    isOldPin -> {
                        errorMessage = when {
                            isPatternMode -> "Same as old pattern"
                            isPasswordMode -> "Same as old password"
                            else -> "Same as old PIN"
                        }
                        newPin = ""
                    }

                    isOtherAppPin -> {
                        errorMessage = when {
                            isPatternMode -> "Same as $otherAppName pattern"
                            isPasswordMode -> "Same as $otherAppName password"
                            else -> "Same as $otherAppName PIN"
                        }
                        newPin = ""
                    }

                    else -> {
                        errorMessage = ""
                        newPin = attempt
                        step = 3
                    }
                }
            }

            3 -> {
                confirmPin = attempt
                if (newPin == confirmPin) {
                    if (group != null) {
                        val hashedPin = hashPin(newPin)
                        val newPinLength = groupPinLength.takeIf { it > 0 } ?: newPin.length
                        val updatedGroup = if (appPackage == group.app1PackageName) {
                            group.copy(
                                app1LockPin = hashedPin,
                                app1PinLength = if (isPinMode) newPinLength else group.app1PinLength,
                                app1FingerprintBiometricOnly = if (isBackupRegistration) false else group.app1FingerprintBiometricOnly
                            )
                        } else {
                            group.copy(
                                app2LockPin = hashedPin,
                                app2PinLength = if (isPinMode) newPinLength else group.app2PinLength,
                                app2FingerprintBiometricOnly = if (isBackupRegistration) false else group.app2FingerprintBiometricOnly
                            )
                        }
                        sharedPrefs.saveAppGroup(updatedGroup)
                        Toast.makeText(
                            activity,
                            when {
                                isBackupRegistration && isPatternMode -> "Backup pattern set successfully"
                                isBackupRegistration && isPasswordMode -> "Backup password set successfully"
                                isBackupRegistration -> "Backup PIN set successfully"
                                isPatternMode -> "Pattern changed successfully"
                                isPasswordMode -> "Password changed successfully"
                                else -> "PIN changed successfully"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        activity.finish()
                    }
                } else {
                    errorMessage = when {
                        isPatternMode -> "Patterns don't match"
                        isPasswordMode -> "Passwords don't match"
                        else -> "PINs don't match"
                    }
                    confirmPin = ""
                }
            }
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
                    text = when {
                        isBackupRegistration && isPatternMode -> "Set Backup Pattern"
                        isBackupRegistration && isPasswordMode -> "Set Backup Password"
                        isBackupRegistration -> "Set Backup PIN"
                        isPatternMode -> "Change Pattern"
                        isPasswordMode -> "Change Password"
                        else -> "Change PIN"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Content
            if (isPinMode) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.weight(0.28f))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-20).dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Crossfade(
                            targetState = appIcon,
                            animationSpec = tween(300),
                            label = "ChangePinAppIconFade"
                        ) { icon ->
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = "$appName icon",
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
                            text = when (step) {
                                1 -> "Enter Current PIN for $appName"
                                2 -> if (isBackupRegistration) {
                                    "Enter Backup PIN for $appName"
                                } else {
                                    "Enter New PIN for $appName"
                                }
                                else -> "Confirm New PIN for $appName"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9575CD),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 4.dp),
                            lineHeight = 22.sp
                        )

                        Text(
                            text = "Step $displayStepNumber of $totalPinSteps",
                            fontSize = 12.sp,
                            color = Color(0xFFB0B0B0),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        PinDisplayBox(
                            pin = currentPinValue,
                            appIndex = if ((step == 1 && storedPinLength > 0) || groupPinLength > 0) 2 else 1,
                            app1PinLength = if (groupPinLength > 0) groupPinLength else storedPinLength,
                            step = if (step == 3) 1 else 0,
                            firstInputLength = newPin.length,
                            dotsAlpha = pinDotsAlpha,
                            modifier = Modifier
                                .padding(top = 12.dp, bottom = 8.dp)
                                .height(72.dp)
                                .fillMaxWidth()
                        )
                    }

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            color = Color(0xFFFF6B6B),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Keep keypad closer to the PIN field (not pinned to bottom)
                    Spacer(modifier = Modifier.height(20.dp))

                    val pinEntryMaxLength = when {
                        step == 3 && newPin.isNotEmpty() -> newPin.length
                        step == 2 -> if (groupPinLength > 0) groupPinLength else 10
                        storedPinLength > 0 -> storedPinLength
                        groupPinLength > 0 -> groupPinLength
                        else -> pinMaxLength
                    }
                    val pinMinLengthForStep = when {
                        step == 3 -> if (newPin.isNotEmpty()) newPin.length else 4
                        step == 2 -> if (groupPinLength > 0) groupPinLength else 4
                        storedPinLength > 0 -> storedPinLength
                        groupPinLength > 0 -> groupPinLength
                        else -> 4
                    }
                    val isConfirmEnabled = when (step) {
                        3 -> currentPinValue.length == newPin.length && newPin.isNotEmpty()
                        else -> currentPinValue.length >= pinMinLengthForStep
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                    VirtualNumberKeypad(
                        onNumberClick = { number ->
                            if (!isClearingPin && currentPinValue.length < pinEntryMaxLength) {
                                when (step) {
                                    1 -> currentPin += number
                                    2 -> newPin += number
                                    else -> confirmPin += number
                                }
                                errorMessage = ""
                            }
                        },
                        onBackspace = {
                            if (!isClearingPin) {
                                when (step) {
                                    1 -> if (currentPin.isNotEmpty()) currentPin = currentPin.dropLast(1)
                                    2 -> if (newPin.isNotEmpty()) newPin = newPin.dropLast(1)
                                    3 -> if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                                }
                                errorMessage = ""
                            }
                        },
                        onBackspaceLongPress = {
                            if (!isClearingPin && currentPinValue.isNotEmpty()) {
                                isClearingPin = true
                                pinDotsAlphaTarget = 0f
                                scope.launch {
                                    delay(140)
                                    when (step) {
                                        1 -> currentPin = ""
                                        2 -> newPin = ""
                                        3 -> confirmPin = ""
                                    }
                                    errorMessage = ""
                                    pinDotsAlphaTarget = 1f
                                    delay(120)
                                    isClearingPin = false
                                }
                            }
                        },
                        onConfirm = { processCredentialAttempt(currentPinValue) },
                        isConfirmEnabled = isConfirmEnabled,
                        confirmLabel = when (step) {
                            totalPinSteps -> "Confirm"
                            else -> "Next"
                        },
                        hapticIntensity = hapticIntensity
                    )
                    }
                }
            } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Crossfade(
                    targetState = appIcon,
                    animationSpec = tween(300),
                    label = "ChangeCredentialAppIconFade"
                ) { icon ->
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = "$appName icon",
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
                    text = when (step) {
                        1 -> "Enter Current $credentialLabel"
                        2 -> if (isBackupRegistration) "Enter Backup $credentialLabel" else "Enter New $credentialLabel"
                        else -> "Confirm New $credentialLabel"
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "for $appName",
                    fontSize = 16.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )

                if (isPatternMode) {
                    Spacer(modifier = Modifier.height(16.dp))

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    PatternChangePad(
                        onPatternDrawn = { pattern ->
                            errorMessage = ""
                            processCredentialAttempt(pattern)
                        },
                        hapticIntensity = hapticIntensity
                    )

                    Text(
                        text = "Draw a pattern",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(40.dp))
                } else {
                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedTextField(
                        value = when (step) {
                            1 -> currentPin
                            2 -> newPin
                            else -> confirmPin
                        },
                        onValueChange = { value ->
                            val trimmed = value.take(32)
                            when (step) {
                                1 -> currentPin = trimmed
                                2 -> newPin = trimmed
                                else -> confirmPin = trimmed
                            }
                            errorMessage = ""
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        label = { Text("Enter $credentialLabel") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple80,
                            unfocusedBorderColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }

                if (isPasswordMode) {
                    Button(
                        onClick = {
                            val attempt = when (step) {
                                1 -> currentPin
                                2 -> newPin
                                else -> confirmPin
                            }
                            processCredentialAttempt(attempt)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0f3460))
                    ) {
                        Text(
                            text = if (step < 3) "Continue" else "Confirm",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
fun PatternChangePad(
    onPatternDrawn: (String) -> Unit,
    hapticIntensity: Int = 100
) {
    Card(
        modifier = Modifier
            .size(280.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2a2a3e))
    ) {
        PatternLockPad(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            hapticIntensity = hapticIntensity,
            onPatternDrawn = onPatternDrawn
        )
    }
}
