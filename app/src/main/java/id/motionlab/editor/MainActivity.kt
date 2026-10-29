package id.motionlab.editor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import id.motionlab.editor.ui.EditorScreen
import id.motionlab.editor.ui.EditorViewModel
import id.motionlab.editor.ui.MotionLabTheme
import id.motionlab.editor.ui.screens.HomeScreen

enum class AppDestination { HOME, EDITOR }

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MotionLabTheme {
                var destination by remember { mutableStateOf(AppDestination.HOME) }

                when (destination) {
                    AppDestination.HOME -> {
                        HomeScreen(
                            vm = viewModel,
                            onOpenEditor = { destination = AppDestination.EDITOR }
                        )
                    }
                    AppDestination.EDITOR -> {
                        EditorScreen(
                            vm = viewModel,
                            onNavigateBack = {
                                viewModel.pause()
                                viewModel.flushSave()
                                destination = AppDestination.HOME
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        viewModel.pause()
        viewModel.flushSave()
        super.onStop()
    }
}
