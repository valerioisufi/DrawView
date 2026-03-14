package com.studiomath.drawview.document

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.motion.OnTouchHover

@Composable
fun DrawComponent(
    drawViewModel: DrawViewModel,
    inProgressStrokesView: InProgressStrokesView
){
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()

    Box(modifier = Modifier.fillMaxSize()) {

        if (!drawViewModel.isDocumentLoaded || !drawViewModel.isDocumentShowed) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        AndroidView(
            modifier = Modifier
                .systemGestureExclusion()
                .fillMaxSize(),
            factory = { context ->
                DrawView(context = context, drawViewModel = drawViewModel)
            }
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val rootView = FrameLayout(context)
                inProgressStrokesView.apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }

                // Wire up the Ink library callbacks to the ViewModel
                drawViewModel.startStrokeInProgress = { event, pointerId, brush ->
                    inProgressStrokesView.startStroke(event, pointerId, brush)
                }
                drawViewModel.addToStrokeInProgress = { event, pointerId, strokeId, predictedEvent ->
                    inProgressStrokesView.addToStroke(event, pointerId, strokeId, predictedEvent)
                }
                drawViewModel.finishStrokeInProgress = { event, pointerId, strokeId ->
                    inProgressStrokesView.finishStroke(event, pointerId, strokeId)
                }
                drawViewModel.cancelStrokeInProgress = { strokeId, event ->
                    inProgressStrokesView.cancelStroke(strokeId, event)
                }
                drawViewModel.removeFinishedStrokes = { strokeKeys ->
                    inProgressStrokesView.removeFinishedStrokes(strokeKeys)
                }
                drawViewModel.maskPath = { path ->
                    inProgressStrokesView.maskPath = path
                }

                /**
                 * Set up the touch and hover listeners for the view
                 */
                val onTouchHover = OnTouchHover(drawViewModel)
                onTouchHover.motionEventPredictor = MotionEventPredictor.newInstance(rootView)
                rootView.setOnTouchListener(onTouchHover.onTouchListener)
                rootView.setOnHoverListener(onTouchHover.onHoverListener)

                rootView.addView(inProgressStrokesView)
                rootView
            }
        )

        // --- FLOATING MENU (Contextual Action Bar) ---
        AnimatedVisibility(
            visible = drawViewModel.currentSelection != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -40 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -40 }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ElevatedCard(
                modifier = Modifier.padding(top = 24.dp),
                shape = RoundedCornerShape(50),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    IconButton(onClick = { drawViewModel.cutSelection() }) {
                        Icon(Icons.Default.ContentCut, contentDescription = "Taglia", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { drawViewModel.copySelection() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copia", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { drawViewModel.deleteSelection() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}