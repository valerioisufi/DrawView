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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.R
import com.studiomath.drawview.document.page.Document
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A composable component that displays an interactive, expandable card containing document metadata.
 * * In its collapsed state, the component shows the document's name and serves as a compact
 * header element. When expanded by the user, it calculates and displays detailed properties
 * of the provided [Document], including timestamps, total page count, physical dimensions,
 * and a cumulative count of all drawable elements (strokes, images, text, and PDF layers)
 * across the document structure.
 *
 * @param document The [Document] object containing the metadata and page content to be analyzed.
 * Can be null to indicate a loading or uninitialized state.
 * @param modifier The [Modifier] to be applied to the root container of this component.
 */
@Composable
fun DocumentInfoSelector(
    document: Document?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    val totalPages = document?.pages?.size ?: 0
    val firstPage = document?.pages?.firstOrNull()
    val dimensionsStr = firstPage?.let {
        stringResource(
            R.string.document_info_value_dimensions,
            it.width.toInt(),
            it.height.toInt()
        ) } ?: stringResource(
        R.string.common_label_not_available
    )

    var totalElements = 0
    document?.pages?.forEach { page ->
        totalElements += page.strokeData.size + page.imageData.size + page.textData.size + page.pdfData.size
    }

    ModularExpandableCard(
        isExpanded = expanded,
        modifier = modifier,
        onDismissRequest = { expanded = false },
        expandedAlignment = Alignment.TopCenter,
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colorScheme.surface,
        elevation = if (expanded) 8.dp else 0.dp,

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
                    text = document?.name ?: stringResource(R.string.common_state_loading),
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.document_info_action_expand),
                    modifier = Modifier.size(20.dp)
                )
            }
        },

        expandedContent = {
            Column(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 320.dp),
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
                        text = document?.name ?: stringResource(R.string.common_state_loading),
                        style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.document_info_action_collapse),
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
                        label = stringResource(R.string.document_info_label_created_date),
                        value = document?.createdAt?.let { dateFormat.format(Date(it)) } ?: stringResource(R.string.common_label_not_available)
                    )

                    InfoRow(
                        icon = Icons.Outlined.Edit,
                        label = stringResource(R.string.document_info_label_modified_date),
                        value = document?.modifiedAt?.let { dateFormat.format(Date(it)) } ?: stringResource(R.string.common_label_not_available)
                    )

                    InfoRow(
                        icon = Icons.Outlined.InsertPageBreak,
                        label = stringResource(R.string.document_info_label_pages),
                        value = totalPages.toString()
                    )

                    InfoRow(
                        icon = Icons.Outlined.AspectRatio,
                        label = stringResource(R.string.document_info_label_format),
                        value = dimensionsStr
                    )

                    InfoRow(
                        icon = Icons.Outlined.ShapeLine,
                        label = stringResource(R.string.document_info_label_elements_count),
                        value = totalElements.toString()
                    )
                }
            }
        }
    )
}

/**
 * A private helper composable that renders a standardized row of information within the expanded card.
 *
 * It formats a specific piece of document metadata by pairing an identifying vector icon and label
 * with the corresponding value string.
 *
 * @param icon The [ImageVector] representing the graphical icon to display at the start of the row.
 * @param label The descriptive text label for the given data point.
 * @param value The string representation of the data value to display at the end of the row.
 */
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