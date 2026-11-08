package app.sila

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.sila.feature.contacts.presentation.ContactDetailsEffect
import app.sila.feature.contacts.presentation.ContactDetailsScreen
import app.sila.feature.contacts.presentation.ContactDetailsStrings
import app.sila.feature.contacts.presentation.ContactDetailsViewModel
import app.sila.feature.contacts.presentation.ContactEditEffect
import app.sila.feature.contacts.presentation.ContactEditIntent
import app.sila.feature.contacts.presentation.ContactEditScreen
import app.sila.feature.contacts.presentation.ContactEditStrings
import app.sila.feature.contacts.presentation.ContactEditViewModel
import app.sila.feature.contacts.presentation.ContactsScreen
import app.sila.feature.contacts.presentation.ContactsStrings
import app.sila.feature.contacts.presentation.ContactsViewModel
import app.sila.feature.contacts.presentation.duplicates.DuplicateReviewErrorKey
import app.sila.feature.contacts.presentation.duplicates.DuplicatesReviewEffect
import app.sila.feature.contacts.presentation.duplicates.DuplicatesReviewScreen
import app.sila.feature.contacts.presentation.duplicates.DuplicatesReviewStrings
import app.sila.feature.contacts.presentation.duplicates.DuplicatesReviewViewModel
import app.sila.feature.contacts.presentation.duplicates.SmartFilterReviewEffect
import app.sila.feature.contacts.presentation.duplicates.SmartFilterReviewErrorKey
import app.sila.feature.contacts.presentation.duplicates.SmartFilterReviewScreen
import app.sila.feature.contacts.presentation.duplicates.SmartFilterReviewStrings
import app.sila.feature.contacts.presentation.duplicates.SmartFilterReviewViewModel
import contactssila.sharedui.generated.resources.Res
import contactssila.sharedui.generated.resources.details_delete_error
import contactssila.sharedui.generated.resources.dup_error_merge
import contactssila.sharedui.generated.resources.dup_error_undo
import contactssila.sharedui.generated.resources.edit_save_error
import contactssila.sharedui.generated.resources.sf_error_apply
import contactssila.sharedui.generated.resources.sf_error_not_found
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * مسارات تبويب جهات الاتصال (T6 · Issue #27 · J-2 — قرار التصميم):
 *
 * أول NavHost في المشروع — يعيش **داخل خانة تبويب جهات الاتصال** في [App] فلكل
 * تبويب مكدس رجوع مستقل (ADR-0005: مسارات type-safe عبر `@Serializable`).
 * التنقل تأثير جانبي للـ ViewModels (NavigateBack/NavigateToEdit) يُجمَع هنا —
 * جامع واحد لكل قناة آثار (قاعدة Channel = مستهلك واحد)، وآثار **المنصة**
 * (اتصال/بريد/مشاركة/منتقي صور…) تُحوَّل لغلاف المضيف عبر [ContactsNavEffect]
 * دون أن يعرف المضيف شيئاً عن تفاصيل الـ ViewModels.
 */

/** جذر مكدس التبويب — قائمة جهات الاتصال (T3). */
@Serializable
data object ContactsListRoute

/** شاشة تفاصيل جهة اتصال محددة. */
@Serializable
data class ContactDetailsRoute(val contactId: Long)

/** شاشة الإضافة/التعديل — [contactId] = `null` نموذج إنشاء فارغ. */
@Serializable
data class ContactEditRoute(val contactId: Long? = null)

/** شاشة مراجعة المكررات (T7 · Issue #28) — كشف حيّ ودمج/تراجع و«ليست مكررات». */
@Serializable
data object DuplicatesReviewRoute

/** شاشة مراجعة الفلترة الذكية (T7 · Issue #28) — اقتراحات القواعد والتطبيق المؤكد. */
@Serializable
data object SmartFilterReviewRoute

/**
 * الآثار المنصوية الوحيدة التي تُسلَّم لغلاف المضيف (`androidApp`) — التنقل لا يسكن
 * هنا (ملك [ContactsNavFlow] حصراً). آثار التقاط الصورة تحمل مَ يومِع النتيجة
 * (البايتات تعود عبر [ContactEditIntent.SetPhotoPicked] — عقد VM الثابت).
 */
sealed interface ContactsNavEffect {
    data class OpenDialer(val e164: String) : ContactsNavEffect
    data class OpenSms(val e164: String) : ContactsNavEffect
    data class OpenEmail(val address: String) : ContactsNavEffect
    data class OpenUrl(val url: String) : ContactsNavEffect

    /** فتح تطبيق متصل — [scheme] مخطط مغلق (`whatsapp`/`tg`/`sgnl`). */
    data class OpenExternalApp(val scheme: String, val e164: String) : ContactsNavEffect

    /**
     * بدء رنين الطوارئ (T8-11) — [contactId] لجهة [app.sila.core.model.Category.EMERGENCY]
     * بعد تحذيرها؛ المضيف يملك بوابة `CALL_PHONE` وتنفيذ استخدام أرحام (فصل الميزات).
     */
    data class EmergencyRing(val contactId: Long) : ContactsNavEffect
    data class ShareVCard(val vcardText: String) : ContactsNavEffect

    /**
     * رسالة خطأ مُترجمة جاهزة للعرض (Toast) — النص يُحلّ من موارد الترجمة في
     * التركيب المشترك حصراً (كائن `Res` الداخلي لا يُصدَّر للمضيف)، والمضيف
     * ينفّذ العرض فقط بلا نصوص ثابتة في كوده (AGENTS.md).
     */
    data class ShowError(val message: String) : ContactsNavEffect

    /** التقاط صورة بالكاميرا — يُستدعى [onPicked] بالبايتات المعالجة أو `null` إن أُلغي. */
    data class PickFromCamera(val onPicked: (ByteArray?) -> Unit) : ContactsNavEffect

    /** اختيار صورة من المعرض — يُستدعى [onPicked] بالبايتات أو `null` إن أُلغي. */
    data class PickFromGallery(val onPicked: (ByteArray?) -> Unit) : ContactsNavEffect
}

/**
 * مضيف التنقل لتبويب جهات الاتصال: القائمة (T3) ← التفاصيل ← التعديل.
 *
 * - [contactsViewModel] واحد قادم من [App] (نطاق Koin — القائمة لا تُعاد بناؤها
 *   مع كل مسار، وحالتها تبقى عبر التنقل داخل التبويب).
 * - ViewModels التفاصيل/التعديل تُنشأ لكل مدخل مكدس عبر `viewModel(initializer)`
 *   — تنجو من تغيّر التشكيل (دوران الشاشة) وتُستكمل تبعياتها من حاوية Koin نفسها
 *   التي يستعملها iOS (`app.sila.di.KoinModule`) لاتساق مصدر التبعيات الواحد.
 * - [navController] مرفوع إلى [App] (حياته خارج خانة التبويب) فلا يُفقد المكدس
 *   عند تبديل التبويبات.
 */
@Composable
fun ContactsNavFlow(
    koin: Koin,
    contactsViewModel: ContactsViewModel,
    contactsStrings: ContactsStrings,
    detailsStrings: ContactDetailsStrings,
    editStrings: ContactEditStrings,
    duplicatesStrings: DuplicatesReviewStrings,
    smartFilterStrings: SmartFilterReviewStrings,
    navController: NavHostController,
    onPlatformEffect: (ContactsNavEffect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // نصوص الأخطاء تُحلّ هنا (تركيب مشترك) وتُحمل في الأثر — المضيف لا يلمس الموارد.
    val deleteErrorText = stringResource(Res.string.details_delete_error)
    val saveErrorText = stringResource(Res.string.edit_save_error)
    val mergeErrorText = stringResource(Res.string.dup_error_merge)
    val undoMergeErrorText = stringResource(Res.string.dup_error_undo)
    val applyErrorText = stringResource(Res.string.sf_error_apply)
    val contactNotFoundText = stringResource(Res.string.sf_error_not_found)

    NavHost(
        navController = navController,
        startDestination = ContactsListRoute,
        modifier = modifier
    ) {

        composable<ContactsListRoute> {
            ContactsScreen(
                viewModel = contactsViewModel,
                strings = contactsStrings,
                onOpenContact = { contactId ->
                    navController.navigate(ContactDetailsRoute(contactId))
                },
                onOpenDuplicatesReview = {
                    navController.navigate(DuplicatesReviewRoute)
                },
                onOpenSmartFilterReview = {
                    navController.navigate(SmartFilterReviewRoute)
                }
            )
        }

        // شاشة مراجعة المكررات (T7 · Issue #28): VM لكل مدخل مكدس (نمط التفاصيل/التعديل)
        // — «دمج كل المؤكدة» وشريط التراجع يعيشان داخل الشاشة، والفعل الوحيد الجامع هنا
        // هو إشعارات الأخطاء المُترجمة وفتح عضو (تفاصيل) أو اتصاله (قرارات الغلاف).
        composable<DuplicatesReviewRoute> {
            val duplicatesViewModel: DuplicatesReviewViewModel = viewModel {
                DuplicatesReviewViewModel(
                    observeGroups = koin.get(),
                    mergeContacts = koin.get(),
                    undoMerge = koin.get(),
                    dismissGroup = koin.get(),
                )
            }

            DuplicatesReviewScreen(
                viewModel = duplicatesViewModel,
                strings = duplicatesStrings,
                onOpenContact = { contactId ->
                    navController.navigate(ContactDetailsRoute(contactId))
                },
                onDial = { e164 ->
                    onPlatformEffect(ContactsNavEffect.OpenDialer(e164))
                },
            )

            // جامع واحد لقناة آثار المراجعة.
            LaunchedEffect(duplicatesViewModel, onPlatformEffect) {
                duplicatesViewModel.effects.collect { effect ->
                    when (effect) {
                        DuplicatesReviewEffect.NavigateBack -> navController.popBackStack()

                        is DuplicatesReviewEffect.ShowError -> onPlatformEffect(
                            ContactsNavEffect.ShowError(
                                when (effect.messageKey) {
                                    DuplicateReviewErrorKey.MERGE_FAILED -> mergeErrorText
                                    DuplicateReviewErrorKey.UNDO_FAILED -> undoMergeErrorText
                                }
                            )
                        )

                        // إتمام الدمج/التراجع يُعرض داخل الشاشة (شريط التراجع/الحالة) —
                        // لا قرار تنقل هنا.
                        is DuplicatesReviewEffect.MergeCompleted -> Unit
                        DuplicatesReviewEffect.UndoCompleted -> Unit
                        DuplicatesReviewEffect.UndoFailed -> Unit
                    }
                }
            }
        }

        // شاشة مراجعة الفلترة الذكية (T7 · Issue #28): اقتراحات القواعد — HIGH محدد
        // مسبقاً داخل الـ VM، والتطبيق بعد تأكيد صريح حصراً (spec 04 §ب). الآثار الجامعة
        // هنا: الرجوع بعد نجاح التطبيق وإشعارات الأخطاء المُترجمة.
        composable<SmartFilterReviewRoute> {
            val smartFilterViewModel: SmartFilterReviewViewModel = viewModel {
                SmartFilterReviewViewModel(
                    observeSuggestions = koin.get(),
                    applySuggestions = koin.get(),
                    recordFeedback = koin.get(),
                    setExclude = koin.get(),
                )
            }

            SmartFilterReviewScreen(
                viewModel = smartFilterViewModel,
                strings = smartFilterStrings,
            )

            // جامع واحد لقناة آثار المراجعة.
            LaunchedEffect(smartFilterViewModel, onPlatformEffect) {
                smartFilterViewModel.effects.collect { effect ->
                    when (effect) {
                        SmartFilterReviewEffect.NavigateBack -> navController.popBackStack()

                        is SmartFilterReviewEffect.ShowError -> onPlatformEffect(
                            ContactsNavEffect.ShowError(
                                when (effect.messageKey) {
                                    SmartFilterReviewErrorKey.APPLY_FAILED -> applyErrorText
                                    SmartFilterReviewErrorKey.CONTACT_NOT_FOUND -> contactNotFoundText
                                }
                            )
                        )
                    }
                }
            }
        }

        composable<ContactDetailsRoute> { entry ->
            val route = entry.toRoute<ContactDetailsRoute>()
            val contactId = route.contactId
            val detailsViewModel: ContactDetailsViewModel = viewModel {
                ContactDetailsViewModel(
                    contactId = contactId,
                    observeDetails = koin.get(),
                    deleteWithUndo = koin.get(),
                    toggleFavorite = koin.get(),
                    changeCategory = koin.get(),
                    recordInAppCall = koin.get(),
                    connectedAppsDetector = koin.get(),
                    appClock = koin.get()
                )
            }

            ContactDetailsScreen(
                viewModel = detailsViewModel,
                strings = detailsStrings
            )

            // جامع واحد لقناة آثار التفاصيل — التنقل هنا والمنصة للمضيف.
            LaunchedEffect(detailsViewModel, onPlatformEffect) {
                detailsViewModel.effects.collect { effect ->
                    when (effect) {
                        ContactDetailsEffect.NavigateBack -> navController.popBackStack()
                        is ContactDetailsEffect.NavigateToEdit ->
                            navController.navigate(ContactEditRoute(effect.contactId))

                        is ContactDetailsEffect.OpenDialer ->
                            onPlatformEffect(ContactsNavEffect.OpenDialer(effect.e164))

                        is ContactDetailsEffect.OpenSms ->
                            onPlatformEffect(ContactsNavEffect.OpenSms(effect.e164))

                        is ContactDetailsEffect.OpenEmail ->
                            onPlatformEffect(ContactsNavEffect.OpenEmail(effect.address))

                        is ContactDetailsEffect.OpenUrl ->
                            onPlatformEffect(ContactsNavEffect.OpenUrl(effect.url))

                        is ContactDetailsEffect.OpenExternalApp ->
                            onPlatformEffect(ContactsNavEffect.OpenExternalApp(effect.scheme, effect.e164))

                        is ContactDetailsEffect.ShareVCard ->
                            onPlatformEffect(ContactsNavEffect.ShareVCard(effect.vcardText))

                        is ContactDetailsEffect.EmergencyRing ->
                            onPlatformEffect(ContactsNavEffect.EmergencyRing(effect.contactId))

                        ContactDetailsEffect.ShowDeleteError ->
                            onPlatformEffect(ContactsNavEffect.ShowError(deleteErrorText))
                    }
                }
            }
        }

        composable<ContactEditRoute> { entry ->
            val route = entry.toRoute<ContactEditRoute>()
            val editViewModel: ContactEditViewModel = viewModel {
                ContactEditViewModel(
                    contactId = route.contactId,
                    observeDetails = koin.get(),
                    saveContact = koin.get(),
                    createArhamOccasion = koin.get(),
                    findDuplicate = koin.get()
                )
            }

            ContactEditScreen(
                viewModel = editViewModel,
                strings = editStrings
            )

            // جامع واحد لقناة آثار التعديل.
            LaunchedEffect(editViewModel, onPlatformEffect) {
                editViewModel.effects.collect { effect ->
                    when (effect) {
                        ContactEditEffect.NavigateBack -> navController.popBackStack()
                        ContactEditEffect.ShowSaveError ->
                            onPlatformEffect(ContactsNavEffect.ShowError(saveErrorText))

                        ContactEditEffect.PickFromCamera ->
                            onPlatformEffect(
                                ContactsNavEffect.PickFromCamera { bytes ->
                                    // الإلغاء (null) لا يُرسل نية — الصورة تبقى كما كانت.
                                    if (bytes != null) {
                                        editViewModel.onIntent(ContactEditIntent.SetPhotoPicked(bytes))
                                    }
                                }
                            )

                        ContactEditEffect.PickFromGallery ->
                            onPlatformEffect(
                                ContactsNavEffect.PickFromGallery { bytes ->
                                    if (bytes != null) {
                                        editViewModel.onIntent(ContactEditIntent.SetPhotoPicked(bytes))
                                    }
                                }
                            )
                    }
                }
            }
        }
    }
}
