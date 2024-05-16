package com.studiomath.drawview

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewCozy
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.ViewCozy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.studiomath.drawview.ui.theme.DrawViewTheme

class DrawActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val filePath = intent.getStringExtra("filePath")

        val drawViewModel = viewModels<DrawViewModel> {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DrawViewModel(
                        filePath = "$filePath",
                        filesDir = filesDir,
                        displayMetrics = resources.displayMetrics
                    ) as T
                }
            }
        }.value

//        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
//            controller.hide(WindowInsetsCompat.Type.systemBars())
//            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            DrawViewTheme {
                DrawActivity(drawViewModel = drawViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawActivity(modifier: Modifier = Modifier, drawViewModel: DrawViewModel) {
    val activity = LocalContext.current as Activity
    Column(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize()
    ) {
        Surface(
            modifier = Modifier
//                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
        ) {
            Column(
                modifier = Modifier,
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                    ){
                        SmallButtonIcon(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            onClick = {
                                activity.finish()
                            }
                        )

                        SmallButtonIcon(
                            icon = Icons.Outlined.GridView,
                            onClick = {
                            }
                        )
                    }

                    TextButton(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .height(36.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 1.dp),
                        onClick = {}
                    ){
                        Row(
                            modifier = Modifier
                                .fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                modifier = Modifier
                                    .padding(end = 8.dp),
                                text = "drawViewModel",
                                style = MaterialTheme.typography.titleMedium,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Ordine",
                                modifier = Modifier
                                    .requiredSize(20.dp)
                            )
                        }

                    }

                    Row(
                        modifier = Modifier
                    ){
                        SmallButtonIcon(
                            icon = Icons.Outlined.Draw,
                            onClick = {
                            }
                        )
                        SmallButtonIcon(
                            icon = Icons.Outlined.MoreHoriz,
                            onClick = {
                            }
                        )

//                        IconButton(
//                            modifier = Modifier
//                            ,
//                            onClick = {
//                                activity.finish()
//                            }
//                        ) {
//                            Icon(
//                                imageVector = Icons.Outlined.Draw,
//                                contentDescription = "Localized description"
//                            )
//                        }
//                        IconButton(
//                            modifier = Modifier,
//                            onClick = { /*TODO*/ }
//                        ) {
//                            Icon(imageVector = Icons.Outlined.MoreHoriz, contentDescription = "")
//                        }
                    }
                }

                HorizontalDivider()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 4.dp),
//                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmallButtonIcon(
                            icon = Icons.AutoMirrored.Outlined.Undo,
                            onClick = {
                            }
                        )
                        SmallButtonIcon(
                            icon = Icons.AutoMirrored.Outlined.Redo,
                            onClick = {
                            }
                        )

                        VerticalDivider(
                            modifier = Modifier
                                .padding(8.dp),
                            thickness = 2.dp
                        )

                        SmallButtonIcon(
                            icon = painterResource(id = R.drawable.icon_ink_pen),
                            onClick = {
                            }
                        )
                        SmallButtonIcon(
                            icon = painterResource(id = R.drawable.icon_ink_highlighter),
                            onClick = {
                            }
                        )
                        SmallButtonIcon(
                            icon = painterResource(id = R.drawable.icon_ink_eraser),
                            onClick = {
                            }
                        )
                        SmallButtonIcon(
                            icon = painterResource(id = R.drawable.icon_text_fields),
                            onClick = {
                            }
                        )


                    }
                }

                HorizontalDivider()
            }


        }


        DrawCompose(
            drawViewModel = drawViewModel
        )
    }

}

@Composable
fun SmallButtonIcon(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String  = "Localized description"
){
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                onClick = { onClick() },
                role = Role.Button,
            )
            .padding(8.dp),
    )
}
@Composable
fun SmallButtonIcon(
    modifier: Modifier = Modifier,
    icon: Painter,
    onClick: () -> Unit,
    contentDescription: String  = "Localized description",
    enabled: Boolean = true
){
    Icon(
        painter = icon,
        contentDescription = contentDescription,
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(
                onClick = { onClick() },
                enabled = enabled,
                role = Role.Button,
            )
            .padding(8.dp),
    )
}