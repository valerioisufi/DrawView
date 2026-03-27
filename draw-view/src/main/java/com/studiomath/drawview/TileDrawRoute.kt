package com.studiomath.drawview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.state.DrawEngineState
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.state.ToolState
import com.studiomath.drawview.document.tools.Tool
import com.studiomath.drawview.ui.TileDrawScreen

@Composable
fun TileDrawRoute() {
    Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background
    ) {
        // 1. Create a dummy initial state for testing
        val initialState = DrawEngineState(
            document = Document("document"), // Pass an empty/dummy document
            toolState = ToolState(
                selectedTool = Tool.PAN,
                toolPreferences = emptyMap() // Empty for now
            )
        )

        // 2. Instantiate the ViewModel using a Factory
        val viewModel: DrawEngineViewModel = viewModel(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DrawEngineViewModel(initialState) as T
                }
            }
        )

        // 3. Render the Composable Canvas!
        TileDrawScreen(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )
    }
}