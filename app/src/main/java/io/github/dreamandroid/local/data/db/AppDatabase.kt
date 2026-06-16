package io.github.dreamandroid.local.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [TaskEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1 -> v2: drop generationTimeMs column (SQLite < 3.35 doesn't support DROP COLUMN
        // directly, so recreate the table). All other columns and indices unchanged.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE generation_history_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        modelId TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        imagePath TEXT NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        denoiseStrength REAL,
                        upscalerId TEXT,
                        steps INTEGER NOT NULL,
                        cfg REAL NOT NULL,
                        seed INTEGER,
                        prompt TEXT NOT NULL,
                        negativePrompt TEXT NOT NULL,
                        generationTime TEXT,
                        scheduler TEXT NOT NULL,
                        runOnCpu INTEGER NOT NULL,
                        useOpenCL INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO generation_history_new
                    (id, modelId, timestamp, imagePath, width, height, mode,
                     denoiseStrength, upscalerId, steps, cfg, seed, prompt,
                     negativePrompt, generationTime, scheduler, runOnCpu, useOpenCL)
                    SELECT id, modelId, timestamp, imagePath, width, height, mode,
                           denoiseStrength, upscalerId, steps, cfg, seed, prompt,
                           negativePrompt, generationTime, scheduler, runOnCpu, useOpenCL
                    FROM generation_history
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE generation_history")
                db.execSQL("ALTER TABLE generation_history_new RENAME TO generation_history")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_modelId_timestamp ON generation_history (modelId, timestamp)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_generation_history_timestamp ON generation_history (timestamp)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_history_mode ON generation_history (mode)")
            }
        }

        // v2 -> v3: Add queue_tasks table for persistent queue storage
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS queue_tasks (
                        id TEXT PRIMARY KEY NOT NULL,
                        batch_group_id TEXT NOT NULL,
                        batch_index INTEGER NOT NULL,
                        model_id TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        negative_prompt TEXT NOT NULL,
                        steps INTEGER NOT NULL,
                        cfg REAL NOT NULL,
                        seed INTEGER,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        effective_width INTEGER NOT NULL,
                        effective_height INTEGER NOT NULL,
                        denoise_strength REAL NOT NULL,
                        use_opencl INTEGER NOT NULL,
                        scheduler TEXT NOT NULL,
                        aspect_ratio TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        timestamp INTEGER NOT NULL,
                        result_seed INTEGER,
                        error_message TEXT,
                        progress REAL NOT NULL DEFAULT 0.0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_queue_tasks_batch_group_id ON queue_tasks (batch_group_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_queue_tasks_status ON queue_tasks (status)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_queue_tasks_timestamp ON queue_tasks (timestamp)",
                )
            }
        }

        // v3 -> v4: Merge generation_history + queue_tasks into unified tasks table
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create unified tasks table
                db.execSQL(
                    """
                    CREATE TABLE tasks (
                        id TEXT PRIMARY KEY NOT NULL,
                        task_type TEXT NOT NULL,
                        model_id TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        negative_prompt TEXT NOT NULL,
                        steps INTEGER NOT NULL,
                        cfg REAL NOT NULL,
                        seed INTEGER,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        denoise_strength REAL,
                        use_opencl INTEGER NOT NULL,
                        scheduler TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        batch_group_id TEXT,
                        batch_index INTEGER,
                        effective_width INTEGER,
                        effective_height INTEGER,
                        aspect_ratio TEXT,
                        status TEXT,
                        result_seed INTEGER,
                        error_message TEXT,
                        progress REAL,
                        image_path TEXT,
                        mode TEXT,
                        upscaler_id TEXT,
                        generation_time TEXT,
                        run_on_cpu INTEGER,
                        tags TEXT
                    )
                    """.trimIndent(),
                )

                // 2. Migrate queue_tasks → tasks (type = QUEUE)
                db.execSQL(
                    """
                    INSERT INTO tasks
                    (id, task_type, model_id, prompt, negative_prompt,
                     steps, cfg, seed, width, height,
                     denoise_strength, use_opencl, scheduler, timestamp,
                     batch_group_id, batch_index, effective_width, effective_height,
                     aspect_ratio, status, result_seed, error_message, progress)
                    SELECT id, 'QUEUE', model_id, prompt, negative_prompt,
                           steps, cfg, seed, width, height,
                           denoise_strength, use_opencl, scheduler, timestamp,
                           batch_group_id, batch_index, effective_width, effective_height,
                           aspect_ratio, status, result_seed, error_message, progress
                    FROM queue_tasks
                    """.trimIndent(),
                )

                // 3. Migrate generation_history → tasks (type = HISTORY)
                //    Old INTEGER PK → new String UUID PK
                //    runOnCpu (INTEGER 0/1) → run_on_cpu (INTEGER)
                db.execSQL(
                    """
                    INSERT INTO tasks
                    (id, task_type, model_id, prompt, negative_prompt,
                     steps, cfg, seed, width, height,
                     denoise_strength, use_opencl, scheduler, timestamp,
                     image_path, mode, upscaler_id, generation_time, run_on_cpu)
                    SELECT id, 'HISTORY', modelId, prompt, negativePrompt,
                           steps, cfg, seed, width, height,
                           denoiseStrength, useOpenCL, scheduler, timestamp,
                           imagePath, mode, upscalerId, generationTime, runOnCpu
                    FROM generation_history
                    """.trimIndent(),
                )

                // 4. Drop old tables
                db.execSQL("DROP TABLE IF EXISTS generation_history")
                db.execSQL("DROP TABLE IF EXISTS queue_tasks")

                // 5. Create indices
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_type ON tasks (task_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_type_model_timestamp ON tasks (task_type, model_id, timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_batch_group_id ON tasks (batch_group_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_timestamp ON tasks (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks (status)")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dreamandroid.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { INSTANCE = it }
        }
    }
}
