package com.studiomath.drawview

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.waterfall
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.studiomath.drawview.document.DrawComponent
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.DrawViewModel.ToolUtilities
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.ui.composeComponents.ColorWheel
import com.studiomath.drawview.ui.composeComponents.SizeSlider
import com.studiomath.drawview.ui.theme.DrawViewTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DrawActivity : ComponentActivity() {
    private lateinit var inProgressStrokesView: InProgressStrokesView
    private lateinit var drawViewModel: DrawViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Get the WindowInsetsControllerCompat
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // Configure behavior and visibility
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        val intent = intent
        val filePath = intent.getStringExtra("filePath")

        drawViewModel = viewModels<DrawViewModel> {
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

        inProgressStrokesView = InProgressStrokesView(this)
        inProgressStrokesView.addFinishedStrokesListener(drawViewModel.drawManager)
        inProgressStrokesView.eagerInit()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            DrawViewTheme {
                DrawActivity(drawViewModel = drawViewModel, inProgressStrokesView = inProgressStrokesView)
            }
        }

        drawViewModel.finishActivity = { finish() }
//        /**
//         * Impedisco all'utente di abbandonare l'activity con il tasto back
//         */
//        val callback: OnBackPressedCallback = object : OnBackPressedCallback(
//            true // default to enabled
//        ) {
//            override fun handleOnBackPressed() {
//                isEnabled = false
//                toast = Toast.makeText(
//                    this@DrawActivity,
//                    "Tap back button in order to exit",
//                    Toast.LENGTH_SHORT
//                )
//                toast.show()
//
//                CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
//                    delay(1500)
//                    isEnabled = true
//                }
//            }
//        }
//        onBackPressedDispatcher.addCallback(
//            this,  // LifecycleOwner
//            callback
//        )
    }

//    /**
//     * Elimino il toast visualizzato quando l'activity viene distrutta
//     */
//    lateinit var toast: Toast
//    override fun onDestroy() {
//        super.onDestroy()
//        if (::toast.isInitialized) {
//            toast.cancel()
//        }
//    }

    override fun onPause() {
        super.onPause()

        drawViewModel.data.saveDocument()
    }

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun DrawActivity(
    modifier: Modifier = Modifier,
    drawViewModel: DrawViewModel,
    inProgressStrokesView: InProgressStrokesView
) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .windowInsetsPadding(WindowInsets.waterfall)
    ) {
        Surface(
            modifier = Modifier
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
                    ) {
                        ToolButton(
                            onClick = {
                                drawViewModel.finishActivity?.let { it() }
                            }
                        ){
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                        ToolButton{
                            Icon(
                                imageVector = Icons.Outlined.GridView,
                                contentDescription = "Grid View",
                            )
                        }
                    }

                    Row (
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 4.dp)
                            .height(36.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(
                            modifier = Modifier,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 1.dp),
                            onClick = {}
                        ) {
                            Text(
                                modifier = Modifier
                                    .padding(end = 8.dp),
                                text = "documento di prova",
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
                    ) {
                        ToolButton{
                            Icon(
                                imageVector = Icons.Outlined.Draw,
                                contentDescription = "Draw",
                            )
                        }
                        ToolButton{
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = "More options",
                            )
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton{
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = "Undo",
                        )
                    }
                    ToolButton{
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Redo,
                            contentDescription = "Redo",
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .padding(8.dp),
                        thickness = 2.dp
                    )


                    var selectedTool by remember { mutableStateOf(ToolUtilities.Tool.INK_PEN) }

                    var penSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (selectedTool == ToolUtilities.Tool.INK_PEN){
                                penSettingsExpanded = true
                            } else {
                                drawViewModel.activeBrush = drawViewModel.penTool.getBrush(0)
                                selectedTool = ToolUtilities.Tool.INK_PEN
                            }
                        },
                        onLongClick = {
                            drawViewModel.activeBrush = drawViewModel.penTool.getBrush(0)
                            selectedTool = ToolUtilities.Tool.INK_PEN
                            penSettingsExpanded = true
                        },
                        selected = selectedTool == ToolUtilities.Tool.INK_PEN,
                        dropDownMenu = {
                            var size by remember { mutableFloatStateOf(drawViewModel.activeBrush.size) }
                            ColorWheel(
                                color = Color(drawViewModel.activeBrush.colorIntArgb),
                                onColorChanged = {
                                    drawViewModel.activeBrush = drawViewModel.activeBrush.copyWithColorIntArgb(
                                        colorIntArgb = it.toArgb()
                                    )
                                }
                            )

                            SizeSlider(
                                size = size.pt,
                                onSizeChanged = {
                                    size = it.pt
                                    drawViewModel.activeBrush = drawViewModel.activeBrush.copy(
                                        size = it.pt
                                    )
                                }
                            )
                        },
                        expanded = penSettingsExpanded,
                        onDismissRequest = { penSettingsExpanded = false }
                    ){
                        Icon(
                            painter = painterResource(id = R.drawable.icon_ink_pen),
                            contentDescription = "Grid View",
                        )
                    }

                    var highlighterSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (selectedTool == ToolUtilities.Tool.INK_HIGHLIGHTER){
                                highlighterSettingsExpanded = true
                            } else {
                                drawViewModel.activeBrush = drawViewModel.highlighterTool.getBrush(0)
                                selectedTool = ToolUtilities.Tool.INK_HIGHLIGHTER
                            }
                        },
                        onLongClick = {
                            drawViewModel.activeBrush = drawViewModel.highlighterTool.getBrush(0)
                            selectedTool = ToolUtilities.Tool.INK_HIGHLIGHTER
                            penSettingsExpanded = true
                        },
                        selected = selectedTool == ToolUtilities.Tool.INK_HIGHLIGHTER,
                        dropDownMenu = {
                            var size by remember { mutableFloatStateOf(drawViewModel.activeBrush.size) }
                            ColorWheel(
                                color = Color(drawViewModel.activeBrush.colorIntArgb),
                                onColorChanged = {
                                    drawViewModel.activeBrush = drawViewModel.activeBrush.copyWithColorIntArgb(
                                        colorIntArgb = it.toArgb()
                                    )
                                }
                            )

                            SizeSlider(
                                size = size.pt,
                                onSizeChanged = {
                                    size = it.pt
                                    drawViewModel.activeBrush = drawViewModel.activeBrush.copy(
                                        size = it.pt
                                    )
                                }
                            )
                        },
                        expanded = highlighterSettingsExpanded,
                        onDismissRequest = { highlighterSettingsExpanded = false }
                    ){
                        Icon(
                            painter = painterResource(id = R.drawable.icon_ink_highlighter),
                            contentDescription = "Grid View",
                        )
                    }

                    ToolButton(){
                        Icon(
                            painter = painterResource(id = R.drawable.icon_ink_eraser),
                            contentDescription = "Eraser",
                        )
                    }
                    ToolButton(){
                        Icon(
                            painter = painterResource(id = R.drawable.icon_text_fields),
                            contentDescription = "Text field",
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .padding(8.dp),
                        thickness = 2.dp
                    )


                }

                HorizontalDivider()
            }


        }


        DrawComponent(
            drawViewModel = drawViewModel,
            inProgressStrokesView = inProgressStrokesView
        )
    }

}


@OptIn(ExperimentalFoundationApi::class)
@Preview
@Composable
fun ToolButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit) = {},
    selected: Boolean = false,
    enabled: Boolean = true,
    dropDownMenu: @Composable() () -> Unit = {},
    expanded: Boolean = false,
    onDismissRequest: () -> Unit = {},
    content: @Composable() RowScope.() -> Unit = {}
){
    Box{
        val selectedModifier = if (selected) {
            modifier.background(MaterialTheme.colorScheme.primaryContainer)
        } else {
            modifier
        }
        Row (
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { onClick() },
                    onLongClick = { onLongClick() },
                    enabled = enabled,
                    role = Role.Button,
                )
                .then(selectedModifier)
                .padding(8.dp),
        ){
            content()
        }
        DropdownMenu(
            modifier = Modifier
                .width(300.dp),
            expanded = expanded,
            onDismissRequest = { onDismissRequest() }
        ) {
            dropDownMenu()
        }

    }

}