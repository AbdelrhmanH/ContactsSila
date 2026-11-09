# AGENTS.md — تعليمات العمل على مشروع صِلَة

هذا الملف موجّه لأي مطور أو وكيل ذكاء اصطناعي يعمل على الكود.

## قبل أي تعديل
1. اقرأ [docs/PRD.md](docs/PRD.md) والملف المختص بالميزة في [docs/features/](docs/features/).
2. التزم بـ [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) و [docs/CODING_GUIDELINES.md](docs/CODING_GUIDELINES.md).
3. أي قرار معماري جديد ← أنشئ ADR في [docs/adr/](docs/adr/).

## قواعد لا تُكسر
- **معمارية التقسيم الهجين (Layers inside Features — ADR-0007):** كل ميزة في موديول مستقل (`:feature:contacts`, `:feature:arham`, `:feature:services`, `:feature:settings`) ويحتوي داخله على حزم Clean Architecture الثلاث (`domain`, `data`, `presentation`). الميزات لا تعتمد على بعضها أبداً، بل تتشارك عبر `:core:model` و `:core:database`.
- **الخصوصية:** موديولات `:feature:contacts` و `:feature:arham` **لا تعتمد أبداً** على `:core:network` أو `:core:firebase`. لا ترفع أسماء أو أرقاماً شخصية أو سجل مكالمات للسيرفر.
- **Single Source of Truth:** الواجهة تقرأ من Room (`:core:database`) فقط. الشبكة تكتب في Room.
- **لا منطق أعمال في الواجهة:** Compose/SwiftUI تعرض `UiState` وترسل `Intent` فقط.
- **الكود المشترك أولاً:** أي منطق لا يحتاج API خاص بالنظام يُكتب في `shared/`.
- **expect/actual أو interfaces** لأي وصول لـ Contacts / CallLog / Notifications / Scheduler / Ringtone.
- **RTL أولاً:** كل شاشة تُختبر بالعربية والإنجليزية.
- **لا نصوص ثابتة في الكود:** كل النصوص في موارد الترجمة (`strings.xml` / `Localizable.xcstrings` أو Compose Resources).
- **لا أسرار في المستودع:** ملفات Firebase والمفاتيح عبر `local.properties` / CI secrets.
- **كل UseCase وكل ViewModel له اختبارات.**

## أوامر شائعة
```bash
./gradlew build
./gradlew allTests
./gradlew :shared:umbrella:assembleXCFramework   # لـ iOS
```

### detekt (تحليل ساكن — عبر CLI مثبت، لا توجد مهمة Gradle له)
لا توجد مهمة `detekt` في Gradle في هذا المستودع؛ يُشغَّل عبر سطر أوامر
مثبَّت الإصدار (خطوة CI في `.github/workflows/ci.yml` بلبمة SHA-256):

```bash
java -jar detekt-cli-2.0.0-alpha.6-all.jar \
  --config config/detekt/detekt.yml \
  --input . --excludes "**/build/**,**/generated/**" \
  --baseline config/detekt/baseline.xml
```

- **خط متدرج:** الديون القديمة مجمدة في `config/detekt/baseline.xml` — أي مخالفة
  جديدة تفشل فوراً؛ أصلِحها داخل الشيفرة ولا تُضِفها إلى الـ baseline.
- **أرضية الإعدادات (S2-17):** ملفات واجهة الإعدادات (`SettingsScreen/Strings/
  Sections/Dialogs/SectionPreviews/ContentDialogsPreviews.kt`) ممنوع أن تحمل أي دين تعقيد
  (LongMethod/CCM/TooManyFunctions) في الـ baseline إطلاقاً — خطوة CI تفحص ذلك ويجب ألا تنكسر.

## تعريف "منتهي" (Definition of Done)
- [ ] يلبي معايير القبول في ملف الميزة.
- [ ] اختبارات وحدة للـ UseCases والـ ViewModels + اختبار Room للـ DAOs الجديدة.
- [ ] يعمل بدون إنترنت (إن كانت الميزة محلية).
- [ ] حالات Loading / Empty / Error / Permission denied مُنفذة.
- [ ] RTL + Dark mode + أحجام خطوط كبيرة.
- [ ] Detekt (CLI المثبت) بلا مخالفات جديدة — الديون المجمدة في الـ baseline لا تزيد.
- [ ] تحديث `CHANGELOG.md` والتوثيق عند الحاجة.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (via the `gh` CLI). See [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md).

### Domain docs

Single-context layout: one `GLOSSARY.md` + `docs/adr/` at the repo root. See [`docs/agents/domain.md`](docs/agents/domain.md).
