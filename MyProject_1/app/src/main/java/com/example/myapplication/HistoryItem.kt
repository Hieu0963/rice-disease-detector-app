package com.example.myapplication

import com.google.gson.annotations.SerializedName

/**
 * Data class đại diện cho một mục trong lịch sử nhận diện.
 * isSelected là biến tạm thời cho UI, không cần lưu vào JSON.
 */
data class HistoryItem(
    val timestamp: Long,
    val diseaseName: String,
    val confidence: String,
    val documentId: String,
    val isSynced: Boolean,
    @Transient var isSelected: Boolean = false
)
