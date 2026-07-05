package cn.phonepad

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import cn.phonepad.ui.PhonePadApp

class MainActivity : ComponentActivity() {
    private lateinit var connectionManager: ConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        connectionManager = ConnectionManager(applicationContext, lifecycleScope)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PhonePadApp(connectionManager = connectionManager)
            }
        }
    }

    override fun onDestroy() {
        connectionManager.release()
        super.onDestroy()
    }
}
