package com.example.dale

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.dale.ui.theme.DALETheme
import com.example.dale.ui.theme.Purple40
import com.example.dale.ui.theme.Purple80
import com.example.dale.utils.SharedPreferencesManager
import android.content.pm.ResolveInfo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import java.util.Locale

class AppSelectionActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RETURN_TO_GROUP_NAME = "extra_return_to_group_name"
        const val EXTRA_EDIT_GROUP_ID = "extra_edit_group_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val returnToGroupName = intent.getBooleanExtra(EXTRA_RETURN_TO_GROUP_NAME, false)
        val editGroupId = intent.getStringExtra(EXTRA_EDIT_GROUP_ID).orEmpty()
        val initialSelectionState = if (returnToGroupName && editGroupId.isNotBlank()) 2 else 0
        val skipInitialLoadingForGroupEdit = returnToGroupName && editGroupId.isNotBlank()

        setContent {
            DALETheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppSelectionScreenWithLoading(
                        modifier = Modifier.padding(innerPadding),
                        activity = this,
                        initialSelectionState = initialSelectionState,
                        restoreGroupId = editGroupId,
                        skipInitialLoadingForGroupEdit = skipInitialLoadingForGroupEdit
                    )
                }
            }
        }
    }

    fun getInstalledAppsPublic(onPackageFound: (String) -> Unit = {}): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        val packageManager = packageManager
        val addedPackages = mutableSetOf<String>()

        // 1) Get all installed applications (include system apps)
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in installedApps) {
            onPackageFound(appInfo.packageName)
            if (packageName == appInfo.packageName) continue

            val label = try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                appInfo.packageName
            }

            val iconDrawable: Drawable? = try {
                packageManager.getApplicationIcon(appInfo.packageName)
            } catch (_: Exception) {
                null
            }

            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

            apps.add(
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = label,
                    icon = iconDrawable,
                    isSystem = isSystem,
                    isLauncher = false
                )
            )
            addedPackages.add(appInfo.packageName)
        }

        // 2) Also include apps that are launchable (ACTION_MAIN, CATEGORY_LAUNCHER)
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        for (resolveInfo in resolveInfos) {
            val ai = resolveInfo.activityInfo ?: continue
            val pkg = ai.packageName
            onPackageFound(pkg)
            if (pkg == packageName) continue
            if (addedPackages.contains(pkg)) continue

            val label = try {
                resolveInfo.loadLabel(packageManager).toString()
            } catch (_: Exception) {
                pkg
            }

            val iconDrawable: Drawable? = try {
                resolveInfo.loadIcon(packageManager)
            } catch (_: Exception) {
                null
            }

            val isSystem = try {
                val aiInfo = packageManager.getApplicationInfo(pkg, 0)
                (aiInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) {
                false
            }

            apps.add(
                AppInfo(
                    packageName = pkg,
                    appName = label,
                    icon = iconDrawable,
                    isSystem = isSystem,
                    isLauncher = true
                )
            )
            addedPackages.add(pkg)
        }

        // 3) Best-effort: if Island is installed, try to surface any additional launcher entries
        try {
            packageManager.getPackageInfo("com.oasisfeng.island", 0)
            val islandResolveInfos = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            for (resolveInfo in islandResolveInfos) {
                val ai = resolveInfo.activityInfo ?: continue
                val pkg = ai.packageName
                onPackageFound(pkg)
                if (pkg == packageName) continue
                if (addedPackages.contains(pkg)) continue

                val label = try {
                    resolveInfo.loadLabel(packageManager).toString()
                } catch (_: Exception) {
                    pkg
                }

                val iconDrawable: Drawable? = try {
                    resolveInfo.loadIcon(packageManager)
                } catch (_: Exception) {
                    null
                }

                val isSystem = try {
                    val aiInfo = packageManager.getApplicationInfo(pkg, 0)
                    (aiInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                } catch (_: Exception) {
                    false
                }

                apps.add(
                    AppInfo(
                        packageName = pkg,
                        appName = label,
                        icon = iconDrawable,
                        isSystem = isSystem,
                        isLauncher = true
                    )
                )
                addedPackages.add(pkg)
            }
        } catch (_: Exception) {
            // Island not installed — nothing extra to do
        }

        return apps.distinctBy { it.packageName }.sortedBy { it.appName }
    }
}

private const val MAX_GROUP_NAME_LENGTH = 30

@Composable
fun AppSelectionScreenWithLoading(
    modifier: Modifier = Modifier,
    activity: ComponentActivity? = null,
    initialSelectionState: Int = 0,
    restoreGroupId: String = "",
    skipInitialLoadingForGroupEdit: Boolean = false
) {
    val isLoading = remember { mutableStateOf(!skipInitialLoadingForGroupEdit) }
    val allApps = remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val currentPackageName = remember { mutableStateOf("") }
    val fadeDurationMs = 350

    // Keep loading visible during normal flow, but skip it when returning to edit group name.
    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        try {
            val apps = withContext(Dispatchers.IO) {
                (activity as? AppSelectionActivity)?.getInstalledAppsPublic { pkg ->
                    currentPackageName.value = pkg
                } ?: emptyList()
            }
            allApps.value = apps
        } catch (_: Exception) {
            allApps.value = emptyList()
        }

        if (skipInitialLoadingForGroupEdit) {
            isLoading.value = false
        } else {
            val minLoadingDurationMs = 2000L
            // Ensure the loading composable gets at least one frame before transition.
            withFrameNanos { }
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = minLoadingDurationMs - elapsed
            if (remaining > 0) delay(remaining)
            isLoading.value = false
        }
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
    ) {
        AnimatedVisibility(
            visible = isLoading.value,
            enter = fadeIn(animationSpec = tween(fadeDurationMs)),
            exit = fadeOut(animationSpec = tween(fadeDurationMs))
        ) {
            SearchingAppsLoadingScreen(currentPackageName.value)
        }

        AnimatedVisibility(
            visible = !isLoading.value,
            enter = fadeIn(animationSpec = tween(fadeDurationMs)),
            exit = fadeOut(animationSpec = tween(fadeDurationMs))
        ) {
            AppSelectionScreen(
                modifier = Modifier.fillMaxSize(),
                allApps = allApps.value,
                activity = activity,
                initialSelectionState = initialSelectionState,
                restoreGroupId = restoreGroupId
            )
        }
    }
}

@Composable
private fun SearchingAppsLoadingScreen(currentPackage: String = "") {
    val dotState = remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            dotState.intValue = (dotState.intValue + 1) % 4
            delay(350)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "Searching Apps" + ".".repeat(dotState.intValue),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Purple80
            )
            if (currentPackage.isNotBlank()) {
                Text(
                    text = currentPackage,
                    fontSize = 11.sp,
                    color = Purple80.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        LinearProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(6.dp),
            color = Purple40,
            trackColor = Color(0xFF0A2940)
        )
    }
}

@Composable
fun AppSelectionScreen(
    modifier: Modifier = Modifier,
    allApps: List<AppInfo> = emptyList(),
    activity: ComponentActivity? = null,
    initialSelectionState: Int = 0,
    restoreGroupId: String = ""
) {
    val selectionState = remember { mutableStateOf(initialSelectionState) }
    val app1 = remember { mutableStateOf<AppInfo?>(null) }
    val app2 = remember { mutableStateOf<AppInfo?>(null) }
    val groupName = remember { mutableStateOf("") }
    val groupNameNeedsManualEntry = remember { mutableStateOf(false) }
    val isAutoGeneratedName = remember { mutableStateOf(false) }  // Track if name was auto-generated
    val stepTransitionDurationMs = 450

    // Category filter state: 0 -> Installed, 1 -> System
    val selectedCategory = remember { mutableStateOf(0) }

    val existingNormalizedGroupNames = remember(activity) {
        activity
            ?.let { SharedPreferencesManager.getInstance(it).getAllAppGroups() }
            .orEmpty()
            .mapNotNull { group ->
                group.groupName.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            }
            .toSet()
    }

    val usedPackagesToGroupName = remember(activity) {
        val groups = activity?.let { SharedPreferencesManager.getInstance(it).getAllAppGroups() }.orEmpty()
        buildMap {
            groups.forEach { group ->
                val displayName = group.groupName.ifBlank {
                    listOf(group.app1Name, group.app2Name)
                        .filter { it.isNotBlank() }
                        .joinToString(" + ")
                        .ifBlank { "Unnamed Group" }
                }
                if (group.app1PackageName.isNotBlank()) putIfAbsent(group.app1PackageName, displayName)
                if (group.app2PackageName.isNotBlank()) putIfAbsent(group.app2PackageName, displayName)
            }
        }
    }

    // Partition apps into categories
    val installedApps = allApps.filter { !it.isSystem }
    val systemApps = allApps.filter { it.isSystem }

    val appsToShow = when (selectedCategory.value) {
        1 -> systemApps
        else -> installedApps
    }

    LaunchedEffect(restoreGroupId, allApps) {
        if (restoreGroupId.isBlank()) return@LaunchedEffect

        val pendingGroup = activity
            ?.let { SharedPreferencesManager.getInstance(it).getAppGroupForSetup(restoreGroupId) }
            ?: return@LaunchedEffect

        app1.value = allApps.firstOrNull { it.packageName == pendingGroup.app1PackageName }
            ?: AppInfo(
                packageName = pendingGroup.app1PackageName,
                appName = pendingGroup.app1Name
            )
        app2.value = allApps.firstOrNull { it.packageName == pendingGroup.app2PackageName }
            ?: AppInfo(
                packageName = pendingGroup.app2PackageName,
                appName = pendingGroup.app2Name
            )
        groupName.value = pendingGroup.groupName
        groupNameNeedsManualEntry.value = false
        isAutoGeneratedName.value = false
        selectionState.value = 2
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
    ) {
        AnimatedContent(
            targetState = selectionState.value,
            transitionSpec = {
                fadeIn(animationSpec = tween(stepTransitionDurationMs)) togetherWith
                    fadeOut(animationSpec = tween(stepTransitionDurationMs))
            },
            label = "app-selection-step-transition"
        ) { currentStep ->
            when (currentStep) {
                0 -> AppSelectionStep(
                    title = "SELECT APP 1",
                    apps = appsToShow,
                    onAppSelected = { selectedApp ->
                        app1.value = selectedApp
                        // If the previous group name was auto-generated, clear it so it gets regenerated with new app1
                        if (isAutoGeneratedName.value) {
                            groupName.value = ""
                            groupNameNeedsManualEntry.value = false
                        }
                        isAutoGeneratedName.value = false
                        selectionState.value = 1
                    },
                    onBack = null,
                    selectedCategory = selectedCategory.value,
                    onCategoryChange = { selectedCategory.value = it },
                    usedPackagesToGroupName = usedPackagesToGroupName
                )
                1 -> AppSelectionStep(
                    title = "SELECT APP 2",
                    apps = appsToShow.filter { it.packageName != app1.value?.packageName },
                    onAppSelected = { selectedApp ->
                        app2.value = selectedApp
                        // Regenerate the default name if it's auto-generated OR empty
                        // This ensures name updates when user changes app1 after selecting app2
                        if (isAutoGeneratedName.value || groupName.value.trim().isEmpty()) {
                            val app1Name = app1.value?.appName?.trim().orEmpty()
                            val app2Name = selectedApp.appName.trim()
                            val autoName = "$app1Name+$app2Name"
                            if (autoName.length <= MAX_GROUP_NAME_LENGTH) {
                                groupName.value = autoName
                                groupNameNeedsManualEntry.value = false
                                isAutoGeneratedName.value = true  // Mark as auto-generated
                            } else {
                                groupName.value = ""
                                groupNameNeedsManualEntry.value = true
                                isAutoGeneratedName.value = false
                            }
                        }
                        selectionState.value = 2
                    },
                    onBack = { selectionState.value = 0 },
                    selectedCategory = selectedCategory.value,
                    onCategoryChange = { selectedCategory.value = it },
                    usedPackagesToGroupName = usedPackagesToGroupName
                )
                2 -> GroupNameScreen(
                    app1 = app1.value,
                    app2 = app2.value,
                    groupName = groupName.value,
                    onGroupNameChange = { groupName.value = it },
                    existingNormalizedGroupNames = existingNormalizedGroupNames,
                    showDefaultNameTooLongHint = groupNameNeedsManualEntry.value,
                    onConfirm = {
                        val normalizedGroupName = groupName.value.trim()
                        val normalizedLookupKey = normalizedGroupName.lowercase(Locale.ROOT)
                        if (normalizedGroupName.isNotEmpty() && normalizedLookupKey !in existingNormalizedGroupNames) {
                            val appGroup = AppGroup(
                                id = System.currentTimeMillis().toString(),
                                groupName = normalizedGroupName,
                                app1PackageName = app1.value?.packageName ?: "",
                                app1Name = app1.value?.appName ?: "",
                                app2PackageName = app2.value?.packageName ?: "",
                                app2Name = app2.value?.appName ?: "",
                                app1UninstallProtectionEnabled = true,
                                app2UninstallProtectionEnabled = true
                            )

                            SharedPreferencesManager.getInstance(activity!!).savePendingAppGroup(appGroup)

                            val intent = Intent(activity, PasswordSetupActivity::class.java)
                            intent.putExtra("groupId", appGroup.id)
                            activity.startActivity(intent)
                            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            activity.finish()
                        }
                    },
                    onBack = { selectionState.value = 1 }
                )
            }
        }
    }
}

@Composable
fun CategoryChip(text: String, selected: Boolean = false, onClick: () -> Unit = {}, fontSize: Float = 12f) {
    val containerColor = remember(selected) {
        if (selected) Color(0xFF0f3460) else Color(0xFF0a2940)
    }

    val elevation = remember(selected) {
        if (selected) 4.dp else 1.dp
    }

    Card(
        modifier = Modifier
            .shadow(elevation = elevation, shape = RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Purple80,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize.sp
        )
    }
}

@Composable
fun AppSelectionStep(
    title: String,
    apps: List<AppInfo>,
    onAppSelected: (AppInfo) -> Unit,
    onBack: (() -> Unit)? = null,
    selectedCategory: Int = 0,
    onCategoryChange: (Int) -> Unit = {},
    usedPackagesToGroupName: Map<String, String> = emptyMap()
) {
    var searchQuery by remember(title, selectedCategory) { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header uses fixed slots so both app selection screens keep identical layout.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Purple80
                        )
                    }
                }
            }

            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Purple80,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )

            Box(modifier = Modifier.size(48.dp))
        }

        // Category chips - Only Installed and System
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CategoryChip(
                text = "INSTALLED APPS",
                selected = selectedCategory == 0,
                onClick = { onCategoryChange(0) },
                fontSize = 12f
            )
            CategoryChip(
                text = "SYSTEM APPS",
                selected = selectedCategory == 1,
                onClick = { onCategoryChange(1) },
                fontSize = 12f
            )
        }

        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            placeholder = {
                Text(
                    text = "Search apps…",
                    color = Color(0xFF666666)
                )
            },
            trailingIcon = {
                Image(
                    painter = painterResource(R.drawable.search),
                    contentDescription = "Search",
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 4.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF0f3460),
                unfocusedContainerColor = Color(0xFF0a2940),
                focusedTextColor = Color(0xFFE0E0E0),
                unfocusedTextColor = Color(0xFFB0B0B0),
                cursorColor = Purple80,
                focusedIndicatorColor = Purple80,
                unfocusedIndicatorColor = Color(0xFF1a3a52)
            )
        )

        // Apps List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (filteredApps.isEmpty()) {
                item(key = "no_apps_found") {
                    Text(
                        text = if (searchQuery.isBlank()) "No apps available" else "No apps match your search",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        color = Color(0xFFB0B0B0),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            items(
                items = filteredApps,
                key = { app -> app.packageName }
            ) { app ->
                val usedByGroup = usedPackagesToGroupName[app.packageName]
                AppListCard(
                    appName = app.appName,
                    packageName = app.packageName,
                    icon = app.icon,
                    onClick = { onAppSelected(app) },
                    enabled = usedByGroup == null,
                    usageMessage = usedByGroup?.let { "App already used in $it" }
                )
            }
        }
    }
}

@Composable
fun AppListCard(
    appName: String,
    packageName: String,
    icon: Drawable? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    usageMessage: String? = null
) {
    // Cache bitmap conversion to prevent recalculation on every scroll
    val cachedBitmap = remember(icon) {
        icon?.toBitmap()?.asImageBitmap()
    }

    // Cache container color to avoid recalculation
    val containerColor = remember(enabled) {
        if (enabled) Color(0xFF0f3460) else Color(0xFF020D1A)
    }

    val alpha = remember(enabled) {
        if (enabled) 1f else 0.45f
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .alpha(alpha)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (cachedBitmap != null) {
                        Image(
                            bitmap = cachedBitmap,
                            contentDescription = appName,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )
                        SpacerWidth(12.dp)
                    }

                    Column(modifier = Modifier) {
                        Text(
                            text = appName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Purple80
                        )
                        Text(
                            text = packageName,
                            fontSize = 12.sp,
                            color = Color(0xFFB0B0B0)
                        )
                    }
                }
            }

            if (!enabled && !usageMessage.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = usageMessage,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SpacerWidth(width: Dp) {
    Spacer(modifier = Modifier.width(width))
}

@Composable
fun GroupNameScreen(
    app1: AppInfo?,
    app2: AppInfo?,
    groupName: String,
    onGroupNameChange: (String) -> Unit,
    existingNormalizedGroupNames: Set<String> = emptySet(),
    showDefaultNameTooLongHint: Boolean = false,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val trimmedGroupName = groupName.trim()
    val normalizedLookupKey = trimmedGroupName.lowercase(Locale.ROOT)
    val isBlankName = trimmedGroupName.isEmpty()
    val isDuplicateName = normalizedLookupKey in existingNormalizedGroupNames
    val isGroupNameValid = !isBlankName && !isDuplicateName

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Purple80
                )
            }
            Text(
                text = "Name Your Group",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Purple80,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = {}) {
                // Placeholder for alignment
            }
        }

        // Selected Apps Summary
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0f3460)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Selected Apps",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Purple80,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "App 1: ",
                        fontSize = 14.sp,
                        color = Color(0xFFB0B0B0),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = app1?.appName ?: "Not selected",
                        fontSize = 14.sp,
                        color = Purple80,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "App 2: ",
                        fontSize = 14.sp,
                        color = Color(0xFFB0B0B0),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = app2?.appName ?: "Not selected",
                        fontSize = 14.sp,
                        color = Purple80,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Group Name Input
        Text(
            text = "Enter Group Name",
            fontSize = 14.sp,
            color = Color(0xFFB0B0B0),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        TextField(
            value = groupName,
            onValueChange = { onGroupNameChange(it.take(MAX_GROUP_NAME_LENGTH)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            placeholder = {
                Text(
                    text = "Type group name",
                    color = Color(0xFF666666)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF0f3460),
                unfocusedContainerColor = Color(0xFF0a2940),
                focusedTextColor = Color(0xFFE0E0E0),
                unfocusedTextColor = Color(0xFFB0B0B0),
                cursorColor = Purple80,
                focusedIndicatorColor = Purple80,
                unfocusedIndicatorColor = Color(0xFF1a3a52)
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        Text(
            text = "${groupName.length}/$MAX_GROUP_NAME_LENGTH",
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            textAlign = TextAlign.End
        )

        if (isBlankName) {
            Text(
                text = if (showDefaultNameTooLongHint) {
                    "Default name is too long. Please enter a custom name (max 30 characters)."
                } else {
                    "Group name cannot be blank"
                },
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        } else if (isDuplicateName) {
            Text(
                text = "Group name already exists",
                color = Color(0xFFFF6B6B),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Confirm Button
        Button(
            onClick = onConfirm,
            enabled = isGroupNameValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50),
                disabledContainerColor = Color(0xFF2E5E36)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Confirm",
                    tint = Color.White,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "Proceed to Authentication Setup",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        Box(modifier = Modifier.height(16.dp))
    }
}
