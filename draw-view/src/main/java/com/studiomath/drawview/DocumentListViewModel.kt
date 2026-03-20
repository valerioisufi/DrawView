package com.studiomath.drawview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.data.db.DocumentEntity
import com.studiomath.drawview.data.repository.FileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class DocumentListViewModel(application: Application) : AndroidViewModel(application) {

    private val fileRepository = FileRepository(application)

    // La magia è qui: stateIn trasforma il Flow del DB in uno StateFlow per Jetpack Compose.
    // Si aggiornerà DA SOLO ogni volta che salvi un nuovo file!
    val documents: StateFlow<List<DocumentEntity>> = fileRepository.getRecentDocumentsFlow(100)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Opzionale: puoi gestire il caricamento controllando se la lista è vuota (o usare altri approcci)
    // val isLoading: StateFlow<Boolean> = ...

    fun deleteDocument(document: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            fileRepository.deleteDocument(document.id)
            // NON serve più chiamare loadDocuments()! Il DB si aggiornerà da solo.
        }
    }

    fun renameDocument(document: DocumentEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            fileRepository.renameDocument(document.id, newName)
            // NON serve più chiamare loadDocuments()!
        }
    }
}