package com.example.dale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dale.ui.theme.DALETheme
import com.example.dale.utils.SharedPreferencesManager

class UsageLogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val groupId = intent.getStringExtra("GROUP_ID") ?: ""
        val groupName = intent.getStringExtra("GROUP_NAME") ?: ""

        setContent {
            DALETheme {
                UsageLogsScreen(
                    groupId = groupId,
                    groupName = groupName,
                    activity = this
                )
            }
        }
    }
}

@Composable
fun UsageLogsScreen(
    groupId: String,
    groupName: String,
    activity: ComponentActivity
) {
    val sharedPrefs = SharedPreferencesManager.getInstance(activity)
    
    val appGroup = remember(groupId) { sharedPrefs.getAppGroup(groupId) }
    val usageLogs = remember(groupId) { sharedPrefs.getUsageLogs(groupId) }
    val activityLogs = remember(groupId) { sharedPrefs.getActivityLogs(groupId) }

    val app1Package = appGroup?.app1PackageName ?: ""
    val app1Name = appGroup?.app1Name ?: "App 1"
    
    val app2Package = appGroup?.app2PackageName ?: ""
    val app2Name = appGroup?.app2Name ?: "App 2"
    
    val app1Unlocks = activityLogs.count { it.packageName == app1Package && it.event == "OPENED" }
    val app2Unlocks = activityLogs.count { it.packageName == app2Package && it.event == "OPENED" }
    val totalGroupUnlocks = app1Unlocks + app2Unlocks
    
    val formatMs: (Long) -> String = { ms ->
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", java.util.Locale.getDefault())

    var computedApp1Ms = 0L
    var computedApp2Ms = 0L

    var lastOpenApp1: Long? = null
    var lastOpenApp2: Long? = null

    // activityLogs are ordered newest to oldest, so reverse to process chronologically
    activityLogs.reversed().forEach { log ->
        val time = try { dateFormat.parse(log.timestamp)?.time } catch (e: Exception) { null }
        if (time != null) {
            if (log.packageName == app1Package) {
                if (log.event == "OPENED") {
                    lastOpenApp1 = time
                } else if (log.event == "CLOSED" && lastOpenApp1 != null) {
                    val duration = time - lastOpenApp1!!
                    if (duration > 0) computedApp1Ms += duration
                    lastOpenApp1 = null
                }
            } else if (log.packageName == app2Package) {
                if (log.event == "OPENED") {
                    lastOpenApp2 = time
                } else if (log.event == "CLOSED" && lastOpenApp2 != null) {
                    val duration = time - lastOpenApp2!!
                    if (duration > 0) computedApp2Ms += duration
                    lastOpenApp2 = null
                }
            }
        }
    }

    val app1TimeFormatted = formatMs(computedApp1Ms)
    val app2TimeFormatted = formatMs(computedApp2Ms)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Color(0xFF1a1a2e), Color(0xFF16213e))
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
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "Usage Logs",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = groupName,
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Rectangle: Total unlock count for the group
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0f3460))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Total Times Unlocked",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$totalGroupUnlocks",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFBB86FC)
                        )
                    }
                }

                // Middle Row: Unlocks Count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // App 1 Unlocks
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0f3460))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$app1Name Unlocked",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.LightGray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$app1Unlocks",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // App 2 Unlocks
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0f3460))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$app2Name Unlocked",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.LightGray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$app2Unlocks",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Bottom Row: Time Spent
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // App 1 Time Spent
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0f3460))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Time Spent in $app1Name",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.LightGray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = app1TimeFormatted,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBB86FC)
                            )
                        }
                    }

                    // App 2 Time Spent
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0f3460))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Time Spent in $app2Name",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.LightGray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = app2TimeFormatted,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFBB86FC)
                            )
                        }
                    }
                }
            }
        }
    }
}

