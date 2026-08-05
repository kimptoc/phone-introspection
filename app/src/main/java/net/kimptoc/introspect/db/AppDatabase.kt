package net.kimptoc.introspect.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SampleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "introspect.db",
                ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .build().also { instance = it }
            }
    }
}
