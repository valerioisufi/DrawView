package com.studiomath.drawview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DocumentListRoute(
    modifier: Modifier = Modifier,
    onNavigateToDocument: (Int) -> Unit // Deleghiamo la navigazione all'esterno!
) {
    // Istanziamento standard del ViewModel (AndroidViewModel richiede solo questa riga)
    val viewModel: DocumentListViewModel = viewModel()

    // Osserviamo i dati dal ViewModel
    val documents by viewModel.documents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Chiamiamo la UI pura passando i dati e gestendo le azioni
    DocumentListScreen(
        modifier = modifier,
        documents = documents,
        isLoading = isLoading,
        onCreateNewClick = { onNavigateToDocument(-1) },
        onDocumentClick = { id -> onNavigateToDocument(id) },
        onDeleteDocument = { doc -> viewModel.deleteDocument(doc) },
        onRenameDocument = { doc, newName -> viewModel.renameDocument(doc, newName) }
    )
}