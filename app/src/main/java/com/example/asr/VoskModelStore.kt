package com.example.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

data class VoskLocalModel(val id: String, val name: String, val directory: File)

/** Manages user-imported Vosk archives entirely inside app-private storage. */
class VoskModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("vosk_model_store", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "vosk_models").apply { mkdirs() }

    private val _models = MutableStateFlow<List<VoskLocalModel>>(emptyList())
    val models: StateFlow<List<VoskLocalModel>> = _models.asStateFlow()
    private val _selectedId = MutableStateFlow(prefs.getString("selected_id", null))
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { refresh() }

    fun refresh() {
        _models.value = root.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".tmp") }
            ?.map { VoskLocalModel(it.name, prefs.getString("name_" + it.name, it.name) ?: it.name, it) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (_selectedId.value != null && _models.value.none { it.id == _selectedId.value }) {
            select(null)
        }
    }

    fun select(id: String?) {
        _selectedId.value = id
        prefs.edit().putString("selected_id", id).apply()
    }

    fun selected(): VoskLocalModel? = _models.value.firstOrNull { it.id == _selectedId.value }

    suspend fun importZip(uri: Uri, fileName: String?): Boolean = withContext(Dispatchers.IO) {
        if (_importing.value) return@withContext false
        _importing.value = true
        _error.value = null
        val id = "vosk_" + UUID.randomUUID().toString()
        val archive = File(context.cacheDir, id + ".zip")
        val staging = File(root, id + ".tmp")
        val destination = File(root, id)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(archive).use { output -> input.copyTo(output) }
            } ?: throw IOException("Could not open the selected ZIP file.")
            staging.mkdirs()
            extractZip(archive, staging)
            val children = staging.listFiles().orEmpty()
            val modelRoot = if (children.size == 1 && children[0].isDirectory) children[0] else staging
            if (modelRoot == staging) {
                if (!staging.renameTo(destination)) throw IOException("Could not install the imported model.")
            } else {
                if (!modelRoot.renameTo(destination)) throw IOException("Could not install the imported model.")
                staging.deleteRecursively()
            }
            val displayName = fileName.orEmpty().substringBeforeLast('.').ifBlank { "Vosk model" }
            prefs.edit().putString("name_" + id, displayName).apply()
            refresh()
            select(id)
            true
        } catch (t: Exception) {
            staging.deleteRecursively()
            destination.deleteRecursively()
            _error.value = t.localizedMessage ?: "Could not import Vosk model."
            Log.e("VoskModelStore", "Vosk ZIP import failed", t)
            false
        } finally {
            archive.delete()
            staging.deleteRecursively()
            _importing.value = false
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        prefs.edit().remove("name_" + id).apply()
        if (_selectedId.value == id) select(null)
        refresh()
    }

    private fun extractZip(zip: File, target: File) {
        val canonicalRoot = target.canonicalPath + File.separator
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val outputFile = File(target, entry.name)
                val canonical = outputFile.canonicalPath
                if (!canonical.startsWith(canonicalRoot)) throw SecurityException("Invalid ZIP entry path.")
                if (entry.isDirectory) outputFile.mkdirs() else {
                    outputFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }
}
