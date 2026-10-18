package com.example.speedmeter.ui

import android.net.TrafficStats
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.speedmeter.core.PlanError
import com.example.speedmeter.core.cycleEnd
import com.example.speedmeter.core.cycleStart
import com.example.speedmeter.core.daysLeftInCycle
import com.example.speedmeter.core.projectedCapDate
import com.example.speedmeter.core.validatePlan
import com.example.speedmeter.data.AppDb
import com.example.speedmeter.data.CycleTotals
import com.example.speedmeter.data.DataPlanEntity
import com.example.speedmeter.usage.UsageSampler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val GB = 1_000_000_000.0

fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000L -> "%.2f GB".format(b / GB)
    b >= 1_000_000L -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000L -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}

@Composable
fun UsageScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val plan by db.planDao().observePlan().collectAsState(initial = null)
    val today = LocalDate.now()
    val todayRow by db.usageDao().observeDay(today.toString()).collectAsState(initial = null)
    val recent by db.usageDao().observeRecent(7).collectAsState(initial = emptyList())
    var down by remember { mutableLongStateOf(0L) }
    var up by remember { mutableLongStateOf(0L) }
    var totals by remember { mutableStateOf(CycleTotals(0L, 0L)) }
    val start = cycleStart(today, plan?.cycleDay ?: 1)

    LaunchedEffect(Unit) {
        UsageSampler.sample(ctx)
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        var tick = 0
        while (true) {
            delay(1000)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (rx >= 0 && lastRx >= 0) down = (rx - lastRx).coerceAtLeast(0L)
            if (tx >= 0 && lastTx >= 0) up = (tx - lastTx).coerceAtLeast(0L)
            lastRx = rx; lastTx = tx
            if (++tick % 15 == 0) UsageSampler.sample(ctx)
        }
    }
    LaunchedEffect(todayRow, plan, start) {
        totals = db.usageDao().cycleTotals(start.toString(), cycleEnd(start).toString())
    }

    val used = if (plan?.counts == "mobile_wifi") totals.mobile + totals.wifi else totals.mobile
    val cap = plan?.capBytes

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live speed", style = MaterialTheme.typography.titleMedium)
        Text("↓ ${fmtBytes(down)}/s    ↑ ${fmtBytes(up)}/s", style = MaterialTheme.typography.headlineSmall)
        Text("Today: mobile ${fmtBytes((todayRow?.mobileRx ?: 0) + (todayRow?.mobileTx ?: 0))}, " +
            "Wi-Fi ${fmtBytes((todayRow?.wifiRx ?: 0) + (todayRow?.wifiTx ?: 0))}")
        Text("This cycle", style = MaterialTheme.typography.titleMedium)
        if (cap != null) {
            LinearProgressIndicator(progress = { (used.toFloat() / cap).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${fmtBytes(used)} of ${fmtBytes(cap)} used · ${daysLeftInCycle(today, start)} days left")
            val hit = projectedCapDate(used, cap, today, start)
            if (hit != null) Text("At this pace you reach the cap on $hit")
        } else {
            Text("${fmtBytes(used)} used · ${daysLeftInCycle(today, start)} days left (no cap set)")
        }
        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
        recent.forEach {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(it.day)
                Text("${fmtBytes(it.mobileRx + it.mobileTx)} mobile · ${fmtBytes(it.wifiRx + it.wifiTx)} Wi-Fi")
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val plan by db.planDao().observePlan().collectAsState(initial = null)
    var dayText by remember(plan) { mutableStateOf((plan?.cycleDay ?: 1).toString()) }
    var capText by remember(plan) {
        mutableStateOf(plan?.capBytes?.let { (it / GB).let { g -> if (g % 1.0 == 0.0) g.toLong().toString() else g.toString() } } ?: "")
    }
    var withWifi by remember(plan) { mutableStateOf(plan?.counts == "mobile_wifi") }
    var message by remember { mutableStateOf("") }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Data plan", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(dayText, { dayText = it }, label = { Text("Cycle start day (1-28)") }, singleLine = true)
        OutlinedTextField(capText, { capText = it }, label = { Text("Cap in GB (empty = no cap)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(withWifi, { withWifi = it })
            Text("Count Wi-Fi towards the cap")
        }
        Button(onClick = {
            val day = dayText.toIntOrNull()
            val gb = if (capText.isBlank()) null else capText.replace(',', '.').toDoubleOrNull()
            val capBytes = gb?.let { (it * GB).toLong() }
            val err = if (day == null || (capText.isNotBlank() && gb == null)) PlanError.CycleDay
            else validatePlan(day, capBytes)
            if (err != null || day == null) {
                message = if (err == PlanError.Cap) "Cap must be greater than 0" else "Check the cycle day and cap"
            } else {
                scope.launch {
                    db.planDao().savePlan(
                        DataPlanEntity(1, day, capBytes, if (withWifi) "mobile_wifi" else "mobile", System.currentTimeMillis())
                    )
                    message = "Saved"
                }
            }
        }) { Text("Save") }
        if (message.isNotEmpty()) Text(message)
    }
}
