package app.sila.di

import org.koin.core.module.Module

/**
 * موديول انعاش الودجت (T8-10 · D3): `expect`/`actual` بنمط T8-08/09 — androidMain يربط
 * جسر [app.sila.core.database.dao.ArhamDataChangeListener] (worker D3)، وiosMain فارغ
 * (ودجت iOS مسؤولية T8-13 — BLOCKED).
 */
internal expect val platformArhamWidgetModule: Module
