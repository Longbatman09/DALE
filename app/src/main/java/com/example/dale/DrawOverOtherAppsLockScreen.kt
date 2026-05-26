package com.example.dale

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.dale.ui.theme.DALETheme
import com.example.dale.utils.performKeypadHaptic
import com.example.dale.utils.SharedPreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrawOverOtherAppsLockScreen : FragmentActivity() {

    private var targetPackageName by mutableStateOf<String?>(null)
    private var groupId by mutableStateOf<String?>(null)
    private var isPinVerified = false
    private var isDismissing = false
    private var canUseBiometricForTarget = false
    private var isBiometricOnlyForTarget = false
    private var biometricTriggeredOnce = false
    private var isBiometricPromptShowing = false
    private var isUninstallFlow = false

    private var biometricPrompt: BiometricPrompt? = null
    private var biometricPromptInfo: BiometricPrompt.PromptInfo? = null

    private val relaunchHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle system back: allow exiting the lock screen.
        // - Normal flow: behave like the top-left arrow (dismiss to home)
        // - Uninstall flow: finish the activity so the system uninstall dialog can continue
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try {
                    if (isUninstallFlow) {
                        // In uninstall flow we allow the back button to close the lock screen
                        // so the uninstall UI can proceed.
                        finishAndRemoveTask()
                    } else {
                        // Default dismiss behavior (records closed app and goes home)
                        dismissToHome()
                    }
                } catch (e: Exception) {
                    // Fallback: ensure activity finishes if anything unexpected occurs
                    finishAndRemoveTask()
                }
            }
        })

        if (!SharedPreferencesManager.getInstance(this).isProtectionEnabled()) {
            finishAndRemoveTask()
            return
        }

        // Keep screen awake and visible while lock screen is active.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        updateTargetState(intent)
        refreshBiometricState()

        // Safety check: Never show lock screen for DALE itself
        if (targetPackageName == packageName) {
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            DALETheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LockScreenContent(
                        modifier = Modifier.padding(innerPadding),
                        targetPackageName = targetPackageName,
                        groupId = groupId,
                        canUseBiometric = canUseBiometricForTarget,
                        biometricOnly = isBiometricOnlyForTarget && canUseBiometricForTarget,
                        isUninstallFlow = isUninstallFlow,
                        onBiometricRequested = { triggerBiometricPrompt() },
                        onUnlockSuccess = { unlockApp(it) },
                        onUnlockFail = { /* Handle fail */ },
                        onDismissRequested = { dismissToHome() },
                        onVerified = { isPinVerified = true }
                    )
                }
            }
        }

        tryAutoBiometricPrompt()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateTargetState(intent)
        refreshBiometricState()
        tryAutoBiometricPrompt()
    }

    private fun updateTargetState(intent: Intent?) {
        targetPackageName = intent?.getStringExtra("TARGET_PACKAGE")
        groupId = intent?.getStringExtra("GROUP_ID")
        isUninstallFlow = intent?.getBooleanExtra("IS_UNINSTALL_FLOW", false) == true
        biometricTriggeredOnce = false
    }

    private fun refreshBiometricState() {
        val biometricEnabled = isBiometricEnabledForCurrentTarget()
        isBiometricOnlyForTarget = isBiometricOnlyForCurrentTarget()
        canUseBiometricForTarget = isFingerprintAvailable() && biometricEnabled
        if (canUseBiometricForTarget) {
            prepareBiometricPrompt()
        } else {
            biometricPrompt = null
            biometricPromptInfo = null
        }
    }

    private fun isFingerprintAvailable(): Boolean {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return false
        return BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }


    private fun isBiometricEnabledForCurrentTarget(): Boolean {
        val currentGroupId = groupId ?: return false
        val currentTargetPackage = targetPackageName ?: return false
        val group = SharedPreferencesManager.getInstance(this).getAppGroup(currentGroupId) ?: return false
        return when (currentTargetPackage) {
            group.app1PackageName -> group.app1FingerprintEnabled
            group.app2PackageName -> group.app2FingerprintEnabled
            else -> false
        }
    }

    private fun isBiometricOnlyForCurrentTarget(): Boolean {
        val currentGroupId = groupId ?: return false
        val currentTargetPackage = targetPackageName ?: return false
        val group = SharedPreferencesManager.getInstance(this).getAppGroup(currentGroupId) ?: return false
        return when (currentTargetPackage) {
            group.app1PackageName -> group.app1FingerprintBiometricOnly
            group.app2PackageName -> group.app2FingerprintBiometricOnly
            else -> false
        }
    }

    private fun prepareBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    isBiometricPromptShowing = false
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isBiometricPromptShowing = false
                    targetPackageName?.let { unlockApp(it) }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Keep lockscreen visible; user can retry biometric or use credential.
                }
            }
        )

        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock with fingerprint")
            .setSubtitle("Authenticate to unlock this app")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
            )

        if (isBiometricOnlyForTarget) {
            promptBuilder.setNegativeButtonText("Cancel")
        } else {
            promptBuilder.setNegativeButtonText("Use lock credential")
        }

        biometricPromptInfo = promptBuilder.build()
    }

    private fun triggerBiometricPrompt() {
        if (!canUseBiometricForTarget || isBiometricPromptShowing || isFinishing) return
        val prompt = biometricPrompt ?: return
        val promptInfo = biometricPromptInfo ?: return

        isBiometricPromptShowing = true
        try {
            prompt.authenticate(promptInfo)
        } catch (_: Exception) {
            isBiometricPromptShowing = false
        }
    }

    private fun tryAutoBiometricPrompt() {
        if (!canUseBiometricForTarget || biometricTriggeredOnce) return
        biometricTriggeredOnce = true
        window.decorView.post { triggerBiometricPrompt() }
    }

    private fun unlockApp(packageName: String) {
        if (isUninstallFlow) {
            completeUninstallVerification(packageName)
            return
        }

        // Mark PIN as verified before unlocking
        isPinVerified = true

        val sourcePackage = targetPackageName
        val currentGroupId = groupId
        val crossUnlockSource = sourcePackage?.takeIf { it.isNotBlank() && it != packageName }
        val isCrossUnlock = crossUnlockSource != null

        // For cross-unlock, first app is explicitly closed/backgrounded before opening app 2.
        if (isCrossUnlock) {
            closeSourceAppBeforeCrossUnlock(crossUnlockSource, currentGroupId)
        }

        // Mark unlock transition immediately.
        sendBroadcast(Intent(AppMonitorService.ACTION_APP_UNLOCKING).apply {
            putExtra("UNLOCKED_PACKAGE", packageName)
            putExtra("SOURCE_PACKAGE", sourcePackage)
            putExtra("GROUP_ID", currentGroupId)
        })

        handler.postDelayed({
            // Mark unlock complete before launching target app.
            sendBroadcast(Intent(AppMonitorService.ACTION_APP_UNLOCKED).apply {
                putExtra("UNLOCKED_PACKAGE", packageName)
                putExtra("SOURCE_PACKAGE", sourcePackage)
                putExtra("GROUP_ID", currentGroupId)
            })

            recordAppOpenedLog(currentGroupId, packageName)

            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            finishAndRemoveTask()
        }, if (isCrossUnlock) 320 else 250)
    }

    private fun completeUninstallVerification(packageName: String) {
        isPinVerified = true

        sendBroadcast(Intent(AppMonitorService.ACTION_UNINSTALL_AUTH_GRANTED).apply {
            setPackage(this@DrawOverOtherAppsLockScreen.packageName)
            putExtra("TARGET_PACKAGE", packageName)
            putExtra("GROUP_ID", groupId)
        })

        Toast.makeText(applicationContext, "Uninstall within a minute", Toast.LENGTH_SHORT).show()
        DALEAppLockManager.isLockScreenShown.set(false)
        finishAndRemoveTask()
    }

    private fun closeSourceAppBeforeCrossUnlock(sourcePackage: String, groupId: String?) {
        recordAppClosedLog(groupId, sourcePackage)

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
    }

    private fun recordAppOpenedLog(groupId: String?, openedPackage: String) {
        val targetGroupId = groupId ?: return

        val sharedPrefs = SharedPreferencesManager.getInstance(this)
        val group = sharedPrefs.getAppGroup(targetGroupId) ?: return

        val appName = when (openedPackage) {
            group.app1PackageName -> group.app1Name
            group.app2PackageName -> group.app2Name
            else -> {
                try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(openedPackage, 0)
                    ).toString()
                } catch (_: Exception) {
                    openedPackage
                }
            }
        }

        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
            .format(Date())

        sharedPrefs.saveActivityLog(
            groupId = targetGroupId,
            entry = ActivityLogEntry(
                appName = appName,
                packageName = openedPackage,
                event = "OPENED",
                timestamp = timestamp
            )
        )
        
        // ✅ STEP 1: Store the last opened protected app with its group
        sharedPrefs.saveLastOpenedApp(
            packageName = openedPackage,
            groupId = targetGroupId,
            groupName = group.groupName,
            appName = appName
        )
        
        android.util.Log.d("ActivityLog", "✅ Saved last opened app: $appName ($openedPackage) in group ${group.groupName}")
    }

    private fun recordAppClosedLog(groupId: String?, closedPackage: String) {
        val targetGroupId = groupId ?: return

        val sharedPrefs = SharedPreferencesManager.getInstance(this)
        val group = sharedPrefs.getAppGroup(targetGroupId) ?: return

        val appName = when (closedPackage) {
            group.app1PackageName -> group.app1Name
            group.app2PackageName -> group.app2Name
            else -> {
                try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(closedPackage, 0)
                    ).toString()
                } catch (_: Exception) {
                    closedPackage
                }
            }
        }

        val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
            .format(Date())

        // ✅ FIX #6: Improved deduplication logic with null safety
        val lastEvent = sharedPrefs.getLatestActivityEventForPackage(targetGroupId, closedPackage)
        if (lastEvent?.uppercase(Locale.ROOT) == "CLOSED") {
            android.util.Log.d("DrawOverOtherAppsLockScreen", "Skipped duplicate CLOSED for $closedPackage")
            return
        }

        sharedPrefs.saveActivityLog(
            groupId = targetGroupId,
            entry = ActivityLogEntry(
                appName = appName,
                packageName = closedPackage,
                event = "CLOSED",
                timestamp = timestamp
            )
        )
    }

    private fun dismissToHome() {
        if (isUninstallFlow) return
        if (isFinishing || isDismissing || isPinVerified) return
        isDismissing = true
        relaunchHandler.removeCallbacksAndMessages(null)

        // Treat manual dismiss as closing the currently targeted protected app.
        targetPackageName?.let { recordAppClosedLog(groupId, it) }

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)
        finishAndRemoveTask()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isPinVerified && !isDismissing && !isBiometricPromptShowing) {
            relaunchHandler.postDelayed({
                if (!isFinishing && !isPinVerified && !isDismissing) {
                    bringToFront()
                }
            }, 120)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isPinVerified && !isDismissing && !isBiometricPromptShowing) {
            relaunchHandler.postDelayed({
                if (!isFinishing && !isPinVerified && !isDismissing) {
                    bringToFront()
                }
            }, 80)
        }
    }

    override fun onDestroy() {
        relaunchHandler.removeCallbacksAndMessages(null)
        DALEAppLockManager.isLockScreenShown.set(false)
        super.onDestroy()
    }

    private fun bringToFront() {
        val intent = Intent(this, DrawOverOtherAppsLockScreen::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("TARGET_PACKAGE", targetPackageName)
            putExtra("GROUP_ID", groupId)
            putExtra("IS_UNINSTALL_FLOW", isUninstallFlow)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshBiometricState()
        if (!SharedPreferencesManager.getInstance(this).isProtectionEnabled()) {
            finishAndRemoveTask()
        }
    }

    companion object {
        private val handler = Handler(Looper.getMainLooper())
    }
}

@Composable
fun LockScreenContent(
    modifier: Modifier = Modifier,
    targetPackageName: String?,
    groupId: String?,
    canUseBiometric: Boolean,
    biometricOnly: Boolean,
    isUninstallFlow: Boolean = false,
    onBiometricRequested: () -> Unit,
    onUnlockSuccess: (String) -> Unit,
    onUnlockFail: () -> Unit,
    onDismissRequested: () -> Unit,
    onVerified: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = SharedPreferencesManager.getInstance(context)

    var credentialInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var isClearingPin by remember { mutableStateOf(false) }
    var pinDotsAlphaTarget by remember { mutableStateOf(1f) }
    val pinDotsAlpha by animateFloatAsState(
        targetValue = pinDotsAlphaTarget,
        animationSpec = tween(180),
        label = "LockPinDotsAlpha"
    )
    val scope = rememberCoroutineScope()

    val appGroup = remember(groupId) {
        groupId?.let { sharedPrefs.getAppGroup(it) }
    }

    val appInfo = remember(appGroup, targetPackageName) {
        when {
            appGroup == null -> LockTarget("", "", "PIN", 0)
            targetPackageName == appGroup.app1PackageName ->
                LockTarget(appGroup.app1PackageName, appGroup.app1LockPin, appGroup.app1LockType, appGroup.app1PinLength)
            targetPackageName == appGroup.app2PackageName ->
                LockTarget(appGroup.app2PackageName, appGroup.app2LockPin, appGroup.app2LockType, appGroup.app2PinLength)
            else -> LockTarget("", "", "PIN", 0)
        }
    }

    val targetAppName = remember(appGroup, targetPackageName) {
        when {
            appGroup == null -> "this app"
            targetPackageName == appGroup.app1PackageName -> appGroup.app1Name.ifBlank { appGroup.app1PackageName }
            targetPackageName == appGroup.app2PackageName -> appGroup.app2Name.ifBlank { appGroup.app2PackageName }
            else -> targetPackageName ?: "this app"
        }
    }

    val normalizedLockType = appInfo.lockType.uppercase()
    val isPatternMode = normalizedLockType == "PATTERN"
    val isPinMode = normalizedLockType == "PIN"
    val expectedPinLength = (if (appInfo.pinLength > 0) appInfo.pinLength else 4).coerceIn(1, 10)
    val hapticIntensity = sharedPrefs.getGlobalVibrationIntensity().coerceIn(0, 100)

    suspend fun verifyAndUnlock(inputCredential: String) {
        if (isVerifying) return
        if (isPinMode && appInfo.pinLength > 0 && inputCredential.length != appInfo.pinLength) return
        if (isPinMode && appInfo.pinLength == 0 && inputCredential.length < 4) return  // Fallback for legacy PINs
        if (!isPinMode && !isPatternMode && inputCredential.length < 6) return

        isVerifying = true
        delay(150)

        val hashedInput = MessageDigest.getInstance("SHA-256")
            .digest(inputCredential.toByteArray())
            .joinToString("") { "%02x".format(it) }

        if (hashedInput == appInfo.lockHash) {
            errorMessage = null
            onVerified()
            onUnlockSuccess(appInfo.appPackage)
            isVerifying = false
            return
        }

        if (!isUninstallFlow) {
            val otherAppHash = when (appInfo.appPackage) {
                appGroup?.app1PackageName -> appGroup.app2LockPin
                appGroup?.app2PackageName -> appGroup.app1LockPin
                else -> null
            }
            val otherAppType = when (appInfo.appPackage) {
                appGroup?.app1PackageName -> appGroup.app2LockType
                appGroup?.app2PackageName -> appGroup.app1LockType
                else -> null
            }
            val otherAppPackage = when (appInfo.appPackage) {
                appGroup?.app1PackageName -> appGroup.app2PackageName
                appGroup?.app2PackageName -> appGroup.app1PackageName
                else -> null
            }

            if (
                otherAppHash != null &&
                otherAppPackage != null &&
                otherAppType?.uppercase() == appInfo.lockType.uppercase() &&
                hashedInput == otherAppHash
            ) {
                errorMessage = null
                onVerified()
                onUnlockSuccess(otherAppPackage)
                isVerifying = false
                return
            }
        }

        errorMessage = when {
            isPatternMode -> "Incorrect pattern"
            isPinMode -> "Incorrect PIN"
            else -> "Incorrect password"
        }
        delay(420)
        credentialInput = ""
        onUnlockFail()

        isVerifying = false
    }

    LaunchedEffect(credentialInput, isPinMode, appInfo.pinLength) {
        if (isPinMode) {
            if (credentialInput.length == expectedPinLength) {
                verifyAndUnlock(credentialInput)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF03193B),
                        Color(0xFF02122E)
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .zIndex(2f),
            horizontalArrangement = Arrangement.Start
        ) {
            if (!isUninstallFlow) {
                IconButton(onClick = onDismissRequested) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            val credentialCardModifier = if (isPinMode && !biometricOnly) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            }

            val credentialContent: @Composable ColumnScope.() -> Unit = {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .background(Color(0x332A4A73), shape = RoundedCornerShape(34.dp))
                            .border(1.dp, Color(0x664A77B6), RoundedCornerShape(34.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    Text(
                        text = when {
                            isUninstallFlow -> "Authenticate to uninstall"
                            isPatternMode -> "Draw Pattern"
                            isPinMode -> "Enter PIN"
                            else -> "Enter Password"
                        },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = if (isUninstallFlow) {
                            "$targetAppName requires your credentials"
                        } else {
                            "Authenticate to continue"
                        },
                        fontSize = 13.sp,
                        color = Color(0xFF9FB2CC),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (canUseBiometric) {
                        TextButton(
                            onClick = onBiometricRequested,
                            enabled = !isVerifying,
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Text("Use Fingerprint", color = Color(0xFFB6CCFF))
                        }
                    }

                    if (biometricOnly) {
                        Text(
                            text = "Biometric only enabled for this app",
                            color = Color(0xFFD6D6D6),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    } else if (isPinMode) {
                        Row(
                            modifier = Modifier
                                .alpha(pinDotsAlpha)
                                .fillMaxWidth()
                                .padding(top = 20.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(expectedPinLength) { index ->
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(
                                            color = if (index < credentialInput.length) Color(0xFF97B9FF) else Color(0xFF4A5A72),
                                            shape = RoundedCornerShape(7.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (index < credentialInput.length) Color(0xFFBBD2FF) else Color(0xFF5B6C86),
                                            RoundedCornerShape(7.dp)
                                        )
                                )
                            }
                        }
                    } else if (isPatternMode) {
                        Card(
                            modifier = Modifier
                                .padding(top = 180.dp)
                                .size(280.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF132E56)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x334C78AD))
                        ) {
                            PatternLockPad(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                enabled = !isVerifying,
                                hapticIntensity = hapticIntensity,
                                onPatternDrawn = { patternValue ->
                                    if (!isVerifying) {
                                        errorMessage = null
                                        scope.launch { verifyAndUnlock(patternValue) }
                                    }
                                }
                            )
                        }

                        Text(
                            text = "Draw a pattern",
                            color = Color(0xFF9FB2CC),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = credentialInput,
                            onValueChange = { credentialInput = it.take(32) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Password") },
                            enabled = !isVerifying,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8EB2F4),
                                unfocusedBorderColor = Color(0xFF4A5E7E),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0C2447),
                                unfocusedContainerColor = Color(0xFF0C2447),
                                focusedLabelColor = Color(0xFFABC7FF),
                                unfocusedLabelColor = Color(0xFF8AA3C7)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = errorMessage != null,
                        enter = fadeIn(animationSpec = tween(250)),
                        exit = fadeOut(animationSpec = tween(250))
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFFF7878),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    if (!isPinMode && !isPatternMode) {
                        Button(
                            onClick = {
                                if (!isVerifying) {
                                    errorMessage = null
                                    scope.launch { verifyAndUnlock(credentialInput) }
                                }
                            },
                            enabled = credentialInput.length >= 6 && !isVerifying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .padding(top = 14.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E5E9E),
                                disabledContainerColor = Color(0xFF1A3152)
                            )
                        ) {
                            Text("Unlock", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

            Column(
                modifier = credentialCardModifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = credentialContent
            )

            if (isPinMode && !biometricOnly) {
                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    for (row in 0..2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            for (col in 1..3) {
                                val number = (row * 3) + col
                                NumberButton(
                                    number = number.toString(),
                                    onClick = {
                                        if (!isClearingPin && credentialInput.length < expectedPinLength && !isVerifying) {
                                            credentialInput += number
                                            errorMessage = null
                                        }
                                    },
                                    enabled = !isVerifying,
                                    hapticIntensity = hapticIntensity
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        NumberButton(
                            number = "⌫",
                            onClick = {
                                if (!isClearingPin && credentialInput.isNotEmpty() && !isVerifying) {
                                    credentialInput = credentialInput.dropLast(1)
                                    errorMessage = null
                                }
                            },
                            onLongClick = {
                                if (!isClearingPin && credentialInput.isNotEmpty() && !isVerifying) {
                                    isClearingPin = true
                                    pinDotsAlphaTarget = 0f
                                    scope.launch {
                                        delay(140)
                                        credentialInput = ""
                                        errorMessage = null
                                        pinDotsAlphaTarget = 1f
                                        delay(120)
                                        isClearingPin = false
                                    }
                                }
                            },
                            enabled = !isVerifying,
                            hapticIntensity = hapticIntensity
                        )

                        NumberButton(
                            number = "0",
                            onClick = {
                                if (!isClearingPin && credentialInput.length < expectedPinLength && !isVerifying) {
                                    credentialInput += "0"
                                    errorMessage = null
                                }
                            },
                            enabled = !isVerifying,
                            hapticIntensity = hapticIntensity
                        )

                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .padding(6.dp)
                                .shadow(
                                    elevation = if (!isVerifying) 3.dp else 0.dp,
                                    shape = CircleShape
                                )
                                .clip(CircleShape)
                                .background(if (!isVerifying) Color(0xFF0F315C) else Color(0xFF0A213F))
                                .clickable(enabled = !isVerifying, onClick = {
                                    performKeypadHaptic(context, intensityPercent = hapticIntensity)
                                    scope.launch { verifyAndUnlock(credentialInput) }
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.back_arrow),
                                contentDescription = "Continue",
                                modifier = Modifier.size(36.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    if (!isVerifying) Color.White else Color.White.copy(alpha = 0.7f)
                                )
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

 private data class LockTarget(
    val appPackage: String,
    val lockHash: String,
    val lockType: String,
    val pinLength: Int = 0
 )

 @Composable
 fun NumberButton(
    number: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    hapticIntensity: Int = 100
 ) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(76.dp)
            .padding(6.dp)
            .shadow(
                elevation = if (enabled) 3.dp else 0.dp,
                shape = CircleShape
            )
            .background(
                if (enabled) Color(0xFF0F315C) else Color(0xFF0A213F),
                shape = CircleShape
            )
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
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color(0xFF6D7B8F)
        )
    }
 }
