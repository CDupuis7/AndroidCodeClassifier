package com.example.androidcodeclassifier

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.androidcodeclassifier.ui.theme.AndroidCodeClassifierTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

class ClassicModelsActivity : ComponentActivity() {

    // UI state
    private var isProcessing by mutableStateOf(false)
    private var predictionText by mutableStateOf("No prediction yet")
    private var bestSimText by mutableStateOf("--")
    private var class0SimText by mutableStateOf("--")
    private var class1SimText by mutableStateOf("--")
    private var predictedEmoji by mutableStateOf("")
    private var predictedClassName by mutableStateOf("")
    private var avgMsText by mutableStateOf("--")
    private var totalMsText by mutableStateOf("--")
    private var batteryText by mutableStateOf("--")
    private var peakHeapText by mutableStateOf("--")
    private var avgPssText by mutableStateOf("--")
    private var peakPssText by mutableStateOf("--")

    // NEW: power + thermal + energy
    private var avgCurrentText by mutableStateOf("--")
    private var avgCpuTempText by mutableStateOf("--")
    private var energyPerInfText by mutableStateOf("--")

    private var runsText by mutableStateOf("500")
    private val modelOptions = listOf("RF", "SVM")
    private var selectedModel by mutableStateOf(modelOptions.first())

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) autoProcessApk(uri)
    }

    private fun openApkPicker() {
        if (!isProcessing) {
            pickApk.launch(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/zip",
                    "*/*"
                )
            )
        }
    }

    private fun autoProcessApk(uri: Uri) {
        val rounds = runsText.toIntOrNull()?.coerceAtLeast(1) ?: 500

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isProcessing = true
                predictionText = "Processing $rounds runs with $selectedModel..."
                bestSimText = "--"; class0SimText = "--"; class1SimText = "--"
                avgMsText = "--"; totalMsText = "--"; batteryText = "--"
                peakHeapText = "--"; avgPssText = "--"; peakPssText = "--"
                avgCurrentText = "--"; avgCpuTempText = "--"; energyPerInfText = "--"
                predictedEmoji = ""; predictedClassName = ""
            }

            val batteryStart = getBatteryPercent()
            val batteryVoltageMv = getBatteryVoltageMillivolts()

            // Feature extraction
            val feats = try {
                ClassicFeatureExtractor.extractOpcodeTokens(
                    context = this@ClassicModelsActivity,
                    apkUri = uri,
                    maxLines = 10_000
                )
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    Toast.makeText(
                        this@ClassicModelsActivity,
                        "Feature extraction failed: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            // Benchmark loop
            var totalNs = 0L
            var peakHeapMb = 0.0
            var peakPssMb = 0.0
            var sumPssMb = 0.0
            var lastLabel = 0
            var p0 = Float.NaN
            var p1 = Float.NaN

            // power / thermal accumulators
            var sumCurrentMa = 0.0
            var currentSamples = 0
            var sumCpuTempC = 0.0
            var cpuTempSamples = 0

            repeat(rounds) {
                val ns = measureNanoTime {
                    when (selectedModel) {
                        "RF" -> {
                            val rfModel = RfJsonForest
                                .loadFromAssets(this@ClassicModelsActivity, "rf_model 1.json")

                            val x = rfModel.featurize(
                                tokens = feats.tokens,
                                methodCount = feats.methodCount,
                                opcodeCount = feats.opcodeCount
                            )
                            val (lbl, probs) = rfModel.predict(x)
                            lastLabel = lbl
                            p0 = probs.getOrNull(0) ?: Float.NaN
                            p1 = probs.getOrNull(1) ?: Float.NaN
                        }

                        "SVM" -> {
                            val svmModel = SvmRbf
                                .loadFromAssets(this@ClassicModelsActivity, "rbf_svm 1.json")

                            val x = svmModel.featurizeDense(
                                tokens = feats.tokens,
                                methodCount = feats.methodCount,
                                opcodeCount = feats.opcodeCount
                            )
                            val (lbl, probs) = svmModel.predict(x)
                            lastLabel = lbl
                            p0 = probs.getOrNull(0) ?: Float.NaN
                            p1 = probs.getOrNull(1) ?: Float.NaN
                        }

                        else -> error("Model not implemented: $selectedModel")
                    }
                }
                totalNs += ns

                // memory
                val heapMb = currentHeapUsedMb()
                if (heapMb > peakHeapMb) peakHeapMb = heapMb

                val pss = readProcessPss()
                sumPssMb += pss.totalPssMb
                if (pss.totalPssMb > peakPssMb) peakPssMb = pss.totalPssMb

                // current (mA)
                getBatteryCurrentMicroAmps()?.let { microA ->
                    val ma = abs(microA) / 1000.0
                    sumCurrentMa += ma
                    currentSamples++
                }

                // CPU temp (°C) from /sys/class/thermal
                getCpuTemperatureC()?.let { tempC ->
                    sumCpuTempC += tempC
                    cpuTempSamples++
                }
            }

            val batteryEnd = getBatteryPercent()
            val avgNs = totalNs / rounds.toDouble()
            val avgMs = round(avgNs / 1_000_000.0)
            val totalMs = round(totalNs / 1_000_000.0)
            val niceName = if (lastLabel == 1) "Malware" else "Benign"
            val emoji = if (lastLabel == 1) "😈" else "🙂"
            val bestSim = if (lastLabel == 1) p1 else p0

            val batteryStr = when {
                batteryStart == null || batteryEnd == null -> "n/a"
                else -> {
                    val delta = batteryEnd - batteryStart
                    "$batteryStart% → $batteryEnd% (Δ ${if (delta >= 0) "+$delta" else "$delta"}%)"
                }
            }

            val avgPssMb = sumPssMb / rounds
            val avgPssStr = String.format("%.1f MB (avg PSS)", avgPssMb)
            val peakPssStr = String.format("%.1f MB (peak PSS)", peakPssMb)

            // avg current + temp
            var avgMaForEnergy: Double? = null
            val avgCurrentStr = if (currentSamples > 0) {
                val avgMa = sumCurrentMa / currentSamples
                avgMaForEnergy = avgMa
                String.format("%.1f mA (avg device current)", avgMa)
            } else {
                "n/a"
            }

            val avgCpuTempStr = if (cpuTempSamples > 0) {
                val avgC = sumCpuTempC / cpuTempSamples
                String.format("%.1f °C (avg CPU)", avgC)
            } else {
                "n/a"
            }

            // energy per inference (mJ)
            val energyPerInfStr = if (avgMaForEnergy != null && batteryVoltageMv != null) {
                val avgCurrentA = avgMaForEnergy / 1000.0
                val totalSeconds = totalNs / 1_000_000_000.0
                val voltageV = batteryVoltageMv / 1000.0
                val totalEnergyJ = voltageV * avgCurrentA * totalSeconds
                val perInfJ = totalEnergyJ / rounds
                val perInfmJ = perInfJ * 1000.0
                String.format("%.3f mJ / inference (approx)", perInfmJ)
            } else {
                "n/a"
            }

            withContext(Dispatchers.Main) {
                predictedEmoji = emoji
                predictedClassName = niceName
                predictionText = "Predicted: $niceName ($selectedModel)"

                if (selectedModel == "RF") {
                    bestSimText = if (bestSim.isNaN()) "--" else "%.4f".format(bestSim)
                    class0SimText = if (p0.isNaN()) "--" else "%.4f".format(p0)
                    class1SimText = if (p1.isNaN()) "--" else "%.4f".format(p1)
                } else {
                    bestSimText = "--"
                    class0SimText = "--"
                    class1SimText = "--"
                }

                avgMsText = "${avgMs.toLong()} ms (avg over $rounds, incl. model load)"
                totalMsText = "${totalMs.toLong()} ms (total, incl. model load)"
                batteryText = batteryStr
                peakHeapText = "${round(peakHeapMb * 10) / 10.0} MB (peak heap)"
                avgPssText = avgPssStr
                peakPssText = peakPssStr

                avgCurrentText = avgCurrentStr
                avgCpuTempText = avgCpuTempStr
                energyPerInfText = energyPerInfStr

                isProcessing = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logThermalZones()
        enableEdgeToEdge()
        setContent {
            AndroidCodeClassifierTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ClassicModelsScreen(
                        modifier = Modifier.padding(innerPadding),
                        isProcessing = isProcessing,
                        runsText = runsText,
                        onRunsChange = { txt ->
                            runsText = txt.filter { it.isDigit() }.ifEmpty { "1" }
                        },
                        modelOptions = modelOptions,
                        selectedModel = selectedModel,
                        onSelectModel = { m -> if (!isProcessing) selectedModel = m },
                        emoji = predictedEmoji,
                        emojiLabel = predictedClassName,
                        prediction = predictionText,
                        bestSim = bestSimText,
                        class0Sim = class0SimText,
                        class1Sim = class1SimText,
                        avgMs = avgMsText,
                        totalMs = totalMsText,
                        battery = batteryText,
                        avgPss = avgPssText,
                        peakPss = peakPssText,
                        peakHeap = peakHeapText,
                        avgCurrent = avgCurrentText,
                        avgCpuTemp = avgCpuTempText,
                        energyPerInf = energyPerInfText,
                        onPickApk = { openApkPicker() },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    // --------- Benchmark utils (inside the class!) ----------

    private fun logThermalZones() {
        val dir = File("/sys/class/thermal")
        if (!dir.exists() || !dir.isDirectory) {
            Log.d("THERMAL", "No /sys/class/thermal directory found")
            return
        }

        dir.listFiles()?.forEach { zone ->
            val typeFile = File(zone, "type")
            val tempFile = File(zone, "temp")
            if (typeFile.exists() && tempFile.exists()) {
                try {
                    val label = typeFile.readText().trim()
                    val raw = tempFile.readText().trim()
                    Log.d("THERMAL", "Zone ${zone.name}: type='$label', tempRaw='$raw'")
                } catch (e: Exception) {
                    Log.w("THERMAL", "Error reading ${zone.name}: ${e.message}")
                }
            }
        }
    }


    private fun getBatteryPercent(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (cap in 0..100) return cap
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = registerReceiver(null, ifilter) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100f).roundToInt() else null
    }

    private fun getBatteryVoltageMillivolts(): Int? {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = registerReceiver(null, ifilter) ?: return null
        val mv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        return if (mv > 0) mv else null
    }

    private fun getBatteryCurrentMicroAmps(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (cur != Int.MIN_VALUE) cur else null
    }

    // CPU temp via /sys/class/thermal
    private fun readCpuThermalZoneTemp(): Double? {
        val dir = File("/sys/class/thermal")
        if (!dir.exists() || !dir.isDirectory) return null

        val cpuTags = listOf(
            "cpu", "soc", "ap", "a53", "a57", "a73", "a75", "a76", "a77", "a78",
            "big", "little"
        )

        dir.listFiles()?.forEach { zone ->
            val typeFile = File(zone, "type")
            val tempFile = File(zone, "temp")

            if (typeFile.exists() && tempFile.exists()) {
                try {
                    val label = typeFile.readText().trim().lowercase()
                    if (cpuTags.any { tag -> label.contains(tag) }) {
                        val raw = tempFile.readText().trim()
                        val rawVal = raw.toFloat()
                        val tempC = if (rawVal > 200) rawVal / 1000f else rawVal
                        return tempC.toDouble()
                    }
                } catch (e: Exception) {
                    Log.w("ClassicModelsActivity", "Error reading thermal zone ${zone.name}: ${e.message}")
                }
            }
        }
        return null
    }

    private fun getCpuTemperatureC(): Double? = readCpuThermalZoneTemp()

    private fun currentHeapUsedMb(): Double {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return used / 1048576.0
    }

    private data class ProcMemSnapshot(
        val totalPssMb: Double,
        val javaHeapMb: Double?,
        val nativeHeapMb: Double?
    )

    private fun readProcessPss(): ProcMemSnapshot {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0]
        val totalPssMb = mi.totalPss / 1024.0
        return if (Build.VERSION.SDK_INT >= 23) {
            val stats = mi.memoryStats
            val javaKb = stats["summary.java-heap"]?.toIntOrNull() ?: 0
            val nativeKb = stats["summary.native-heap"]?.toIntOrNull() ?: 0
            ProcMemSnapshot(
                totalPssMb = totalPssMb,
                javaHeapMb = javaKb / 1024.0,
                nativeHeapMb = nativeKb / 1024.0
            )
        } else {
            ProcMemSnapshot(
                totalPssMb = totalPssMb,
                javaHeapMb = mi.dalvikPss / 1024.0,
                nativeHeapMb = mi.nativePss / 1024.0
            )
        }
    }
}

// ---------- Composable UI (top-level) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassicModelsScreen(
    modifier: Modifier = Modifier,
    isProcessing: Boolean,
    runsText: String,
    onRunsChange: (String) -> Unit,
    modelOptions: List<String>,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    emoji: String,
    emojiLabel: String,
    prediction: String,
    bestSim: String,
    class0Sim: String,
    class1Sim: String,
    avgMs: String,
    totalMs: String,
    battery: String,
    avgPss: String,
    peakPss: String,
    peakHeap: String,
    avgCurrent: String,
    avgCpuTemp: String,
    energyPerInf: String,
    onPickApk: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Classical Model Benchmark", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        if (emoji.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(text = "$emoji  $emojiLabel", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(0.95f)
            ) {
                OutlinedTextField(
                    value = "Model = $selectedModel",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    modelOptions.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = { expanded = false; onSelectModel(m) },
                            enabled = !isProcessing
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = runsText,
                onValueChange = onRunsChange,
                label = { Text("Runs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.95f)
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onPickApk,
                enabled = !isProcessing,
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth(0.95f)
            ) {
                Text(if (isProcessing) "Processing..." else "Pick APK")
            }

            Spacer(Modifier.height(12.dp))
            Text(text = prediction, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            if (selectedModel == "RF") {
                Text(text = "Top probability (RF): $bestSim", fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(text = "Benign probability: $class0Sim", fontSize = 14.sp)
                Text(text = "Malware probability: $class1Sim", fontSize = 14.sp)
            }

            Spacer(Modifier.height(12.dp))
            Text(text = "Avg inference time: $avgMs", fontSize = 14.sp)
            Text(text = "Total time: $totalMs", fontSize = 14.sp)
            Text(text = "Battery: $battery", fontSize = 14.sp)
            Text(text = "Avg PSS: $avgPss", fontSize = 14.sp)
            Text(text = "Peak PSS: $peakPss", fontSize = 14.sp)
            Text(text = "Peak heap used: $peakHeap", fontSize = 14.sp)

            Spacer(Modifier.height(8.dp))
            Text(text = "Avg current: $avgCurrent", fontSize = 14.sp)
            Text(text = "CPU temp: $avgCpuTemp", fontSize = 14.sp)
            Text(text = "Energy per inference: $energyPerInf", fontSize = 14.sp)

            if (isProcessing) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Button(
                onClick = onBack,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back") }
        }
    }
}
