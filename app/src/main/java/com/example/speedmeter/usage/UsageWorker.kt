package com.example.speedmeter.usage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UsageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        UsageSampler.sample(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "usage-sample",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UsageWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
