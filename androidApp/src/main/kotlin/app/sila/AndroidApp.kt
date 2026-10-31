package app.sila

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sila.core.model.ContactSwipeAction
import app.sila.di.ensureKoinStarted
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState
import app.sila.feature.contacts.presentation.ContactsEffect
import app.sila.feature.contacts.presentation.ContactsIntent
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.contacts.presentation.onboarding.OnboardingEffect
import app.sila.feature.contacts.presentation.onboarding.OnboardingIntent
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel

/**
 * غلاف أندرويد للواجهة المشتركة (Issue #26 — T5 · مصفوفة الأذونات — Issue #20):
 * الجسر الوحيد بين آثار المنصة وسيستم أندرويد:
 * - [OnboardingViewModel]: طلب `READ_CONTACTS` وفتح إعدادات التطبيق (T5).
 * - [ContactsViewModel] (T3 — Issue #24): الاتصال (`ACTION_DIAL`)، الرسائل
 *   (`ACTION_SENDTO`)، مشاركة النص العادي، إعدادات التطبيق، ومنتقي الأرقام
 *   (حوار محلي — لا منطق أعمال هنا، مجرد تنفيذ أثر المنصة).
 *
 * **جامع واحد لكل قناة آثار** (Channel = مستهلك واحد): كل آثار ViewModel تُجمَع
 * هنا حصرياً — الشاشات/الغلاف المشترك لا تجمعها إطلاقاً حتى لا تُسرق الآثار.
 */
@Composable
fun SilaAndroidApp() {
    val koin = remember { ensureKoinStarted(storageDirPath = null) }
    val onboardingViewModel = remember(koin) { koin.get<OnboardingViewModel>() }
    val contactsViewModel = remember(koin) { koin.get<ContactsViewModel>() }
    val activity = LocalContext.current as? Activity
    val contactsStrings = rememberContactsStrings()
    var numberPicker by remember { mutableStateOf<ContactsEffect.ShowNumberPicker?>(null) }

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

    LaunchedEffect(contactsViewModel) {
        contactsViewModel.effects.collect { effect ->
            when (effect) {
                is ContactsEffect.OpenDialer -> activity?.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(effect.e164)}"))
                )

                is ContactsEffect.OpenSms -> activity?.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(effect.e164)}"))
                )

                is ContactsEffect.ShareContacts -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, effect.text)
                    }
                    activity?.startActivity(Intent.createChooser(send, null))
                }

                ContactsEffect.OpenAppSettings -> activity?.openAppSettings()

                is ContactsEffect.ShowNumberPicker -> numberPicker = effect
            }
        }
    }

    numberPicker?.let { picker ->
        AlertDialog(
            onDismissRequest = { numberPicker = null },
            title = { Text(contactsStrings.numberPickerTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    picker.phones.forEach { phone ->
                        TextButton(
                            onClick = {
                                numberPicker = null
                                contactsViewModel.onIntent(
                                    when (picker.action) {
                                        ContactSwipeAction.CALL ->
                                            ContactsIntent.CallNumber(picker.contactId, phone.e164)

                                        ContactSwipeAction.MESSAGE ->
                                            ContactsIntent.Message(picker.contactId, phone.e164)
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = phone.e164 + if (phone.isPrimary) " ★" else "")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { numberPicker = null }) { Text(contactsStrings.cancel) }
            }
        )
    }

    App()
}

/**
 * "رفض مع إمكانية إعادة الطلب": بعد رفضٍ ما تظهر لوحة الشرح، ما لم يفعّل المستخدم
 * "لا تسأل مرة أخرى" (عندها تكون rationale مخفية والنظام قفل الطلب نهائياً).
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
