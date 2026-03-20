package com.studiomath.drawview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DocumentListRoute(
    modifier: Modifier = Modifier,
    onNavigateToDocument: (Int) -> Unit
) {
    val viewModel: DocumentListViewModel = viewModel()

    // Magia di Jetpack Compose: questo valore si aggiorna da solo
    // ogni volta che il DB cambia, scatenando una ricomposizione della UI.
    val documents by viewModel.documents.collectAsState()

    DocumentListScreen(
        modifier = modifier,
        documents = documents,
        onCreateNewClick = { onNavigateToDocument(-1) },
        onDocumentClick = { id -> onNavigateToDocument(id) },
        onDeleteDocument = { doc -> viewModel.deleteDocument(doc) },
        onRenameDocument = { doc, newName -> viewModel.renameDocument(doc, newName) }
    )
}