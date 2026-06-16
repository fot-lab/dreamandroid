package io.github.dreamandroid.local.data

import android.content.Context
import android.util.Log
import io.github.dreamandroid.local.data.db.AppDatabase
import io.github.dreamandroid.local.data.db.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/**
 * Manages persistence of [GenerateParameterRecord] instances via Room (unified TaskEntity).
 *
 * Records are saved by Queue swipe-to-save and Gallery Save Info,
 * and displayed / managed in Generate → Records Tab.
 *
 * **Storage:**
 * - Room [TaskEntity] with [TaskEntity.TYPE_RECORD] — same ACID table as Queue + History
 * - [RecordSource] stored in [TaskEntity.tags] JSON bag
 *
 * **Migration:**
 * - On first init, legacy JSON file (`generate_records.json`) is imported into Room
 * - Imported file is renamed to `.migrated.{timestamp}` (not deleted, for safety)
 */
class RecordRepository(private val context: Context) {

    companion object {
        private const val TAG = "RecordRepository"
        private const val LEGACY_FILE = "generate_records.json"
    }

    private val dao = AppDatabase.get(context).taskDao()

    private val _records = MutableStateFlow<List<GenerateParameterRecord>>(emptyList())
    val records: StateFlow<List<GenerateParameterRecord>> = _records.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            migrateLegacyJsonIfNeeded()
            loadFromDb()
        }
    }

    /**
     * Add a new record and persist to Room.
     */
    suspend fun addRecord(record: GenerateParameterRecord): GenerateParameterRecord {
        dao.insert(record.toEntity())
        // Optimistic in-memory update
        val current = _records.value.toMutableList()
        current.add(0, record)
        _records.value = current
        return record
    }

    /**
     * Delete a record by id from Room.
     */
    suspend fun deleteRecord(id: String) {
        dao.deleteRecordById(id)
        _records.value = _records.value.filter { it.id != id }
    }

    /**
     * Delete all records from Room.
     */
    suspend fun deleteAll() {
        dao.deleteAllRecords()
        _records.value = emptyList()
    }

    // ── Internal ──

    private suspend fun loadFromDb() {
        try {
            val entities = dao.getAllRecords()
            _records.value = GenerateParameterRecord.listFromEntities(entities)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load records from DB", e)
        }
    }

    /**
     * One-time migration: import legacy JSON file into Room, then rename the file.
     * Idempotent — does nothing if the legacy file no longer exists.
     */
    private suspend fun migrateLegacyJsonIfNeeded() {
        val legacyFile = File(context.filesDir, LEGACY_FILE)
        if (!legacyFile.exists()) return

        Log.i(TAG, "Legacy records JSON found, migrating to Room...")
        try {
            val json = legacyFile.readText()
            if (json.isNotBlank()) {
                val arr = JSONArray(json)
                val records = GenerateParameterRecord.listFromJsonArray(arr)
                if (records.isNotEmpty()) {
                    val entities = records.map { it.toEntity() }
                    dao.insertAll(entities)
                    Log.i(TAG, "Migrated ${records.size} legacy records to Room")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy JSON migration failed, backing up file", e)
            // Backup corrupted file instead of deleting it
            val backup = File(context.filesDir, "$LEGACY_FILE.corrupted.${System.currentTimeMillis()}")
            legacyFile.renameTo(backup)
            return
        }

        // Migration successful → rename old file for safety (not delete)
        val migrated = File(context.filesDir, "$LEGACY_FILE.migrated.${System.currentTimeMillis()}")
        legacyFile.renameTo(migrated)
        Log.i(TAG, "Legacy JSON migrated → renamed to ${migrated.name}")
    }
}
