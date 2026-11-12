package app.sila.di

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * تنفيذ أندرويد (إصلاح عيب DEF-02 — تذكرة #97): لغة الموارد الفعلية هي per-app
 * locale إن وُجد — منصة 33+ عبر `LocaleManager`، ومنصة 26–32 عبر مخزن appcompat
 * (`autoStoreLocales` في Manifest) — وإلا لغة النظام. تعثر قراءة appcompat يبقى
 * صامتاً (نمط منفذ `AppLocalePort`) ويعود الحسم إلى لغة النظام.
 */
actual fun platformResourceLanguageCode(): String? =
    runCatching {
        AppCompatDelegate.getApplicationLocales().get(0)?.language
    }.getOrNull() ?: Locale.getDefault().language
