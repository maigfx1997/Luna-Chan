package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.model.CustomNovelSite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "custom_novel_sites")
data class CustomSiteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val host: String,
    val useSmartBrowserDefault: Boolean,
    val titleSelector: String,
    val authorSelector: String,
    val descriptionSelector: String,
    val coverSelector: String,
    val chapterListSelector: String,
    val chapterContentSelector: String,
    val addedAt: Long
)

@Dao
interface CustomSiteDao {
    @Query("SELECT * FROM custom_novel_sites ORDER BY addedAt DESC")
    fun observeAllSites(): Flow<List<CustomSiteEntity>>

    @Query("SELECT * FROM custom_novel_sites ORDER BY addedAt DESC")
    suspend fun getAllSitesSnapshot(): List<CustomSiteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSite(entity: CustomSiteEntity)

    @Query("DELETE FROM custom_novel_sites WHERE id = :id")
    suspend fun deleteSiteById(id: String)
}

class CustomSiteRepository(private val dao: CustomSiteDao) {

    val customSites: Flow<List<CustomNovelSite>> = dao.observeAllSites().map { list ->
        list.map { it.toModel() }
    }

    suspend fun getAllSnapshot(): List<CustomNovelSite> =
        dao.getAllSitesSnapshot().map { it.toModel() }

    suspend fun saveSite(site: CustomNovelSite) {
        dao.upsertSite(site.toEntity())
    }

    suspend fun deleteSite(id: String) {
        dao.deleteSiteById(id)
    }

    suspend fun findMatchingSite(url: String): CustomNovelSite? {
        val targetHost = extractNormalizedHost(url) ?: return null
        val all = getAllSnapshot()
        return all.firstOrNull { site ->
            val siteHost = site.host.lowercase()
            targetHost == siteHost || targetHost.endsWith(".$siteHost")
        }
    }

    private fun CustomSiteEntity.toModel() = CustomNovelSite(
        id = id,
        name = name,
        baseUrl = baseUrl,
        host = host,
        useSmartBrowserDefault = useSmartBrowserDefault,
        titleSelector = titleSelector,
        authorSelector = authorSelector,
        descriptionSelector = descriptionSelector,
        coverSelector = coverSelector,
        chapterListSelector = chapterListSelector,
        chapterContentSelector = chapterContentSelector,
        addedAt = addedAt
    )

    private fun CustomNovelSite.toEntity() = CustomSiteEntity(
        id = id,
        name = name,
        baseUrl = baseUrl,
        host = host,
        useSmartBrowserDefault = useSmartBrowserDefault,
        titleSelector = titleSelector,
        authorSelector = authorSelector,
        descriptionSelector = descriptionSelector,
        coverSelector = coverSelector,
        chapterListSelector = chapterListSelector,
        chapterContentSelector = chapterContentSelector,
        addedAt = addedAt
    )

    companion object {
        fun extractNormalizedHost(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val parsedHost = runCatching { android.net.Uri.parse(withScheme).host }.getOrNull()
                ?.lowercase()
                ?.removePrefix("www.")
                ?.removePrefix("m.")
                ?.trim()
            return parsedHost?.takeIf { it.contains('.') && it.length >= 4 }
        }

        fun normalizeBaseUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }
    }
}
