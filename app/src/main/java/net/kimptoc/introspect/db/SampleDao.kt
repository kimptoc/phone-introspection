package net.kimptoc.introspect.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples ORDER BY timestamp ASC")
    suspend fun getAll(): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM samples")
    suspend fun count(): Long
}
