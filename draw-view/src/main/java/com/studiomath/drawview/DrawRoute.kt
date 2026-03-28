//package com.studiomath.drawview
//
//import android.app.Application
//import android.view.ViewConfiguration
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.remember
//import androidx.compose.ui.platform.LocalContext
//import androidx.ink.authoring.InProgressStrokesView
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.ViewModelProvider
//import com.studiomath.drawview.document.DrawViewModel
//import androidx.lifecycle.viewmodel.compose.viewModel
//
//@Composable
//fun DrawRoute(
//    documentId: Int, // Riceviamo l'ID del documento da aprire
//    onNavigateBack: () -> Unit
//) {
//    val context = LocalContext.current
//    val application = context.applicationContext as Application
//    val displayMetrics = context.resources.displayMetrics
//    val configuration = ViewConfiguration.get(context)
//
//    // 1. Creiamo il ViewModel usando Compose (non più l'Activity)
//    val factory = remember(application, documentId) {
//        object : ViewModelProvider.Factory {
//            @Suppress("UNCHECKED_CAST")
//            override fun <T : ViewModel> create(modelClass: Class<T>): T {
//                return DrawViewModel(
//                    application = application,
//                    documentId = documentId,
//                    displayMetrics = displayMetrics,
//                    configuration = configuration
//                ) as T
//            }
//        }
//    }
//    val drawViewModel: DrawViewModel = viewModel(factory = factory)
//
//    // 2. Creiamo la View dell'inchiostro in modo sicuro per Compose
//    // Usiamo remember per non ricrearla ad ogni ricomposizione grafica
//    val inProgressStrokesView = remember(context) {
//        InProgressStrokesView(context).apply {
//            addFinishedStrokesListener(drawViewModel.drawManager.inkStrokeProcessor)
//            eagerInit()
//        }
//    }
//
//    // 3. Mostriamo l'interfaccia
//    DrawScreen(
//        drawViewModel = drawViewModel,
//        inProgressStrokesView = inProgressStrokesView,
//        onNavigateBack = onNavigateBack
//    )
//}