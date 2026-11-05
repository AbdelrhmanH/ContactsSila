package app.sila.di

import org.koin.core.module.Module

/**
 * موديول منفذ حلقة رنين الطوارئ (T8-10): `expect`/`actual` بنمط T8-08/09 —
 * androidMain يربط [app.sila.feature.arham.domain.EmergencyRingingController]
 * الحقيقي فوق FGS، وiosMain فارغ (T8-13 BLOCKED — بلا تحقق Swift/SKIE على Windows).
 * يُضمَّن في `createSilaSharedModule` مع بقية موديولات المنصة.
 */
internal expect val platformArhamEmergencyModule: Module
