package app.sila

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sila.core.designsystem.SilaTheme
import app.sila.core.model.AppThemeMode
import app.sila.core.model.ContactSwipeAction
import app.sila.di.ensureKoinStarted
import app.sila.feature.contacts.domain.onboarding.OnboardingPermissionState
import app.sila.feature.contacts.presentation.ContactsEffect
import app.sila.feature.contacts.presentation.ContactsIntent
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.contacts.presentation.onboarding.OnboardingEffect
import app.sila.feature.contacts.presentation.onboarding.OnboardingIntent
import app.sila.feature.contacts.presentation.onboarding.OnboardingViewModel
import app.sila.feature.main.MainViewModel
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * غلاف أندرويد للواجهة المشتركة (Issue #26 — T5 · مصفوفة الأذونات — Issue #20):
 * الجسر الوحيد بين آثار المنصة وسيستم أندرويد:
 * - [OnboardingViewModel]: طلب `READ_CONTACTS` وفتح إعدادات التطبيق (T5).
 * - [ContactsViewModel] (T3 — Issue #24): الاتصال (`ACTION_DIAL`)، الرسائل
 *   (`ACTION_SENDTO`)، مشاركة النص العادي، إعدادات التطبيق، ومنتقي الأرقام
 *   (حوار محلي — لا منطق أعمال هنا، مجرد تنفيذ أثر المنصة).
 * - [ContactsNavEffect] (T6 — Issue #27): تفاصيل/إضافة جهة الاتصال — اتصال/رسالة/
 *   بريد/رابط/تطبيق متصل، مشاركة vCard، إشعارات أخطاء الحذف/الحفظ، ومنتقيات
 *   الصورة (الكاميرا/المعرض)؛ إضافة لطلب `WRITE_CONTACTS` لمرة واحدة بعد التهيئة.
 *
 * **جامع واحد لكل قناة آثار** (Channel = مستهلك واحد): كل آثار ViewModel تُجمَع
 * هنا حصرياً (أو في [ContactsNavFlow] لقناتي التفاصيل/التعديل — التنقل ملكها
 * وما سوى التنقل يُحال إلى هنا) — الشاشات/الغلاف المشترك لا تجمعها إطلاقاً حتى
 * لا تُسرق الآثار.
 */
@Composable
fun SilaAndroidApp() {
    val koin = remember { ensureKoinStarted(storageDirPath = null) }
    val onboardingViewModel = remember(koin) { koin.get<OnboardingViewModel>() }
    val contactsViewModel = remember(koin) { koin.get<ContactsViewModel>() }
    val mainViewModel = remember(koin) { koin.get<MainViewModel>() }
    val mainState by mainViewModel.state.collectAsState()
    val activity = LocalContext.current as? Activity
    val context = LocalContext.current
    val contactsStrings = rememberContactsStrings()
    val scope = rememberCoroutineScope()
    var numberPicker by remember { mutableStateOf<ContactsEffect.ShowNumberPicker?>(null) }

    // T6 — منتقيات الصورة: النتيجة تعود للـ ViewModel عبر callback الأثر نفسه.
    var pendingPhotoPick by remember { mutableStateOf<((ByteArray?) -> Unit)?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { photo ->
        val onPicked = pendingPhotoPick
        pendingPhotoPick = null
        onPicked?.let { callback ->
            scope.launch {
                val bytes = photo?.let { withContext(Dispatchers.IO) { it.toJpegBytes() } }
                callback(bytes)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val onPicked = pendingPhotoPick
        pendingPhotoPick = null
        onPicked?.let { callback ->
            scope.launch {
                val bytes = uri?.let { target ->
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(target)?.use { stream ->
                            stream.readBytes()
                        }
                    }
                }
                callback(bytes)
            }
        }
    }

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

    // T6 — طلب WRITE_CONTACTS لمرة واحدة بعد اكتمال التهيئة (صلاحية أول كتابة كسولة):
    // إن رُفض، يفحص الكاتب الصلاحية لكل عملية ويُظهر الخطأ المترجم حينئذٍ.
    var writeContactsRequested by rememberSaveable { mutableStateOf(false) }
    val writeContactsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* النتيجة ليست جوهرية — آلية الفحص لكل عملية تكفي */ }

    LaunchedEffect(mainState.preferences.onboardingCompleted) {
        if (mainState.preferences.onboardingCompleted && !writeContactsRequested) {
            writeContactsRequested = true
            writeContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
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
                is ContactsEffect.OpenDialer -> activity?.openDialer(effect.e164)

                is ContactsEffect.OpenSms -> activity?.openSms(effect.e164)

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

    // T6 — جسر آثار المنصة لشاشات التفاصيل/الإضافة (جامع واحد لكل قناة آثار).
    val onContactsNavEffect: (ContactsNavEffect) -> Unit = { effect ->
        when (effect) {
            is ContactsNavEffect.OpenDialer -> activity?.openDialer(effect.e164)
            is ContactsNavEffect.OpenSms -> activity?.openSms(effect.e164)
            is ContactsNavEffect.OpenEmail -> activity?.openEmail(effect.address)
            is ContactsNavEffect.OpenUrl -> activity?.openUrl(effect.url)
            is ContactsNavEffect.OpenExternalApp -> activity?.openConnectedApp(effect.scheme, effect.e164)
            is ContactsNavEffect.ShareVCard -> activity?.shareText(effect.vcardText)
            is ContactsNavEffect.ShowError -> context.toast(effect.message)
            is ContactsNavEffect.PickFromCamera -> {
                pendingPhotoPick = effect.onPicked
                cameraLauncher.launch(null)
            }
            is ContactsNavEffect.PickFromGallery -> {
                pendingPhotoPick = effect.onPicked
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
    }

    numberPicker?.let { picker ->
        // داخل SilaTheme (مراجعة PR #36 — F5): الحوار يتألف خارج [App]، فلكي يرث
        // الوضع الداكن واتجاه RTL من تفضيلات المستخدم نفسها نلفه بنفس سمات السمة.
        SilaTheme(
            darkTheme = mainState.preferences.themeMode == AppThemeMode.DARK,
            isRtl = mainState.preferences.language.isRtl
        ) {
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
    }

    App(onContactsNavEffect = onContactsNavEffect)
}

/** رسالة قصيرة للغلاف — نصها من موارد الترجمة (مُحلولة في التركيب). */
private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private fun Activity.openDialer(e164: String) = startActivity(
    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(e164)}"))
)

private fun Activity.openSms(e164: String) = startActivity(
    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(e164)}"))
)

private fun Activity.openEmail(address: String) = startActivity(
    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(address)}"))
)

/** الرابط قد يكون رديئاً (مستخدم أدخله يدوياً) — الفشل هنا بلا انهيار. */
private fun Activity.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/**
 * فتح تطبيق متصل مراسلة برقم الجهة (T6): واتساب يدعم فتح محادثة عبر `wa.me`،
 * وتيليجرام/سيغنال بمخططاتهم — والفشل (اختفى التطبيق بين الكشف والفتح) يرتد
 * لمحاولة أخيرة بفتح التطبيق نفسه.
 */
private fun Activity.openConnectedApp(scheme: String, e164: String) {
    val digits = e164.filter { it.isDigit() }
    val uri = when (scheme) {
        "whatsapp" -> Uri.parse("https://wa.me/$digits")
        "tg" -> Uri.parse("tg://resolve?phone=$digits")
        "sgnl" -> Uri.parse("sgnl://")
        else -> Uri.parse("$scheme://")
    }
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure {
            packageManager.getLaunchIntentForPackage(connectedAppPackage(scheme))?.let(::startActivity)
        }
}

/** حزم التطبيقات المتصلة — نفس القائمة المغلقة في [app.sila.feature.contacts.data.AndroidConnectedAppsDetector]. */
private fun connectedAppPackage(scheme: String): String = when (scheme) {
    "whatsapp" -> "com.whatsapp"
    "tg" -> "org.telegram.messenger"
    "sgnl" -> "org.thoughtcrime.securesms"
    else -> scheme
}

/** مشاركة نص (vCard هذه الدورة — EXTRA_STREAM عبر FileProvider تحسين لاحق موثق). */
private fun Activity.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, null))
}

/** ترميز اللقطة JPEG — بايتات المعالج [app.sila.feature.contacts.data] تتوقع JPEG. */
private fun Bitmap.toJpegBytes(quality: Int = 90): ByteArray {
    val output = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, output)
    return output.toByteArray()
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
