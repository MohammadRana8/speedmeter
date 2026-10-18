package com.example.speedmeter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.speedmeter.ui.SettingsScreen
import com.example.speedmeter.ui.UsageScreen
import com.example.speedmeter.usage.UsageWorker

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        UsageWorker.schedule(this)

        setContent {
            MaterialTheme {
                var tab by remember { mutableIntStateOf(0) }
                Scaffold(bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("📶") }, label = { Text("Usage") })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("⚙️") }, label = { Text("Plan") })
                    }
                }) { pad ->
                    if (tab == 0) UsageScreen(Modifier.padding(pad)) else SettingsScreen(Modifier.padding(pad))
                }
            }
        }
    }
}
