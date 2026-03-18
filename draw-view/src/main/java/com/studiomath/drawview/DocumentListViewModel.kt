package com.studiomath.drawview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import com.studiomath.drawview.data.db.DocumentEntity
import com.studiomath.drawview.data.db.DrawDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DocumentListViewModel(application: Application) : AndroidViewModel(application) {
    private val db = DrawDatabase.getInstance(application)

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
                // (In futuro, ti consiglio di spostare questa query direttamente nel DocumentDao!)
                val query = SimpleSQLiteQuery("SELECT * FROM documents ORDER BY modifiedAt DESC")
                db.query(query).use { cursor ->
                    val list = mutableListOf<DocumentEntity>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        val modifiedAt = cursor.getLong(cursor.getColumnIndexOrThrow("modifiedAt"))
                        list.add(
                            DocumentEntity(
                                id=id,
                                name = name,
                                modifiedAt = modifiedAt
                            )
                        )
                    }
                    _documents.value = list
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteDocument(document: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.documentDao().delete(document)
            loadDocuments() // Ricarica la lista
        }
    }

    fun renameDocument(document: DocumentEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedDoc = document.copy(name = newName, modifiedAt = System.currentTimeMillis())
            db.documentDao().insert(updatedDoc)
            loadDocuments() // Ricarica la lista
        }
    }
}