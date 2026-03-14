package com.studiomath.drawview.document

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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

    Box {
        // UPDATE: Accessing state directly from the ViewModel instead of the old data object
        if (!drawViewModel.isDocumentLoaded || !drawViewModel.isDocumentShowed) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth(),
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
            modifier = Modifier
                .fillMaxSize(),
            factory = { context ->
                val rootView = FrameLayout(context)
                inProgressStrokesView.apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
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
    }
}