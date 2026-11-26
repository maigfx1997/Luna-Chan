package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppLanguage
import com.example.model.EbookFormat
import com.example.model.MultiExportMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lunaDataStore: DataStore<Preferences> by preferencesDataStore(name = "luna_chan_prefs")

data class UserPreferences(
    val language: AppLanguage = AppLanguage.AR,
    val isDarkMode: Boolean = false,
    val includeChapterImages: Boolean = true,
    val preSelectedFormats: Set<EbookFormat> = setOf(EbookFormat.EPUB),
    val multiExportMode: MultiExportMode = MultiExportMode.SEPARATE_FILES,
    val shareReceiveEnabled: Boolean = true,
    val shareInstantFullConvert: Boolean = false
)

class PreferencesRepository(private val context: Context) {

    private val keyLanguage = stringPreferencesKey("luna_lang")
    private val keyDarkMode = booleanPreferencesKey("luna_dark")
    private val keyIncludeImages = booleanPreferencesKey("luna_include_chapter_images")
    private val keyPreSelectedFormats = stringPreferencesKey("luna_pre_selected_formats")
    private val keyMultiExportMode = stringPreferencesKey("luna_multi_export_mode")
    private val keyShareReceiveEnabled = booleanPreferencesKey("luna_share_receive_enabled")
    private val keyShareInstantFullConvert = booleanPreferencesKey("luna_share_instant_full_convert")

    val userPreferences: Flow<UserPreferences> = context.lunaDataStore.data.map { prefs ->
        val rawFormats = prefs[keyPreSelectedFormats]
        val parsedFormats: Set<EbookFormat> = when {
            rawFormats == null -> setOf(EbookFormat.EPUB)
            rawFormats.isBlank() -> emptySet()
            else -> rawFormats.split(",")
                .mapNotNull { EbookFormat.fromExtension(it.trim()) }
                .toSet()
        }
        UserPreferences(
            language = AppLanguage.fromCode(prefs[keyLanguage]),
            isDarkMode = prefs[keyDarkMode] ?: false,
            includeChapterImages = prefs[keyIncludeImages] ?: true,
            preSelectedFormats = parsedFormats,
            multiExportMode = MultiExportMode.fromName(prefs[keyMultiExportMode]),
            shareReceiveEnabled = prefs[keyShareReceiveEnabled] ?: true,
            shareInstantFullConvert = prefs[keyShareInstantFullConvert] ?: false
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.lunaDataStore.edit { prefs ->
            prefs[keyLanguage] = language.code
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.lunaDataStore.edit { prefs ->
            prefs[keyDarkMode] = enabled
        }
    }

    suspend fun setIncludeChapterImages(enabled: Boolean) {
        context.lunaDataStore.edit { prefs ->
            prefs[keyIncludeImages] = enabled
        }
    }

    suspend fun setPreSelectedFormats(formats: Set<EbookFormat>) {
        context.lunaDataStore.edit { prefs ->
            prefs[keyPreSelectedFormats] = formats.joinToString(",") { it.extension }
        }
    }

    suspend fun setMultiExportMode(mode: MultiExportMode) {
        context.lunaDataStore.edit { prefs ->
            prefs[keyMultiExportMode] = mode.name
        }
    }

    suspend fun setShareReceiveEnabled(enabled: Boolean) {
        context.lunaDataStore.edit { prefs ->
            prefs[keyShareReceiveEnabled] = enabled
        }
    }

    suspend fun setShareInstantFullConvert(enabled: Boolean) {
        context.lunaDataStore.edit { prefs ->
            prefs[keyShareInstantFullConvert] = enabled
        }
    }
}
