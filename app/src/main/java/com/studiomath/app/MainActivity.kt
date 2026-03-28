package com.studiomath.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.studiomath.app.ui.theme.DrawViewTheme
import com.studiomath.drawview.DocumentListRoute
import com.studiomath.drawview.TileDrawRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            DrawViewTheme {

//                DocumentListRoute(
//                    onNavigateToDocument = { documentId ->
//                        // È il progetto base a decidere COME navigare
//                        val intent = Intent(this, DrawActivity::class.java)
//                        intent.putExtra("documentId", documentId)
//                        startActivity(intent)
//                    }
//                )

                TileDrawRoute(1)

            }
        }
    }
}