package com.studiomath.drawview

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.state.DrawEvent
import com.studiomath.drawview.ui.TileDrawScreen

@Composable
fun TileDrawRoute(
    documentId: Int // Pass the real ID here, or 0 to let the VM create a new one
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    // 1. Instantiate the ViewModel using the updated Factory
    val factory = remember(application, documentId) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DrawEngineViewModel(application, documentId) as T
            }
        }
    }
    val viewModel: DrawEngineViewModel = viewModel(factory = factory)

    // 2. Setup the Android System Picker for PDFs
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // FIRE THE EVENT! The UI doesn't know what happens next.
            viewModel.onEvent(DrawEvent.ImportPdf(uri))
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 3. Render the Tile Engine
            TileDrawScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )

            // 4. A temporary button to test the import
            FloatingActionButton(
                onClick = { pdfPickerLauncher.launch("application/pdf") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = "Import PDF"
                )
            }
        }
    }
}