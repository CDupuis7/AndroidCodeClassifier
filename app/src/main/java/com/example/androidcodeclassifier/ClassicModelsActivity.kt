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
import com.example.androidcodeclassifier.RfJsonForest
import com.example.androidcodeclassifier.ui.theme.AndroidCodeClassifierTheme
import com.example.androidcodeclassifier.ClassicFeatureExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

class ClassicModelsActivity : ComponentActivity() {

    //Private Variables
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
    private var runsText by mutableStateOf("500")
    private val modelOptions = listOf("RF", "SVM")
    private var selectedModel by mutableStateOf(modelOptions.first())

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) autoProcessApk(uri)
    }

    private fun openApkPicker() {
        pickApk.launch(arrayOf(
            "application/vnd.android.package-archive",
            "application/zip",
            "*/*"
        ))
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
                predictedEmoji = ""; predictedClassName = ""
            }

            val batteryStart = getBatteryPercent()

            //Feature Extraction:
            val feats = try {
                com.example.androidcodeclassifier.ClassicFeatureExtractor.extractOpcodeTokens(
                    context = this@ClassicModelsActivity,
                    apkUri = uri,
                    maxLines = 10_000
                )
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    Toast.makeText(this@ClassicModelsActivity, "Feature extraction failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            //Testing loop for benchmark:
            var totalNs = 0L
            var peakHeapMb = 0.0
            var peakPssMb = 0.0
            var sumPssMb = 0.0
            var lastLabel = 0
            var p0 = Float.NaN
            var p1 = Float.NaN
            var lastSvmMargin: Float? = null   // only used for SVM

            repeat(rounds) {
                val ns = measureNanoTime {
                    when (selectedModel) {
                        "RF" -> {
                            val rfModel = com.example.androidcodeclassifier.RfJsonForest
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
                            lastSvmMargin = null
                        }

                        "SVM" -> {
                            val svmModel = com.example.androidcodeclassifier.SvmRbf
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
                val heapMb = currentHeapUsedMb()
                if (heapMb > peakHeapMb) peakHeapMb = heapMb
                val pss = readProcessPss()
                sumPssMb += pss.totalPssMb
                if (pss.totalPssMb > peakPssMb) peakPssMb = pss.totalPssMb
            }

            val batteryEnd = getBatteryPercent()
            val avgNs = totalNs / rounds.toDouble()
            val avgMs = kotlin.math.round(avgNs / 1_000_000.0)
            val totalMs = kotlin.math.round(totalNs / 1_000_000.0)
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
                peakHeapText = "${kotlin.math.round(peakHeapMb * 10) / 10.0} MB (peak heap)"
                avgPssText = avgPssStr
                peakPssText = peakPssStr

                isProcessing = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidCodeClassifierTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ClassicModelsScreen(
                        modifier = Modifier.padding(innerPadding),
                        isProcessing = isProcessing,
                        runsText = runsText,
                        onRunsChange = { txt -> runsText = txt.filter { it.isDigit() }.ifEmpty { "1" } },
                        modelOptions = modelOptions,
                        selectedModel = selectedModel,
                        onSelectModel = { m -> if (!isProcessing) selectedModel = m },
                        // emoji + result
                        emoji = predictedEmoji,
                        emojiLabel = predictedClassName,
                        prediction = predictionText,
                        bestSim = bestSimText,
                        class0Sim = class0SimText,
                        class1Sim = class1SimText,
                        // metrics
                        avgMs = avgMsText,
                        totalMs = totalMsText,
                        battery = batteryText,
                        avgPss = avgPssText,
                        peakPss = peakPssText,
                        peakHeap = peakHeapText,
                        onPickApk = { openApkPicker() },
                        onBack = { finish() }
                    )
                }
            }
        }
    }


    //Benchmark Utils:
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
                // “Top probability” instead of “Best similarity”
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