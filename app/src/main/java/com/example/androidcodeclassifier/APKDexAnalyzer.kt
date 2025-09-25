package com.example.androidcodeclassifier

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.DexFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ApkDexAnalyzer {

    private const val TAG = "ApkDexAnalyzer"

    private data class DexEntry(val name: String, val size: Long, val bytes: ByteArray?)

    //Function to Extract Opcodes from APK:
    suspend fun dumpFirstOpcodesToFile(
        context: Context,
        apkUri: Uri,
        maxLines: Int = 10_000,
        printToLogcat: Boolean = true
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dexEntries = listDexEntriesFromUri(context, apkUri, readBytes = true)
            if (dexEntries.isEmpty()) {
                Log.w(TAG, "No classes*.dex entries found in APK.")
                return@withContext null
            }

            val lines = ArrayList<String>(minOf(maxLines, 10_000))
            var count = 0

            outer@ for (dex in dexEntries.sortedBy { it.name }) {
                val bytes = dex.bytes ?: continue
                val dexFile: DexFile = loadDexFromBytes(context, bytes)  // <-- FIXED: load from temp file

                for (cls in dexFile.classes) {
                    for (m in cls.methods) {
                        val impl = m.implementation ?: continue
                        for (insn in impl.instructions) {
                            val method = m.name                    // just the method name
                            val opcode = insn.opcode.name
                            val line = "$method $opcode"           // e.g., "onCreate invoke-virtual"
                            lines.add(line)
                            if (printToLogcat) Log.i(TAG, line)
                            if (++count >= maxLines) break@outer
                        }
                    }
                }


            }

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val outFile = File(context.cacheDir, "apk_opcodes_$ts.txt")
            outFile.bufferedWriter().use { w -> lines.forEach { w.appendLine(it) } }
            Log.i(TAG, "Wrote ${lines.size} opcode lines to: ${outFile.absolutePath}")
            outFile
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to dump opcodes", t)
            null
        }
    }


    //Extracts the DEX files/Opcodes from the APK
    private fun listDexEntriesFromUri(
        context: Context,
        apkUri: Uri,
        readBytes: Boolean,
        maxEntryBytes: Long = 32L * 1024 * 1024
    ): List<DexEntry> {
        val tmpApk = copyToCache(context, apkUri)
        try {
            ZipFile(tmpApk).use { zf ->
                val out = mutableListOf<DexEntry>()
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e: ZipEntry = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name.startsWith("classes") && name.endsWith(".dex")) {
                        val size = e.size
                        if (!readBytes) {
                            out += DexEntry(name, size, null)
                        } else {
                            if (size in 1..maxEntryBytes) {
                                zf.getInputStream(e).use { ins ->
                                    out += DexEntry(name, size, ins.readBytes())
                                }
                            } else {
                                out += DexEntry(name, size, null) // skip huge entry
                            }
                        }
                    }
                }
                return out
            }
        } finally {
        }
    }
    //Copies APK to cache:
    private fun copyToCache(context: Context, uri: Uri): File {
        val cacheFile = File.createTempFile("apk_inspect_", ".apk", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { ins ->
            requireNotNull(ins) { "Unable to open Uri: $uri" }
            cacheFile.outputStream().use { outs -> ins.copyTo(outs, 8 * 1024) }
        }
        return cacheFile
    }

    //Saves dex files to a temporary file:
    private fun loadDexFromBytes(context: Context, bytes: ByteArray): DexFile {
        val tmpDex = File.createTempFile("dex_", ".dex", context.cacheDir)
        tmpDex.writeBytes(bytes)
        return DexFileFactory.loadDexFile(tmpDex, /*opcodes*/ null)
    }
}
