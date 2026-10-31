package app.sila

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.sila.di.ensureKoinStarted

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ensureKoinStarted(storageDirPath = filesDir.absolutePath)
        setContent {
            SilaAndroidApp()
        }
    }
}
