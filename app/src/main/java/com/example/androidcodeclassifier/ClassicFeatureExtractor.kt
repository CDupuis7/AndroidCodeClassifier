package com.example.androidcodeclassifier

import android.content.Context
import android.net.Uri
import com.example.androidcodeclassifier.ApkDexAnalyzer
import java.util.HashSet

object ClassicFeatureExtractor {

    data class Features(
        val tokens: List<String>,
        val methodCount: Int,
        val opcodeCount: Int
    )

    suspend fun extractOpcodeTokens(
        context: Context,
        apkUri: Uri,
        maxLines: Int = 10_000
    ): Features {
        val dump = ApkDexAnalyzer.dumpFirstOpcodesToFile(
            context = context,
            apkUri = apkUri,
            maxLines = maxLines,
            printToLogcat = false
        ) ?: return Features(emptyList(), 0, 0)

        val tokens = ArrayList<String>(16_384)
        val uniqueMethods = HashSet<String>(4096)
        var opcodeCount = 0

        dump.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEach
                val parts = t.split(' ', limit = 2)
                if (parts.size < 2) return@forEach
                val method = parts[0]
                val opcode = parts[1]
                uniqueMethods += method
                tokens += "api_$opcode"
                opcodeCount++
            }
        }

        return Features(tokens, uniqueMethods.size, opcodeCount)
    }
}
