package id.motionlab.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import id.motionlab.feature.MotionLabRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = (application as MotionLabApp).services
        setContent { MotionLabRoot(services) }
    }
}
