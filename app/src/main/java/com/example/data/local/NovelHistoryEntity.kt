package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "novel_history")
data class NovelHistoryEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUri: String?,
    val chaptersJson: String,
    val savedAt: Long
)
