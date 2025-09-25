package com.example.androidcodeclassifier

import android.content.Context
import org.json.JSONArray

object ClassVectorLoader {

    //Load JSON From Assets
    fun loadFromAssets(context: Context, fileName: String): FloatArray {
        val json = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val out = FloatArray(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            out[i] = when (v) {
                is Number -> v.toFloat()
                is String -> v.toFloat()
                else -> error("Unsupported JSON value at index $i: $v")
            }
        }
        return out
    }
}
