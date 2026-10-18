package com.example.speedmeter.usage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.speedmeter.R

object Notifier {
    private const val CHANNEL = "cap_alerts"

    fun notifyCap(ctx: Context, threshold: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Data cap alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val text = if (threshold >= 100) "You have reached your data cap." else "You have used $threshold% of your data cap."
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Data usage alert")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(1000 + threshold, n)
    }
}
