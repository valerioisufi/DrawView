package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.InsertPageBreak
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ShapeLine
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.document.page.Document
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentInfoSelector(
    document: Document?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    // Calcolo metadati
    val totalPages = document?.pages?.size ?: 0
    val firstPage = document?.pages?.firstOrNull()
    val dimensionsStr = firstPage?.let { "${it.width.toInt()} x ${it.height.toInt()} mm" } ?: "N/D"

    var totalElements = 0
    document?.pages?.forEach { page ->
        totalElements += page.strokeData.size + page.imageData.size + page.textData.size + page.pdfData.size
    }

    ModularExpandableCard(
        isExpanded = expanded,
        modifier = modifier,
        onDismissRequest = { expanded = false }, // Chiusura automatica cliccando fuori
        expandedAlignment = Alignment.TopCenter,
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colorScheme.surface,
        elevation = if (expanded) 8.dp else 0.dp,

        // --- 1. STATO CHIUSO ---
        collapsedContent = {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    modifier = Modifier.padding(end = 8.dp),
                    text = document?.name ?: "Caricamento...",
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
                modifier = Modifier
                    // wrapContentSize rimosso o semplificato per evitare shift indesiderati
                    .widthIn(min = 260.dp, max = 320.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
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
                        text = document?.name ?: "Caricamento...",
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoRow(
                        icon = Icons.Outlined.DateRange,
                        label = "Creato il",
                        value = document?.createdAt?.let { dateFormat.format(Date(it)) } ?: "N/D"
                    )

                    InfoRow(
                        icon = Icons.Outlined.Edit,
                        label = "Modificato",
                        value = document?.modifiedAt?.let { dateFormat.format(Date(it)) } ?: "N/D"
                    )

                    InfoRow(
                        icon = Icons.Outlined.InsertPageBreak,
                        label = "Pagine",
                        value = totalPages.toString()
                    )

                    InfoRow(
                        icon = Icons.Outlined.AspectRatio,
                        label = "Formato",
                        value = dimensionsStr
                    )

                    InfoRow(
                        icon = Icons.Outlined.ShapeLine,
                        label = "Elementi",
                        value = totalElements.toString()
                    )
                }
            }
        }
    )
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}