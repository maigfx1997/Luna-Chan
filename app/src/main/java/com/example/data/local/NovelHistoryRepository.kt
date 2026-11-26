package com.example.data.local

import com.example.model.Chapter
import com.example.model.Novel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NovelHistoryRepository(private val dao: NovelHistoryDao) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val chaptersListType = Types.newParameterizedType(List::class.java, Chapter::class.java)
    private val chaptersAdapter = moshi.adapter<List<Chapter>>(chaptersListType)

    val recentNovels: Flow<List<Novel>> = dao.getRecentNovels().map { list ->
        list.map { entity -> entity.toDomain() }
    }

    suspend fun saveNovel(novel: Novel) {
        val now = System.currentTimeMillis()
        val entry = NovelHistoryEntity(
            id = novel.id.ifBlank { now.toString() },
            url = novel.url,
            title = novel.title.ifBlank { "Untitled Story" },
            author = novel.author,
            description = novel.description,
            coverUri = novel.coverUri,
            chaptersJson = chaptersAdapter.toJson(novel.chapters),
            savedAt = now
        )
        dao.deleteDuplicatesByTitle(entry.title, entry.id)
        dao.upsertNovel(entry)
        dao.trimToLatest15()
    }

    suspend fun deleteNovel(id: String) {
        dao.deleteById(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    private fun NovelHistoryEntity.toDomain(): Novel {
        val parsedChapters = runCatching {
            chaptersAdapter.fromJson(chaptersJson)
        }.getOrNull() ?: emptyList()
        return Novel(
            id = id,
            url = url,
            title = title,
            author = author,
            description = description,
            coverUri = coverUri,
            chapters = parsedChapters,
            savedAt = savedAt
        )
    }
}
