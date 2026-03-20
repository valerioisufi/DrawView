package com.studiomath.drawview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.data.db.DocumentEntity
import com.studiomath.drawview.data.repository.FileRepository // IMPORTANTE: Usa il Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DocumentListViewModel(application: Application) : AndroidViewModel(application) {

    // Inizializza il Repository invece del Database diretto
    private val fileRepository = FileRepository(application)

    private val _documents = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val documents: StateFlow<List<DocumentEntity>> = _documents.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadDocuments()
    }

    fun loadDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // Usiamo il repository.
                // Puoi usare getRecentDocuments(50) o getDocumentsInFolder(null) se vuoi solo la root
                val list = fileRepository.getRecentDocuments(100)
                _documents.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteDocument(document: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // QUESTO è fondamentale: chiama il repository, che si occuperà
            // di fare la Garbage Collection dei file PDF e Immagini!
            val success = fileRepository.deleteDocument(document.id)
            if (success) {
                loadDocuments() // Ricarica la lista solo se l'eliminazione ha successo
            }
        }
    }

    fun renameDocument(document: DocumentEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Usa il repository, che farà anche i controlli per evitare nomi duplicati
            // e aggiornerà automaticamente il modifiedAt grazie ai Trigger SQLite!
            val success = fileRepository.renameDocument(document.id, newName)
            if (success) {
                loadDocuments() // Ricarica la lista
            }
        }
    }
}