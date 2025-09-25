package com.example.androidcodeclassifier

import android.content.Context
import android.util.JsonReader
import com.example.androidcodeclassifier.HvLookupFloat
import java.io.InputStreamReader

//Functions for reading and loading Opcode Vectors from JSON files. Specifically the premade hv_lookup 1.json file.
object HvOpcodeSelectiveLoader {

    fun loadSelectiveOpcodes(
        context: Context,
        neededOpcodes: Set<String>,
        expectedDim: Int,
        assetFileName: String = "hv_lookup 1.json"
    ): HvLookupFloat {
        val opMap = HashMap<String, FloatArray>(neededOpcodes.size.coerceAtLeast(16))

        context.assets.open(assetFileName).use { ins ->
            JsonReader(InputStreamReader(ins, Charsets.UTF_8)).use { jr ->
                jr.beginObject()
                while (jr.hasNext()) {
                    when (jr.nextName()) {
                        "opcodes" -> {
                            jr.beginObject()
                            while (jr.hasNext()) {
                                val key = jr.nextName()
                                if (key in neededOpcodes) {
                                    val hv = readVectorAdaptDim(jr, expectedDim)
                                    opMap[key] = hv
                                } else {
                                    jr.skipValue()
                                }
                            }
                            jr.endObject()
                        }
                        else -> jr.skipValue()
                    }
                }
                jr.endObject()
            }
        }


        return HvLookupFloat(opcodes = opMap, methods = emptyMap(), dimension = expectedDim)
    }


    private fun readVectorAdaptDim(jr: JsonReader, expectedDim: Int): FloatArray {
        val out = FloatArray(expectedDim)
        var i = 0
        var total = 0
        jr.beginArray()
        while (jr.hasNext()) {
            val v: Float = try { jr.nextDouble().toFloat() } catch (_: Throwable) { jr.nextString().toFloat() }
            if (i < expectedDim) {
                out[i++] = if (v >= 0f) 1f else -1f
            } else {

            }
            total++
        }
        jr.endArray()
        require(total >= expectedDim) {
            "Opcode vector length $total < expectedDim $expectedDim in hv_lookup 1.json"
        }
        return out
    }
}