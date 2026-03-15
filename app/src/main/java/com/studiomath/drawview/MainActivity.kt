package com.studiomath.drawview

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.sqlite.db.SimpleSQLiteQuery
import com.studiomath.drawview.data.db.DocumentEntity
import com.studiomath.drawview.data.db.DrawDatabase
import com.studiomath.drawview.ui.theme.DrawViewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // Trigger utilizzato per forzare il ricaricamento della lista
    // quando l'utente torna indietro dalla DrawActivity.
    private val refreshTrigger = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        refreshTrigger.intValue++
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DrawViewTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "I Miei Appunti",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                val intent = Intent(this@MainActivity, DrawActivity::class.java)
                                // ID -1 segnala al ViewModel di creare un nuovo documento vuoto
                                intent.putExtra("documentId", -1)
                                startActivity(intent)
                            }
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Crea Nuovo Documento")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    DocumentListScreen(
                        modifier = Modifier.padding(innerPadding),
                        refreshTrigger = refreshTrigger.intValue,
                        onDocumentClick = { documentId ->
                            val intent = Intent(this@MainActivity, DrawActivity::class.java)
                            intent.putExtra("documentId", documentId)
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentListScreen(
    modifier: Modifier = Modifier,
    refreshTrigger: Int,
    onDocumentClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var documents by remember { mutableStateOf<List<DocumentEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Ricarica i documenti dal database ogni volta che il refreshTrigger cambia (es. in onResume)
    val loadDocuments = {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val db = DrawDatabase.getInstance(context)

                // Utilizziamo una query SQL grezza per assicurarci che funzioni immediatamente,
                // anche se non hai ancora aggiunto il metodo `getAllDocuments` al tuo DocumentDao.
                // (In futuro, potrai spostare questa logica direttamente nel DAO).
                val query = SimpleSQLiteQuery("SELECT * FROM documents ORDER BY modifiedAt DESC")

                db.query(query).use { cursor ->
                    val list = mutableListOf<DocumentEntity>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                        val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        val modifiedAt = cursor.getLong(cursor.getColumnIndexOrThrow("modifiedAt"))

                        list.add(DocumentEntity(id = id, name = name, modifiedAt = modifiedAt))
                    }

                    withContext(Dispatchers.Main) {
                        documents = list
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        loadDocuments()
    }

    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (documents.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Nessun documento presente.\nUsa il pulsante + per crearne uno nuovo!",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = documents,
                key = { it.id } // Chiave univoca essenziale per le animazioni e lo swipe
            ) { doc ->
                var showRenameDialog by remember { mutableStateOf(false) }

                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { dismissValue ->
                        when (dismissValue) {
                            SwipeToDismissBoxValue.EndToStart -> {
                                // Scorrimento da destra verso sinistra -> Elimina
                                coroutineScope.launch {
                                    val db = DrawDatabase.getInstance(context)
                                    db.documentDao().delete(doc)
                                    loadDocuments() // Ricarica la lista
                                }
                                true // Consenti al box di essere dismesso
                            }
                            SwipeToDismissBoxValue.StartToEnd -> {
                                // Scorrimento da sinistra verso destra -> Rinomina
                                showRenameDialog = true
                                false // Ritorna false per far scattare indietro l'item, poiché non vogliamo eliminarlo dalla vista
                            }
                            else -> false
                        }
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        val direction = dismissState.dismissDirection

                        // Uso i colori "Container" per uno sfondo più morbido e moderno
                        val color by animateColorAsState(
                            targetValue = when (dismissState.targetValue) {
                                SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.background
                                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer // Rinomina
                                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer // Elimina
                            },
                            label = "color"
                        )

                        // Tint dinamico per le icone basato sul Container
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
                                tint = iconTint // Usiamo il tint calcolato sopra
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
                            // Resetta lo stato di swipe se l'utente annulla
                            coroutineScope.launch { dismissState.reset() }
                        },
                        title = {
                            Text(
                                text = "Rinomina Documento",
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
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
                                    coroutineScope.launch {
                                        val db = DrawDatabase.getInstance(context)
                                        // UPDATE: Uso di insert con OnConflictStrategy.REPLACE al posto di update
                                        db.documentDao().insert(doc.copy(name = newName, modifiedAt = System.currentTimeMillis()))
                                        dismissState.reset()
                                        loadDocuments() // Ricarica la lista per mostrare il nuovo nome
                                    }
                                }
                            ) {
                                Text("Rinomina", style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showRenameDialog = false
                                    coroutineScope.launch { dismissState.reset() }
                                }
                            ) {
                                Text("Annulla")
                            }
                        }
                    )
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