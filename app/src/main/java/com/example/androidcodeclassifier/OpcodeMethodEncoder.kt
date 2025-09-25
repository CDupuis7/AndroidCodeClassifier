package com.example.androidcodeclassifier

import com.example.androidcodeclassifier.HvLookupFloat
import kotlin.math.roundToInt
import kotlin.random.Random

//Class for vectorizing (encoding) opcodes and methods.
class OpcodeMethodEncoder(
    private val D: Int,
    private val posFraction: Double = 0.5,
    private val baseSeed: Long = 123456789L,
    private val dict: HvLookupFloat? = null
) {
    private val acc = FloatArray(D)
    private val tmp = FloatArray(D)

    private val opcodeHv = HashMap<String, FloatArray>(512)
    private val methodHv = HashMap<String, FloatArray>(2048)

    fun reset() { java.util.Arrays.fill(acc, 0f) }

    fun add(method: String, opcode: String) {
        val m = methodHv.getOrPut(method) { resolveMethod(method) }
        val o = opcodeHv.getOrPut(opcode) { resolveOpcode(opcode) }
        HdcCore.bindBipolar(m, o, tmp)
        HdcCore.bundleInto(acc, tmp)
    }

    fun addLine(line: String) {
        val parts = line.trim().split(' ', limit = 2)
        if (parts.size < 2) return
        add(parts[0], parts[1])
    }

    fun finalizeVector(): FloatArray {
        val out = acc.copyOf()
        HdcCore.signNormalize(out)
        return out
    }

    private fun resolveOpcode(opcode: String): FloatArray {
        dict?.opcodes?.get(opcode)?.let { return it }
        return tokenHv("O", opcode)
    }


    private fun resolveMethod(methodKey: String): FloatArray {
        return tokenHv("M", methodKey)
    }


    private fun seedFor(ns: String, token: String): Long {
        val h = (ns + ":" + token).hashCode()
        val mixed = (h.toLong() shl 32) xor (h.toLong() * 123456789L)
        return baseSeed xor mixed
    }

    private fun tokenHv(ns: String, token: String): FloatArray {
        val seed = seedFor(ns, token)
        return randomBipolar(D, posFraction, seed)
    }

    //Random Vector Generator
    private fun randomBipolar(D: Int, posFraction: Double, seed: Long): FloatArray {
        val rnd = Random(seed)
        val hv = FloatArray(D) { -1f }
        val numPos = (D * posFraction).roundToInt()
        val idx = IntArray(D) { it }
        for (i in 0 until numPos) {
            val j = i + rnd.nextInt(D - i)
            val t = idx[i]; idx[i] = idx[j]; idx[j] = t
            hv[idx[i]] = 1f
        }
        return hv
    }
}
