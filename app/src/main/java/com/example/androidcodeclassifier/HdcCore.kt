package com.example.androidcodeclassifier
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.math.roundToInt


object HdcCore {


    //Bipolar Vector Gen: D size, Fraction of +1s, Random Seed
    @JvmStatic
    fun randomBipolar(D: Int, posFraction: Double = 0.5, seed: Long = 0L): FloatArray {
        require(D > 0) { "D must be > 0" }
        require(posFraction in 0.0..1.0) { "posFraction must be between 0.0 and 1.0" }
        val rnd = Random(seed)
        val hv = FloatArray(D) { -1f }
        val numPos = (D * posFraction).roundToInt()
        val idx = IntArray(D) { it }
        for (i in 0 until numPos) {
            val j = i + rnd.nextInt(D - i)
            val tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp
            hv[idx[i]] = 1f
        }
        return hv
    }

    //Binary Vector Gen: D size, Fraction of +1s, Random Seed
    @JvmStatic
    fun randomBinary(D: Int, trueFraction: Double = 0.5, seed: Long = 0L): BooleanArray {
        require(D > 0) { "D must be > 0" }
        require(trueFraction in 0.0..1.0) { "trueFraction must be between 0.0 and 1.0" }
        val rnd = Random(seed)
        val hv = BooleanArray(D)
        val numTrue = (D * trueFraction).roundToInt()
        val idx = IntArray(D) { it }
        for (i in 0 until numTrue) {
            val j = i + rnd.nextInt(D - i)
            val tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp
            hv[idx[i]] = true
        }
        return hv
    }

    //Bind for bipolar vectors: element-wise multiplication
    @JvmStatic
    fun bindBipolar(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(a.size)): FloatArray {
        require(a.size == b.size) { "bindBipolar: size mismatch ${a.size} vs ${b.size}" }
        require(out.size == a.size) { "bindBipolar: out wrong size" }
        for (i in a.indices) out[i] = a[i] * b[i]  // ±1f stays ±1f
        return out
    }

    //Bind for binary vectors: element-wise XOR
    @JvmStatic
    fun bindBinary(a: BooleanArray, b: BooleanArray, out: BooleanArray = BooleanArray(a.size)): BooleanArray {
        require(a.size == b.size) { "bindBinary: size mismatch ${a.size} vs ${b.size}" }
        require(out.size == a.size) { "bindBinary: out wrong size" }
        for (i in a.indices) out[i] = a[i] xor b[i]
        return out
    }


    //Bipolar Vector Bundling:
    @JvmStatic
    fun bundleInto(accumulator: FloatArray, hv: FloatArray) {
        require(accumulator.size == hv.size) { "bundleInto: size mismatch" }
        for (i in hv.indices) accumulator[i] += hv[i]
    }

    //In place sign normalization:
    @JvmStatic
    fun signNormalize(accumulator: FloatArray) {
        for (i in accumulator.indices) {
            val v = accumulator[i]
            accumulator[i] = if (v >= 0f) 1f else -1f
        }
    }

    //Binary Vector Bundling:
    @JvmStatic
    fun bundleIntoBinary(accumulator: IntArray, hv: BooleanArray) {
        require(accumulator.size == hv.size) { "bundleIntoBinary: size mismatch" }
        for (i in hv.indices) {
            accumulator[i] += if (hv[i]) 1 else -1
        }
    }

    //Normalization for Binary Vectors:
    @JvmStatic
    fun normalizeBinary(accumulator: IntArray, out: BooleanArray = BooleanArray(accumulator.size)): BooleanArray {
        for (i in accumulator.indices) {
            out[i] = accumulator[i] >= 0
        }
        return out
    }

    //Cosine Similarity: Bipolar Similarity
    @JvmStatic
    fun cosine(a: FloatArray, b: FloatArray, assumeUnitNormBipolar: Boolean = true): Float {
        require(a.size == b.size) { "cosine: size mismatch" }
        var dot = 0f
        if (assumeUnitNormBipolar) {
            // For bipolar ±1, norms are sqrt(D), so cosine = dot / D
            for (i in a.indices) dot += a[i] * b[i]
            return dot / a.size
        } else {
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                val ai = a[i]
                val bi = b[i]
                dot += ai * bi
                na += ai * ai
                nb += bi * bi
            }
            val denom = sqrt((na * nb).toDouble()).toFloat()
            return if (denom == 0f) 0f else dot / denom
        }
    }

    //Hamming Distance Similarity: Binary Similarity
    @JvmStatic
    fun hammingDistance(a: BooleanArray, b: BooleanArray): Int {
        require(a.size == b.size) { "hammingDistance: size mismatch" }
        var diff = 0
        for (i in a.indices) if (a[i] != b[i]) diff++
        return diff
    }


    @JvmStatic
    fun permuteLeft(a: FloatArray, k: Int, out: FloatArray = FloatArray(a.size)): FloatArray {
        val D = a.size; require(D > 0) { "permuteLeft: D must be > 0" }
        val s = ((k % D) + D) % D
        if (s == 0) { System.arraycopy(a, 0, out, 0, D); return out }
        val tail = D - s
        // [s..D-1] -> [0..tail-1], [0..s-1] -> [tail..D-1]
        for (i in 0 until tail) out[i] = a[i + s]
        for (i in 0 until s) out[tail + i] = a[i]
        return out
    }


    @JvmStatic
    fun permuteLeft(a: BooleanArray, k: Int, out: BooleanArray = BooleanArray(a.size)): BooleanArray {
        val D = a.size; require(D > 0) { "permuteLeft: D must be > 0" }
        val s = ((k % D) + D) % D
        if (s == 0) { System.arraycopy(a, 0, out, 0, D); return out }
        val tail = D - s
        for (i in 0 until tail) out[i] = a[i + s]
        for (i in 0 until s) out[tail + i] = a[i]
        return out
    }
}