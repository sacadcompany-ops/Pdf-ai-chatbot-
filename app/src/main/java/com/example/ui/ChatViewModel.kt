package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.AiFallbackManager
import com.example.data.AppDatabase
import com.example.data.ChatMessageEntity
import com.example.data.PdfDocumentEntity
import com.example.data.PdfExtractor
import com.example.data.ProviderConfigEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val pdfDao = db.pdfDao()
    private val chatDao = db.chatDao()
    private val providerDao = db.providerDao()
    private val fallbackManager = AiFallbackManager(application)

    val documents: StateFlow<List<PdfDocumentEntity>> = pdfDao.getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val providers: StateFlow<List<ProviderConfigEntity>> = providerDao.getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProvider: StateFlow<String> = fallbackManager.activeProvider
    val fallbackEvent: StateFlow<String?> = fallbackManager.fallbackEvent

    private val _selectedDocument = MutableStateFlow<PdfDocumentEntity?>(null)
    val selectedDocument: StateFlow<PdfDocumentEntity?> = _selectedDocument.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val messages: StateFlow<List<ChatMessageEntity>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private var messageCollectionJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            fallbackManager.initializeDefaultProviders()
        }
    }

    fun selectDocument(document: PdfDocumentEntity?) {
        _selectedDocument.value = document
        messageCollectionJob?.cancel()
        if (document != null) {
            messageCollectionJob = viewModelScope.launch {
                chatDao.getMessagesForDocument(document.id).collect { msgs ->
                    _messages.value = msgs
                }
            }
        } else {
            _messages.value = emptyList()
        }
    }

    fun addDocumentFromUri(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = PdfExtractor.extractTextFromUri(getApplication(), uri)
                val entity = PdfDocumentEntity(
                    title = result.title,
                    fileName = result.fileName,
                    pageCount = result.pageCount,
                    extractedText = result.extractedText
                )
                val id = pdfDao.insertDocument(entity)
                val inserted = pdfDao.getDocumentById(id)
                if (inserted != null) {
                    selectDocument(inserted)
                    _uiMessage.value = "Successfully imported ${inserted.fileName}"
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to import PDF", e)
                _uiMessage.value = "Failed to import PDF: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteDocument(document: PdfDocumentEntity) {
        viewModelScope.launch {
            chatDao.clearMessagesForDocument(document.id)
            pdfDao.deleteDocument(document)
            if (_selectedDocument.value?.id == document.id) {
                selectDocument(null)
            }
            _uiMessage.value = "Deleted ${document.fileName}"
        }
    }

    fun sendMessage(prompt: String) {
        val doc = _selectedDocument.value
        if (doc == null) {
            _uiMessage.value = "Please select or upload a PDF document first."
            return
        }
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank() || _isLoading.value) return

        viewModelScope.launch {
            val recentHistory = _messages.value
                .filter { it.providerUsed != "ERROR" }
                .takeLast(6)
                .map { it.role to it.content }

            val userMsg = ChatMessageEntity(
                documentId = doc.id,
                role = "user",
                content = cleanPrompt,
                providerUsed = "USER"
            )
            chatDao.insertMessage(userMsg)
            _isLoading.value = true

            try {
                val relevantContext = PdfExtractor.retrieveRelevantContext(cleanPrompt, doc.extractedText)
                val (responseContent, providerUsed) = fallbackManager.queryWithFallback(
                    prompt = cleanPrompt,
                    pdfContext = relevantContext,
                    history = recentHistory
                )
                val modelMsg = ChatMessageEntity(
                    documentId = doc.id,
                    role = "model",
                    content = responseContent,
                    providerUsed = providerUsed,
                    citation = "Cited from ${doc.fileName}"
                )
                chatDao.insertMessage(modelMsg)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "AI query failed", e)
                val errorMsg = ChatMessageEntity(
                    documentId = doc.id,
                    role = "model",
                    content = "Error: ${e.localizedMessage}",
                    providerUsed = "ERROR"
                )
                chatDao.insertMessage(errorMsg)
                _uiMessage.value = e.localizedMessage
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun solveStepByStep() {
        sendMessage(
            "Find the math, calculation, or exercise problems in this PDF and solve them step-by-step for a student. " +
                "Show Given Data, Formula/Rule, every calculation step clearly, and the Final Answer with an explanation of why each step works."
        )
    }

    fun explainSubjectSimply() {
        sendMessage(
            "Explain the main subject and lessons inside this PDF to a student in simple, clear terms. " +
                "Break down key concepts, give practical examples, and explain any formulas or rules step-by-step."
        )
    }

    fun generatePracticeQuiz() {
        sendMessage(
            "Create 3 practice questions/exercises based on this PDF for a student, and provide the complete step-by-step worked solution for each question."
        )
    }

    fun summarizeDocument() {
        sendMessage("Please provide a comprehensive summary of this document, including key points, formulas, and takeaways.")
    }

    fun updateProviderConfig(config: ProviderConfigEntity) {
        viewModelScope.launch {
            providerDao.upsertProvider(config)
            _uiMessage.value = "Updated settings for ${config.providerName}"
        }
    }

    fun testConnection(providerName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resp = fallbackManager.testSingleProvider(providerName)
                _uiMessage.value = "$providerName: $resp"
            } catch (e: Exception) {
                _uiMessage.value = "$providerName test failed: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun testAllConnections() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val results = fallbackManager.testAllProviders()
                val summary = results.entries.joinToString("\n") { (name, status) ->
                    "• $name: $status"
                }
                _uiMessage.value = "Hubinta Dhammaan API-yada:\n$summary"
            } catch (e: Exception) {
                _uiMessage.value = "Hubinta ayaa fashilantay: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun isKeyConfigured(config: ProviderConfigEntity): Boolean {
        return fallbackManager.getEffectiveKey(config).isNotBlank()
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }
}
