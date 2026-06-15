package io.github.dreamandroid.local.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages persistence of [GenerateParameterRecord] instances as a JSON file
 * in the app's internal files directory.
 *
 * Records are saved by Queue swipe-to-save and Gallery Save Info,
 * and displayed / managed in Generate → Records Tab.
 *
 * **Data durability:**
 * - Corrupted JSON → backed up to `.corrupted.{timestamp}` + partial recovery attempted
 * - Concurrent writes → protected by [Mutex]
 * - Atomic write → temp file + rename (no partial writes on disk)
 */
class RecordRepository(private val context: Context) {

    companion object {
        private const val TAG = "RecordRepository"
    }

    private val recordsFile: File
        get() = File(context.filesDir, "generate_records.json")

    private val _records = MutableStateFlow<List<GenerateParameterRecord>>(emptyList())
    val records: StateFlow<List<GenerateParameterRecord>> = _records.asStateFlow()

    private val writeMutex = Mutex()

    init {
        loadFromDisk()
    }

    /**
     * Add a new record and persist.
     * Returns the newly created record.
     */
    suspend fun addRecord(record: GenerateParameterRecord): GenerateParameterRecord {
        val current = _records.value.toMutableList()
        current.add(0, record) // newest first
        _records.value = current
        persist()
        return record
    }

    /**
     * Delete a record by id and persist.
     */
    suspend fun deleteRecord(id: String) {
        _records.value = _records.value.filter { it.id != id }
        persist()
    }

    /**
     * Delete all records.
     */
    suspend fun deleteAll() {
        _records.value = emptyList()
        persist()
    }

    /**
     * Load records from disk. On corruption:
     * 1. Back up the corrupted file as `.corrupted.{timestamp}`
     * 2. Attempt per-record partial recovery
     * 3. Never silently overwrite with empty list
     */
    private fun loadFromDisk() {
        try {
            if (recordsFile.exists()) {
                val json = recordsFile.readText()
                if (json.isNotBlank()) {
                    val arr = JSONArray(json)
                    _records.value = GenerateParameterRecord.listFromJsonArray(arr)
                        .sortedByDescending { it.timestamp }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Records file corrupted, attempting recovery", e)

            // 1. Back up the corrupted file
            val backupFile = File(
                recordsFile.parent,
                "generate_records.json.corrupted.${System.currentTimeMillis()}"
            )
            try {
                recordsFile.copyTo(backupFile, overwrite = false)
                Log.w(TAG, "Corrupted records backed up to ${backupFile.name}")
            } catch (_: Exception) {
                Log.e(TAG, "Failed to back up corrupted records file")
            }

            // 2. Attempt per-record partial recovery
            val recovered = attemptPartialRecovery()
            _records.value = recovered

            // 3. Only persist if we recovered something (never overwrite with empty)
            if (recovered.isNotEmpty()) {
                Log.i(TAG, "Partially recovered ${recovered.size} records")
                // Direct atomic write (non-blocking for init{} compatibility)
                try {
                    val arr = GenerateParameterRecord.listToJsonArray(recovered)
                    val tempFile = File(recordsFile.parent, "generate_records.json.tmp")
                    tempFile.writeText(arr.toString(2))
                    tempFile.renameTo(recordsFile)
                } catch (_: Exception) {
                    Log.e(TAG, "Failed to persist recovered records")
                }
            }
        }
    }

    /**
     * Attempt to recover individual records from a corrupted JSON file.
     * Uses regex to extract each {...} JSONObject and parses them independently.
     */
    private fun attemptPartialRecovery(): List<GenerateParameterRecord> {
        return try {
            val content = recordsFile.readText()
            val recovered = mutableListOf<GenerateParameterRecord>()

            // Match top-level JSON objects (simple brace matching)
            val jsonPattern = Regex("""\{[^}]*\}""")
            jsonPattern.findAll(content).forEach { match ->
                try {
                    val obj = JSONObject(match.value)
                    val record = GenerateParameterRecord.fromJson(obj)
                    if (record != null) {
                        recovered.add(record)
                    }
                } catch (_: Exception) {
                    // Skip individual corrupted records
                }
            }

            recovered.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun persistSafely() {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val arr = GenerateParameterRecord.listToJsonArray(_records.value)
                    // Atomic write: temp file → rename (atomic on same filesystem)
                    val tempFile = File(recordsFile.parent, "generate_records.json.tmp")
                    tempFile.writeText(arr.toString(2))
                    tempFile.renameTo(recordsFile)
                } catch (_: Exception) {
                    // Persistence failure is non-fatal; data retained in memory
                }
            }
        }
    }

    private suspend fun persist() {
        persistSafely()
    }
}
