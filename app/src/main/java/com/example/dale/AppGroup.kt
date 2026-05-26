package com.example.dale

data class AppGroup(
    val id: String = "",
    val groupName: String = "",
    val app1PackageName: String = "",
    val app1Name: String = "",
    val app2PackageName: String = "",
    val app2Name: String = "",
    val isLocked: Boolean = false,
    val app1LockPin: String = "",
    val app2LockPin: String = "",
    val app1LockType: String = "PIN",
    val app2LockType: String = "PIN",
    val app1FingerprintEnabled: Boolean = false,
    val app2FingerprintEnabled: Boolean = false,
    val app1FingerprintBiometricOnly: Boolean = false,
    val app2FingerprintBiometricOnly: Boolean = false,
    val app1UninstallProtectionEnabled: Boolean? = null,
    val app2UninstallProtectionEnabled: Boolean? = null,
    val app1PinLength: Int = 0,
    val app2PinLength: Int = 0,
    val vibrationIntensity: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isDisabledDueToUninstall: Boolean = false,
    val uninstalledAppName: String? = null
)
