package com.example.speedmeter.usage

import android.content.Context
import android.net.TrafficStats
import android.provider.Settings
import com.example.speedmeter.core.cycleEnd
import com.example.speedmeter.core.cycleStart
import com.example.speedmeter.core.thresholdsReached
import com.example.speedmeter.data.AlertLogEntity
import com.example.speedmeter.data.AppDb
import com.example.speedmeter.data.CounterCheckpointEntity
import java.time.ZonedDateTime

/** Reads the system traffic counters, turns them into per-day deltas and fires cap alerts. */
object UsageSampler {
    suspend fun sample(ctx: Context) {
        val db = AppDb.get(ctx)
        val usage = db.usageDao()
        val now = System.currentTimeMillis()
        val nowZ = ZonedDateTime.now()
        val day = nowZ.toLocalDate().toString()
        val offset = nowZ.offset.totalSeconds / 60
        val boot = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()

        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()
        val mobRx = TrafficStats.getMobileRxBytes()
        val mobTx = TrafficStats.getMobileTxBytes()
        if (minOf(totalRx, totalTx, mobRx, mobTx) < 0L) return // counters unsupported

        suspend fun delta(name: String, rx: Long, tx: Long): Pair<Long, Long> {
            val cp = usage.checkpoint(name)
            val d = when {
                cp == null -> 0L to 0L
                cp.bootId != boot || rx < cp.lastRx || tx < cp.lastTx -> rx to tx // counters reset
                else -> (rx - cp.lastRx) to (tx - cp.lastTx)
            }
            usage.saveCheckpoint(CounterCheckpointEntity(name, rx, tx, boot, now))
            return d
        }

        val (dTotRx, dTotTx) = delta("total", totalRx, totalTx)
        val (dMobRx, dMobTx) = delta("mobile", mobRx, mobTx)
        usage.ensureDay(day, offset, now)
        usage.addToDay(
            day, dMobRx, dMobTx,
            (dTotRx - dMobRx).coerceAtLeast(0L), (dTotTx - dMobTx).coerceAtLeast(0L), now,
        )

        val plan = db.planDao().getPlan()
        val cap = plan?.capBytes
        if (plan != null && cap != null) {
            val start = cycleStart(nowZ.toLocalDate(), plan.cycleDay)
            val t = usage.cycleTotals(start.toString(), cycleEnd(start).toString())
            val used = if (plan.counts == "mobile_wifi") t.mobile + t.wifi else t.mobile
            for (th in thresholdsReached(used, cap)) {
                if (db.planDao().markFired(AlertLogEntity(start.toString(), th, now)) != -1L) {
                    Notifier.notifyCap(ctx, th)
                }
            }
        }
        usage.pruneBefore(nowZ.toLocalDate().minusDays(120).toString())
        db.planDao().pruneAlerts(nowZ.toLocalDate().minusMonths(4).toString())
    }
}
