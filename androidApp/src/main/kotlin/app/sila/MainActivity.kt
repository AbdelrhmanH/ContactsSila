package app.sila

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.sila.di.ensureKoinStarted

/**
 * S2-14 (تذكرة #68): AppCompatActivity لا ComponentActivity — تطبيق لغة التطبيق
 * (per-app locales) على المنصات 26–32 لا يعمل إلا عبر مندوب appcompat، ومندوبه
 * يتعامل مع أنشطة AppCompatActivity حصراً. المنصة 33+ تطبّقها نظامياً بغضّ
 * النظر، و`setContent` (من compose-activity) يبقى صالحاً إذ AppCompatActivity
 * فرعٌ من androidx.activity.ComponentActivity.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ensureKoinStarted(storageDirPath = filesDir.absolutePath)
        setContent {
            SilaAndroidApp()
        }
    }
}
