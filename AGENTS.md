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
./gradlew detekt ktlintFormat
./gradlew :shared:umbrella:assembleXCFramework   # لـ iOS
```

## تعريف "منتهي" (Definition of Done)
- [ ] يلبي معايير القبول في ملف الميزة.
- [ ] اختبارات وحدة للـ UseCases والـ ViewModels + اختبار Room للـ DAOs الجديدة.
- [ ] يعمل بدون إنترنت (إن كانت الميزة محلية).
- [ ] حالات Loading / Empty / Error / Permission denied مُنفذة.
- [ ] RTL + Dark mode + أحجام خطوط كبيرة.
- [ ] Detekt و ktlint بلا أخطاء.
- [ ] تحديث `CHANGELOG.md` والتوثيق عند الحاجة.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (via the `gh` CLI). See [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md).

### Domain docs

Single-context layout: one `GLOSSARY.md` + `docs/adr/` at the repo root. See [`docs/agents/domain.md`](docs/agents/domain.md).
