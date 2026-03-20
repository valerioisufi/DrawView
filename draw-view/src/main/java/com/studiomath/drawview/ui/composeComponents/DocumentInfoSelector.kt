package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A composable that displays an expandable card containing document information.
 *
 * In its collapsed state, it shows the document name with an expansion icon.
 * When expanded, it reveals additional details such as the full name and synchronization status.
 *
 * @param documentName The name of the document to display. If null, a loading placeholder is shown.
 * @param modifier The [Modifier] to be applied to the component.
 */
@Composable
fun DocumentInfoSelector(
    documentName: String?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Utilizziamo il nostro componente super modulare!
    ModularExpandableCard(
        isExpanded = expanded,
        modifier = modifier,
        expandedAlignment = Alignment.TopCenter,
        // Definiamo qui lo stile della card
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colorScheme.surface,
        elevation = if (expanded) 8.dp else 0.dp, // L'elevazione si anima automaticamente

        // --- 1. STATO CHIUSO ---
        collapsedContent = {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    // Puoi abbassare o alzare il padding verticale per decidere l'altezza millimetrica
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    modifier = Modifier.padding(end = 8.dp),
                    text = documentName ?: "Caricamento...",
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Espandi info",
                    modifier = Modifier.size(20.dp)
                )
            }
        },

        // --- 2. STATO ESPANSO ---
        expandedContent = {
            Column(
                // Manteniamo i vincoli di larghezza e padding che servono al contenuto
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .wrapContentWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = false }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        modifier = Modifier.padding(end = 8.dp),
                        text = documentName ?: "Caricamento...",
                        style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "Chiudi info",
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )

                // Dettagli Animati Insieme al Contenitore
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Dettagli Documento",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Nome: ${documentName ?: "Sconosciuto"}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Stato: Sincronizzato",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA) // Sfondo leggermente grigio per far risaltare l'ombra
@Composable
fun PreviewDocumentInfoSelector() {
    MaterialTheme { // Fondamentale per far funzionare correttamente MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp) // Diamo spazio sufficiente in basso per l'espansione
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                // Test 1: Nome standard
                DocumentInfoSelector(
                    documentName = "Appunti_Fisica_1.pdf"
                )


                // Test 2: Nome molto lungo per verificare l'effetto "Ellipsis" (...)
                DocumentInfoSelector(
                    documentName = "Questo_è_un_nome_di_documento_estremamente_lungo_che_sicuramente_verra_tagliato.pdf"
                )
            }
        }
    }
}