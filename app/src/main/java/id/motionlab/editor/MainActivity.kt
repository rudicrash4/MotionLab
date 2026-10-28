package id.motionlab.editor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import id.motionlab.editor.ui.EditorScreen
import id.motionlab.editor.ui.EditorViewModel
import id.motionlab.editor.ui.MotionLabTheme

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
                EditorScreen(viewModel)
            }
        }
    }

    override fun onStop() {
        // Hentikan pemutaran saat aplikasi tidak terlihat (hemat baterai, lepas decoder).
        viewModel.pause()
        viewModel.flushSave()
        super.onStop()
    }
}
