package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDao {
    @Query("SELECT * FROM pdf_documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getDocumentById(id: Long): PdfDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: PdfDocumentEntity): Long

    @Delete
    suspend fun deleteDocument(document: PdfDocumentEntity)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE documentId = :documentId ORDER BY timestamp ASC")
    fun getMessagesForDocument(documentId: Long): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE documentId = :documentId")
    suspend fun clearMessagesForDocument(documentId: Long)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider_configs ORDER BY priority ASC")
    fun getAllProviders(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs")
    suspend fun getAllProvidersSync(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE providerName = :name")
    suspend fun getProvider(name: String): ProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(config: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE providerName = :name")
    suspend fun deleteProvider(name: String)
}
