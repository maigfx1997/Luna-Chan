package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelHistoryDao {
    @Query("SELECT * FROM novel_history ORDER BY savedAt DESC LIMIT 15")
    fun getRecentNovels(): Flow<List<NovelHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNovel(entity: NovelHistoryEntity)

    @Query("DELETE FROM novel_history WHERE title = :title AND id != :keepId")
    suspend fun deleteDuplicatesByTitle(title: String, keepId: String)

    @Query("DELETE FROM novel_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM novel_history WHERE id NOT IN (SELECT id FROM novel_history ORDER BY savedAt DESC LIMIT 15)")
    suspend fun trimToLatest15()

    @Query("DELETE FROM novel_history")
    suspend fun clearAll()
}
