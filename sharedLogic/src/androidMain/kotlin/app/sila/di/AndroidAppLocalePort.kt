package app.sila.di

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.sila.core.model.AppLanguage
import app.sila.feature.settings.domain.AppLocalePort

/**
 * تنفيذ أندرويد لـ [AppLocalePort] (S2-14 — تذكرة #68):
 * per-app locales عبر `AppCompatDelegate.setApplicationLocales` — على المنصة 33+
 * يفوّض الاستدعاء إلى `android.app.LocaleManager` حصراً، وعلى 26–32 يدير appcompat
 * السلوك ويخزّن الاختيار تلقائياً (خدمة `AppLocalesMetadataHolderService` بـ
 * `autoStoreLocales` في Manifest). ومن هنا تصير لغة المنصة الفعلية منبع
 * `Locale.current` في Compose Resources فتتبع الموارد كلها لا شاشة الإعدادات وحدها.
 *
 * استدعاء المندوب مُقيَّد بالخيط الرئيس (شرط من appcompat) — والتغليف بـ Handler
 * يضمن ذلك أياً كان خيط الاستدعاء.
 */
internal class AndroidAppLocalePort : AppLocalePort {

    override suspend fun applyApplicationLocale(language: AppLanguage) {
        Handler(Looper.getMainLooper()).post {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(language.code)
            )
        }
    }
}
