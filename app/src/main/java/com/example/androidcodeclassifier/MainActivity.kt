package com.example.androidcodeclassifier

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
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
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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
                predictedEmoji = ""; predictedClassName = ""
            }

            val batteryStart = getBatteryPercent()

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
                peakHeapText = "${round(peakHeapMb * 10) / 10.0} MB (peak heap)"

                avgPssText = avgPssStr
                peakPssText = peakPssStr

                isProcessing = false
            }
        }
    }

    private fun openApkPicker() {
        if (!isProcessing) {
            pickApk.launch(arrayOf(
                "application/vnd.android.package-archive",
                "application/zip",
                "*/*"
            ))
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
                        // NEW: PSS stats
                        avgPss = avgPssText,
                        peakPss = peakPssText,
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