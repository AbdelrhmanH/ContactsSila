package app.sila.di

import app.sila.core.model.AppLanguage
import app.sila.feature.settings.domain.AppLocalePort

/**
 * تنفيذ iOS لـ [AppLocalePort] (S2-14 — تذكرة #68): كائن سلبي — لا per-app locales
 * على هذه المنصة أصلاً؛ شاشات iOS الأصلية يتماشى نصوصها مع `preferences.language`
 * عبر (SettingsLocalizedStrings(language:)) أولاً فلا يلزم تطبيقٌ إضافي.
 */
internal object IosAppLocalePort : AppLocalePort {

    override suspend fun applyApplicationLocale(language: AppLanguage) = Unit
}
