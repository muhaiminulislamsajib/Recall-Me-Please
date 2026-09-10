package com.example.data.model

import androidx.annotation.Keep

@Keep
data class Memory(
    val memoryId: String = "",
    val userId: String = "",
    val date: String = "",
    val time: String = "",
    val timestamp: Long = 0L,
    val title: String = "",
    val content: String = "",
    val source: String = SOURCE_AUTOMATIC,
    val detectedPeople: List<String> = emptyList(),
    val detectedTask: String? = null,
    val detectedDeadline: String? = null,
    val importanceLevel: String = IMPORTANCE_NORMAL
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "memoryId" to memoryId,
            "userId" to userId,
            "date" to date,
            "time" to time,
            "timestamp" to timestamp,
            "title" to title,
            "content" to content,
            "source" to source,
            "detectedPeople" to detectedPeople,
            "detectedTask" to detectedTask,
            "detectedDeadline" to detectedDeadline,
            "importanceLevel" to importanceLevel
        )
    }

    companion object {
        const val SOURCE_RECALL_ME_PLEASE = "Recall Me Please"
        const val SOURCE_AUTOMATIC = "Automatic"

        const val IMPORTANCE_HIGH = "High"
        const val IMPORTANCE_MEDIUM = "Medium"
        const val IMPORTANCE_NORMAL = "Normal"

        fun fromMap(id: String, map: Map<String, Any?>): Memory {
            @Suppress("UNCHECKED_CAST")
            return Memory(
                memoryId = id,
                userId = map["userId"] as? String ?: "",
                date = map["date"] as? String ?: "",
                time = map["time"] as? String ?: "",
                timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                title = map["title"] as? String ?: "Note",
                content = map["content"] as? String ?: "",
                source = map["source"] as? String ?: SOURCE_AUTOMATIC,
                detectedPeople = (map["detectedPeople"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                detectedTask = map["detectedTask"] as? String,
                detectedDeadline = map["detectedDeadline"] as? String,
                importanceLevel = map["importanceLevel"] as? String ?: IMPORTANCE_NORMAL
            )
        }
    }
}
