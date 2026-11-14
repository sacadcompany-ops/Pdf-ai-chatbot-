package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_documents")
data class PdfDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val fileName: String,
    val pageCount: Int,
    val extractedText: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentId: Long,
    val role: String, // "user" or "model"
    val content: String,
    val providerUsed: String,
    val citation: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val providerName: String, // "GEMINI", "GROQ", "CLOUDFLARE", "OPENROUTER", "ANYROUTER"
    val apiKey: String,
    val modelId: String,
    val isEnabled: Boolean,
    val priority: Int,
    val lastSuccessfulRequest: Long = 0L,
    val lastError: String? = null,
    val cooldownUntil: Long = 0L,
    val healthStatus: String = "HEALTHY", // "HEALTHY", "DEGRADED", "COOLDOWN", "DISABLED"
    val accountId: String = ""
)
