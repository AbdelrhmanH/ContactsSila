package app.sila

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.sila.di.ensureKoinStarted
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState
import app.sila.feature.contacts.presentation.onboarding.OnboardingEffect
import app.sila.feature.contacts.presentation.onboarding.OnboardingIntent
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel

/**
 * غلاف أندرويد للواجهة المشتركة (Issue #26 — T5 · مصفوفة الأذونات — Issue #20):
 * الجسر الوحيد بين Effect المنصّة من [OnboardingViewModel] وطلب `READ_CONTACTS` الفعلي:
 * - المنح ← [OnboardingIntent.PermissionResult] بحالة GRANTED.
 * - الرفض مع إمكانية إعادة الطلب (ظهور rationale) ← DENIED (إعادة محاولة صريحة).
 * - الرفض مع "لا تسأل مرة أخرى" (بلا rationale) ← DENIED_FOREVER؛ مساره الوحيد
 *   إعدادات التطبيق عبر `ACTION_APPLICATION_DETAILS_SETTINGS`، ولا يتذكَّر التطبيقُ
 *   الطلبَ تلقائياً أبداً (حد الطلبات ≤ 2 مضبوط أعلى في الـ ViewModel).
 */
@Composable
fun SilaAndroidApp() {
    val koin = remember { ensureKoinStarted(storageDirPath = null) }
    val onboardingViewModel = remember(koin) { koin.get<OnboardingViewModel>() }
    val activity = LocalContext.current as? Activity

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onboardingViewModel.onIntent(
            OnboardingIntent.PermissionResult(
                state = when {
                    granted -> OnboardingPermissionState.GRANTED
                    activity?.shouldShowReadContactsRationale() == true -> OnboardingPermissionState.DENIED
                    else -> OnboardingPermissionState.DENIED_FOREVER
                }
            )
        )
    }

    LaunchedEffect(onboardingViewModel) {
        onboardingViewModel.effects.collect { effect ->
            when (effect) {
                OnboardingEffect.RequestContactsPermission ->
                    requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)

                OnboardingEffect.OpenAppSettings -> activity?.openAppSettings()
            }
        }
    }

    App()
}

/**
 * "رفض مع إمكانية إعادة الطلب": بعد رفضٍ ما تظهر لوحة الشرح، ما لم يفعّل المستخدم
 * "لا تسأل مرة أخرى" (عندها تكون rationale مخفية والدنظام قفل الطلب نهائياً).
 */
private fun Activity.shouldShowReadContactsRationale(): Boolean =
    checkSelfPermission(Manifest.permission.READ_CONTACTS) == PERMISSION_DENIED &&
        shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)

private fun Activity.openAppSettings() = startActivity(
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    )
)
