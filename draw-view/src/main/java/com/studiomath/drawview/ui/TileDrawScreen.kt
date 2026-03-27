package com.studiomath.drawview.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.viewinterop.AndroidView
import com.studiomath.drawview.document.math.CoordinateTransformer
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.view.TileDrawView

/**
 * A Compose wrapper around the high-performance native TileDrawView.
 */
@Composable
fun TileDrawScreen(
    viewModel: DrawEngineViewModel,
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current

    // 1. Create a CoroutineScope tied to the Compose lifecycle.
    // If this Composable leaves the screen, the scope cancels safely.
    val coroutineScope = rememberCoroutineScope()

    // 2. Remember the CoordinateTransformer so it isn't recreated on every recomposition
    val coordinateTransformer = remember {
        CoordinateTransformer(resources.displayMetrics)
    }

    // 3. Bridge the native Android View into Compose
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            // This block runs EXACTLY ONCE when the view is created
            TileDrawView(ctx).apply {
                // Wire up the UDF Brain, the Math Engine, and the Coroutines
                attachEngine(
                    vm = viewModel,
                    transformer = coordinateTransformer,
                    scope = coroutineScope
                )
            }
        },
        update = { view ->
            // This block runs every time Compose recomposes.
            // We leave it empty because our View observes the StateFlow internally!
        }
    )
}