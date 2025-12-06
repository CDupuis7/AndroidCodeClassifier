package com.example.androidcodeclassifier

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle

//import android.os.ThermalManager
import android.widget.Toast
import android.util.Log
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
import androidx.core.content.FileProvider
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

class MainActivity : ComponentActivity() {
    //Private Variables
    private var dumpFile by mutableStateOf<File?>(null)
    private var docHv: FloatArray? = null
    private var isLoadingVectors by mutableStateOf(false)
    private var class0: FloatArray? = null
    private var class1: FloatArray? = null
    private var vectorsReady by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var predictionText by mutableStateOf("No prediction yet")
    private var bestSimText by mutableStateOf("--")
    private var class1SimText by mutableStateOf("--")
    private var class2SimText by mutableStateOf("--")
    private var predictedEmoji by mutableStateOf("")
    private var predictedClassName by mutableStateOf("")
    private var avgMsText by mutableStateOf("--")
    private var totalMsText by mutableStateOf("--")
    private var batteryText by mutableStateOf("--")
    private var peakHeapText by mutableStateOf("--")
    private var avgPssText by mutableStateOf("--")
    private var peakPssText by mutableStateOf("--")

    // Battery current + CPU temp display strings
    private var avgCurrentText by mutableStateOf("--")
    private var avgCpuTempText by mutableStateOf("--")

    // NEW: approximate energy per inference
    private var energyPerInfText by mutableStateOf("--")

    private var runsText by mutableStateOf("500")
    private val dOptions = listOf(512, 1024, 2048)
    private var selectedDim by mutableStateOf(2048)

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            autoProcessApk(uri)
        }
    }

    private fun autoProcessApk(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val c0 = class0; val c1 = class1
            if (c0 == null || c1 == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Class vectors not loaded for D=$selectedDim.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val rounds = runsText.toIntOrNull()?.coerceAtLeast(1) ?: 500

            withContext(Dispatchers.Main) {
                isProcessing = true
                predictionText = "Processing $rounds runs with D=$selectedDim..."
                bestSimText = "--"; class1SimText = "--"; class2SimText = "--"
                avgMsText = "--"; totalMsText = "--"; batteryText = "--"; peakHeapText = "--"
                avgPssText = "--"; peakPssText = "--"
                avgCurrentText = "--"; avgCpuTempText = "--"
                energyPerInfText = "--"
                predictedEmoji = ""; predictedClassName = ""
            }

            val batteryStart = getBatteryPercent()
            val batteryVoltageMv = getBatteryVoltageMillivolts() // for energy calc

            //Dump/gather opcodes from APK Dex files
            val outFile = ApkDexAnalyzer.dumpFirstOpcodesToFile(
                context = this@MainActivity,
                apkUri = uri,
                maxLines = 10_000,
                printToLogcat = false
            )
            if (outFile == null) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    Toast.makeText(this@MainActivity, "No DEX entries found or analysis failed.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            //Collect needed opcodes
            val linesList = ArrayList<String>(16_384)
            val neededOpcodes = HashSet<String>(4096)
            outFile.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val t = line.trim()
                    if (t.isEmpty()) return@forEach
                    val parts = t.split(' ', limit = 2)
                    if (parts.size >= 2) {
                        linesList.add(t)
                        neededOpcodes.add(parts[1])
                    }
                }
            }

            //Dictionary Load
            val dict = try {
                HvOpcodeSelectiveLoader.loadSelectiveOpcodes(
                    context = this@MainActivity,
                    neededOpcodes = neededOpcodes,
                    expectedDim = selectedDim,
                    assetFileName = "hv_lookup 1.json"
                )
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    Toast.makeText(
                        this@MainActivity,
                        "Failed loading opcode dict for D=$selectedDim: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            //Testing loop for benchmark
            var bestS0 = 0f
            var bestS1 = 0f
            var lastHv: FloatArray? = null
            var peakHeapMb = 0.0
            var totalNs = 0L
            var peakPssMb = 0.0
            var sumPssMb = 0.0

            // Accumulators for current & CPU temp
            var sumCurrentMa = 0.0
            var currentSamples = 0
            var sumCpuTempC = 0.0
            var cpuTempSamples = 0

            val encoder = OpcodeMethodEncoder(
                D = selectedDim,
                posFraction = 0.5,
                baseSeed = 123456789L,
                dict = dict
            )

            repeat(rounds) {
                val ns = measureNanoTime {
                    encoder.reset()
                    for (line in linesList) encoder.addLine(line)
                    val hv = encoder.finalizeVector()

                    val s0 = HdcCore.cosine(hv, c0)
                    val s1 = HdcCore.cosine(hv, c1)

                    lastHv = hv
                    bestS0 = s0
                    bestS1 = s1
                }
                totalNs += ns

                //Memory Use:
                val heapMb = currentHeapUsedMb()
                if (heapMb > peakHeapMb) peakHeapMb = heapMb

                val pss = readProcessPss()
                sumPssMb += pss.totalPssMb
                if (pss.totalPssMb > peakPssMb) peakPssMb = pss.totalPssMb

                // Sample battery current (mA)
                getBatteryCurrentMicroAmps()?.let { microA ->
                    val ma = abs(microA) / 1000.0   // magnitude; many devices use negative for discharge
                    sumCurrentMa += ma
                    currentSamples++
                }

                // Sample CPU temperature (°C) if available
                getCpuTemperatureC()?.let { tempC ->
                    sumCpuTempC += tempC
                    cpuTempSamples++
                }
            }

            val batteryEnd = getBatteryPercent()
            val avgNs = totalNs / rounds.toDouble()
            val avgMs = round(avgNs / 1_000_000.0)
            val totalMs = round(totalNs / 1_000_000.0)

            val labelNum = if (bestS0 >= bestS1) 1 else 2
            val niceName = if (labelNum == 1) "Benign" else "Malware"
            val emoji = if (labelNum == 1) "🙂" else "😈"
            val bestSim = if (bestS0 >= bestS1) bestS0 else bestS1

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

            // Average current and CPU temp strings
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

            // NEW: approximate energy per inference (mJ)
            val energyPerInfStr = if (avgMaForEnergy != null && batteryVoltageMv != null) {
                val avgCurrentA = avgMaForEnergy / 1000.0              // mA → A
                val totalSeconds = totalNs / 1_000_000_000.0           // ns → s
                val voltageV = batteryVoltageMv / 1000.0               // mV → V
                val totalEnergyJ = voltageV * avgCurrentA * totalSeconds
                val perInfJ = totalEnergyJ / rounds
                val perInfmJ = perInfJ * 1000.0
                String.format("%.3f mJ / inference (approx)", perInfmJ)
            } else {
                "n/a"
            }

            withContext(Dispatchers.Main) {
                dumpFile = outFile
                docHv = lastHv
                predictedEmoji = emoji
                predictedClassName = niceName

                predictionText = "Predicted: Class $labelNum ($niceName) @ D=$selectedDim"
                bestSimText = "%.4f".format(bestSim)
                class1SimText = "%.4f".format(bestS0)
                class2SimText = "%.4f".format(bestS1)

                avgMsText = "${avgMs.toLong()} ms (avg over $rounds)"
                totalMsText = "${totalMs.toLong()} ms (total)"
                batteryText = batteryStr
                peakHeapText = "${kotlin.math.round(peakHeapMb * 10) / 10.0} MB (peak heap)"

                avgPssText = avgPssStr
                peakPssText = peakPssStr

                avgCurrentText = avgCurrentStr
                avgCpuTempText = avgCpuTempStr
                energyPerInfText = energyPerInfStr

                isProcessing = false
            }
        }
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

    private fun viewDumpFile() {
        val file = dumpFile ?: return
        val uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Open dump"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //Load default class vectors for initial D
        loadClassVectorsForDim(selectedDim)

        enableEdgeToEdge()
        setContent {
            AndroidCodeClassifierTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ClassifierScreen(
                        modifier = Modifier.padding(innerPadding),
                        onPickApk = { openApkPicker() },
                        onViewDump = { viewDumpFile() },
                        hasDump = dumpFile != null,
                        isProcessing = isProcessing,          // APK benchmark in progress
                        isLoadingVectors = isLoadingVectors,  // vectors loading due to D change
                        // runs input
                        runsText = runsText,
                        onRunsChange = { txt -> runsText = txt.filter { it.isDigit() }.ifEmpty { "1" } },
                        // D dropdown
                        dOptions = dOptions,
                        selectedDim = selectedDim,
                        onSelectDim = { newD ->
                            if (!isProcessing && selectedDim != newD) {
                                selectedDim = newD

                                loadClassVectorsForDim(newD)
                                //Reset the UI for another run
                                predictionText = "No prediction yet"
                                bestSimText = "--"; class1SimText = "--"; class2SimText = "--"
                                avgMsText = "--"; totalMsText = "--"; batteryText = "--"; peakHeapText = "--"
                                avgPssText = "--"; peakPssText = "--"
                                avgCurrentText = "--"; avgCpuTempText = "--"
                                energyPerInfText = "--"
                                predictedEmoji = ""; predictedClassName = ""
                                dumpFile = null; docHv = null
                            }
                        },

                        emoji = predictedEmoji,
                        emojiLabel = predictedClassName,
                        prediction = predictionText,
                        similarity = bestSimText,
                        class1Sim = class1SimText,
                        class2Sim = class2SimText,
                        // benchmark stats
                        avgMs = avgMsText,
                        totalMs = totalMsText,
                        battery = batteryText,
                        peakHeap = peakHeapText,
                        // PSS stats
                        avgPss = avgPssText,
                        peakPss = peakPssText,
                        // power + thermal
                        avgCurrent = avgCurrentText,
                        avgCpuTemp = avgCpuTempText,
                        // NEW: energy
                        energyPerInf = energyPerInfText,
                        onOpenClassicModels = { openClassicModels() }
                    )
                }
            }
        }
    }

    private fun loadClassVectorsForDim(D: Int) {
        isLoadingVectors = true
        vectorsReady = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val benignName = "benign_class_vector $D.json"
                val malwareName = "malware_class_vector $D.json"
                val c0 = ClassVectorLoader.loadFromAssets(this@MainActivity, benignName)
                val c1 = ClassVectorLoader.loadFromAssets(this@MainActivity, malwareName)
                require(c0.size == D && c1.size == D) {
                    "Class vector dimension mismatch for D=$D. Got ${c0.size} and ${c1.size}"
                }
                withContext(Dispatchers.Main) {
                    class0 = c0
                    class1 = c1
                    vectorsReady = true
                    isLoadingVectors = false
                }
            } catch (t: Throwable) {
                Log.e("MainActivity", "Failed to load class vectors for D=$D", t)
                withContext(Dispatchers.Main) {
                    isLoadingVectors = false
                    vectorsReady = false
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to load class vectors for D=$D: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    //Function to get battery percentage
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

    // NEW: get battery voltage (mV) for energy estimate
    private fun getBatteryVoltageMillivolts(): Int? {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = registerReceiver(null, ifilter) ?: return null
        val mv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        return if (mv > 0) mv else null
    }

    // Get instantaneous battery current in microamps (may be negative for discharge)
    private fun getBatteryCurrentMicroAmps(): Int? {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (cur != Int.MIN_VALUE) cur else null
    }
    // Read a CPU-related thermal zone temperature from /sys/class/thermal in °C
    private fun readCpuThermalZoneTemp(): Double? {
        val dir = File("/sys/class/thermal")
        if (!dir.exists() || !dir.isDirectory) return null

        // Common CPU / SoC labels across vendors
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

                        // Most devices report millidegrees C (e.g., 34567 = 34.567°C)
                        val tempC = if (rawVal > 200) rawVal / 1000f else rawVal
                        return tempC.toDouble()
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Error reading thermal zone ${zone.name}: ${e.message}")
                }
            }
        }
        return null
    }


    // Get current CPU temperature in °C if supported (Android 11+)
    // Get current CPU temperature in °C using reflection (no direct ThermalManager dependency)
    // Get current CPU temperature in °C (from /sys/class/thermal), or null if not available
    private fun getCpuTemperatureC(): Double? {
        // No SDK dependency; just read Linux thermal zones
        return readCpuThermalZoneTemp()
    }




    //Memory Heap Measurement
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

    private fun openClassicModels() {
        startActivity(Intent(this, ClassicModelsActivity::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassifierScreen(
    modifier: Modifier = Modifier,
    onPickApk: () -> Unit,
    onViewDump: () -> Unit,
    hasDump: Boolean,
    isProcessing: Boolean,
    isLoadingVectors: Boolean,
    runsText: String,
    onRunsChange: (String) -> Unit,
    dOptions: List<Int>,
    selectedDim: Int,
    onSelectDim: (Int) -> Unit,
    emoji: String,
    emojiLabel: String,
    prediction: String,
    similarity: String,
    class1Sim: String,
    class2Sim: String,
    avgMs: String,
    totalMs: String,
    battery: String,
    peakHeap: String,
    avgPss: String,
    peakPss: String,
    // power + thermal
    avgCurrent: String,
    avgCpuTemp: String,
    // NEW: energy per inference
    energyPerInf: String,
    onOpenClassicModels: () -> Unit
) {
    val isBusy = isProcessing || isLoadingVectors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Android Code Classifier", fontSize = 24.sp, fontWeight = FontWeight.Bold)

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
                    value = "D = $selectedDim",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Dimension") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    dOptions.forEach { d ->
                        DropdownMenuItem(
                            text = { Text("D = $d") },
                            onClick = {
                                expanded = false
                                onSelectDim(d)
                            },
                            enabled = !isBusy
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = runsText,
                onValueChange = { onRunsChange(it) },
                label = { Text("Runs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.95f)
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onPickApk,
                enabled = !isBusy,
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth(0.95f)
            ) {
                Text(
                    when {
                        isProcessing -> "Processing APK..."
                        isLoadingVectors -> "Loading vectors..."
                        else -> "Pick APK"
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(text = prediction, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(text = "Best similarity: $similarity", fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(text = "Class 1 (Benign) similarity: $class1Sim", fontSize = 14.sp)
            Text(text = "Class 2 (Malware) similarity: $class2Sim", fontSize = 14.sp)

            Spacer(Modifier.height(12.dp))
            Text(text = "Avg inference time: $avgMs", fontSize = 14.sp)
            Text(text = "Total time: $totalMs", fontSize = 14.sp)
            Text(text = "Battery: $battery", fontSize = 14.sp)

            Text(text = "Avg PSS: $avgPss", fontSize = 14.sp)
            Text(text = "Peak PSS: $peakPss", fontSize = 14.sp)

            Text(text = "Peak heap used: $peakHeap", fontSize = 14.sp)

            // current + thermal info
            Spacer(Modifier.height(8.dp))
            Text(text = "Avg current: $avgCurrent", fontSize = 14.sp)
            Text(text = "CPU temp: $avgCpuTemp", fontSize = 14.sp)

            // NEW: energy per inference
            Text(text = "Energy per inference: $energyPerInf", fontSize = 14.sp)

            if (isBusy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Button(
                onClick = onViewDump,
                enabled = hasDump && !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("View Dump") }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onOpenClassicModels,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Classic Models") }
        }
    }
}
