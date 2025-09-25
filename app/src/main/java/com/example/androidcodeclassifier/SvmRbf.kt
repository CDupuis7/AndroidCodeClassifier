package com.example.androidcodeclassifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp


class SvmRbf private constructor(
    private val featureIndex: Map<String, Int>,
    private val nFeatures: Int,
    private val sv: Array<FloatArray>,
    private val alpha: FloatArray,
    private val bias: Float,
    private val classes: IntArray,
    private val gamma: Float,
    private val scale: FloatArray?,
    private val methodIdx: Int?,
    private val opcodeIdx: Int?
) {
    companion object {
        fun loadFromAssets(context: Context, assetName: String = "rbf_svm.json"): SvmRbf {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            // feature names -> indices
            val fnames = root.getJSONArray("feature_names")
            val f2i = HashMap<String, Int>(fnames.length())
            for (i in 0 until fnames.length()) {
                f2i[fnames.getString(i)] = i
            }

            //Support vectors
            val svJA = root.getJSONArray("support_vectors")
            val m = svJA.length()
            require(m > 0) { "No support vectors" }
            val firstRow = svJA.getJSONArray(0)
            val n = firstRow.length()
            val sv = Array(m) { i ->
                val row = svJA.getJSONArray(i)
                FloatArray(n) { j -> row.getDouble(j).toFloat() }
            }

            //Dual Coefficients
            val dcJA = root.getJSONArray("dual_coef").getJSONArray(0)
            require(dcJA.length() == m) { "dual_coef length ${dcJA.length()} != #SV $m" }
            val alpha = FloatArray(m) { i -> dcJA.getDouble(i).toFloat() }

            //Intercepts
            val bias = root.getJSONArray("intercept").getDouble(0).toFloat()

            //Classes
            val classesJA = root.getJSONArray("classes")
            require(classesJA.length() == 2) { "Only binary SVM is supported" }
            val classes = IntArray(2) { i -> classesJA.getInt(i) }

            //Gamma
            val gamma = root.getString("gamma").toFloat()

            //Scaler
            val scale: FloatArray? = if (root.has("scaler_scale")) {
                val arr = root.getJSONArray("scaler_scale")
                require(arr.length() == n) { "scaler_scale length ${arr.length()} != nFeatures $n" }
                FloatArray(n) { i -> arr.getDouble(i).toFloat().let { if (it == 0f) 1f else it } }
            } else null

            val methodIdx = f2i["method_count"]
            val opcodeIdx = f2i["opcode_count"]
            val fixedMethodIdx: Int?
            val fixedOpcodeIdx: Int?
            if (methodIdx == null || opcodeIdx == null) {
                fixedMethodIdx = if (fnames.length() == n - 2) n - 2 else methodIdx
                fixedOpcodeIdx = if (fnames.length() == n - 2) n - 1 else opcodeIdx
            } else {
                fixedMethodIdx = methodIdx
                fixedOpcodeIdx = opcodeIdx
            }

            return SvmRbf(
                featureIndex = f2i,
                nFeatures = n,
                sv = sv,
                alpha = alpha,
                bias = bias,
                classes = classes,
                gamma = gamma,
                scale = scale,
                methodIdx = fixedMethodIdx,
                opcodeIdx = fixedOpcodeIdx
            )
        }
    }


    //Feature vectorizer
    fun featurizeDense(tokens: List<String>, methodCount: Int, opcodeCount: Int): FloatArray {
        val x = FloatArray(nFeatures)
        // bag-of-words counts
        for (t in tokens) {
            val idx = featureIndex[t] ?: continue
            if (idx < nFeatures) x[idx] += 1f
        }
        methodIdx?.let { if (it in x.indices) x[it] = methodCount.toFloat() }
        opcodeIdx?.let { if (it in x.indices) x[it] = opcodeCount.toFloat() }

        scale?.let { s ->
            for (i in x.indices) {
                val denom = s[i]
                if (denom != 0f) x[i] = x[i] / denom
            }
        }
        return x
    }

    //RBF distance
    private inline fun sqDist(a: FloatArray, b: FloatArray): Float {
        var acc = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            acc += d * d
        }
        return acc
    }

    fun decisionValue(x: FloatArray): Float {
        var fx = 0f
        for (j in sv.indices) {
            val k = kotlin.math.exp((-gamma * sqDist(x, sv[j])).toDouble()).toFloat()
            fx += alpha[j] * k
        }
        fx += bias
        return fx
    }
    //Prediction/Inference
    fun predict(x: FloatArray): Pair<Int, FloatArray> {
        val fx = decisionValue(x)
        val pred = if (fx >= 0f) classes[1] else classes[0]
        val p1 = (1.0 / (1.0 + kotlin.math.exp((-fx).toDouble()))).toFloat() // pseudo
        return pred to floatArrayOf(1f - p1, p1)
    }
}
