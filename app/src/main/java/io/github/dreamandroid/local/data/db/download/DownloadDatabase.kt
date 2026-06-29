package io.github.dreamandroid.local.data.db.download

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Independent database for download task management.
 * Separate from the main dreamandroid.db to avoid polluting the generation task schema.
 */
@Database(
    entities = [DownloadTaskEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {

    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        fun get(context: Context): DownloadDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                DownloadDatabase::class.java,
                "downloads.db",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
