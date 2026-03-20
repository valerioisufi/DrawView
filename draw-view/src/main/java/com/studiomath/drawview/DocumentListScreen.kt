package com.studiomath.drawview

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.data.db.DocumentEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentListScreen(
    modifier: Modifier = Modifier,
    documents: List<DocumentEntity>,
    onDocumentClick: (Int) -> Unit,
    onCreateNewClick: () -> Unit,
    onDeleteDocument: (DocumentEntity) -> Unit,
    onRenameDocument: (DocumentEntity, String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("I Miei Appunti", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNewClick) {
                Icon(Icons.Filled.Add, contentDescription = "Crea Nuovo")
            }
        }
    ) { innerPadding ->

        if (documents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nessun documento presente.\nUsa il pulsante + per crearne uno nuovo!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // IL KEY È FONDAMENTALE QUI: aiuta Compose a capire quale elemento viene eliminato
                // o aggiunto con un'animazione fluida, senza ricaricare tutta la lista.
                items(items = documents, key = { it.id }) { doc ->
                    var showRenameDialog by remember { mutableStateOf(false) }

                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            when (dismissValue) {
                                SwipeToDismissBoxValue.EndToStart -> {
                                    onDeleteDocument(doc)
                                    true // Conferma lo scorrimento
                                }
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    showRenameDialog = true
                                    false // Respinge lo scorrimento, mostriamo solo il dialog
                                }
                                else -> false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val direction = dismissState.dismissDirection

                            val color by animateColorAsState(
                                targetValue = when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.background
                                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                },
                                label = "color"
                            )

                            val iconTint = when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onSecondaryContainer
                                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onBackground
                            }

                            val alignment = when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }

                            val icon = when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit
                                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                                else -> Icons.Default.Delete
                            }

                            val scale by animateFloatAsState(
                                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) 0.75f else 1f,
                                label = "scale"
                            )

                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(color, shape = CardDefaults.shape)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = alignment
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = "Swipe Action",
                                    modifier = Modifier.scale(scale),
                                    tint = iconTint
                                )
                            }
                        },
                        content = {
                            DocumentCard(document = doc, onClick = { onDocumentClick(doc.id) })
                        }
                    )

                    if (showRenameDialog) {
                        var newName by remember { mutableStateOf(doc.name) }
                        AlertDialog(
                            onDismissRequest = {
                                showRenameDialog = false
                                coroutineScope.launch { dismissState.reset() }
                            },
                            title = { Text("Rinomina Documento") },
                            text = {
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    singleLine = true,
                                    label = { Text("Nuovo Nome") }
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showRenameDialog = false
                                        // La modifica arriverà al ViewModel -> DB -> e tornerà istantaneamente qui tramite il Flow!
                                        onRenameDocument(doc, newName)
                                        coroutineScope.launch { dismissState.reset() }
                                    }
                                ) { Text("Rinomina") }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showRenameDialog = false
                                        coroutineScope.launch { dismissState.reset() }
                                    }
                                ) { Text("Annulla") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentCard(document: DocumentEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))

                val dateString = remember(document.modifiedAt) {
                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(document.modifiedAt))
                }
                Text(
                    text = "Ultima modifica: $dateString",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}