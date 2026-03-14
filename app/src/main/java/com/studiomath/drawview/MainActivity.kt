package com.studiomath.drawview

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.ui.theme.DrawViewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DrawViewTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier) {
    val mContext = LocalContext.current

    Column(modifier = modifier) {
        Spacer(
            Modifier.height(100.dp)
        )
        TextButton(
            onClick = {
                val intent = Intent(mContext, DrawActivity::class.java)

                // UPDATE: We no longer pass the JSON filePath.
                // We pass a documentId. Passing -1 tells the DrawViewModel
                // to create a brand new default document in the Room database.
                intent.putExtra("documentId", 1)

                mContext.startActivity(intent)
            }
        ) {
            Text(text = "Create New Document (DrawView)")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DrawViewTheme {
        Greeting()
    }
}