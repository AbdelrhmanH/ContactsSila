plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.skie) apply false
}

/**
 * مهمة فحص قيود المعمارية والخصوصية الصارمة (Architecture & Privacy Boundary Check).
 * متوافقة 100% مع Gradle Configuration Cache.
 */
tasks.register("checkArchitectureBoundaries") {
    group = "verification"
    description = "Enforces Clean Architecture layer dependencies and Sila privacy boundaries."
    val rootDir = layout.projectDirectory.asFile

    doLast {
        val violations = mutableListOf<String>()
        val featureNames = listOf("contacts", "arham", "services", "settings")
        val privateFeatures = setOf("contacts", "arham")

        for (feature in featureNames) {
            for (layer in listOf("domain", "data", "presentation")) {
                val buildFile = rootDir.resolve("feature/$feature/$layer/build.gradle.kts")
                if (!buildFile.exists()) {
                    violations += "Missing build.gradle.kts for :feature:$feature:$layer"
                    continue
                }
                val content = buildFile.readText()

                // القاعدة 1: الخصوصية الصارمة لـ contacts و arham
                if (feature in privateFeatures) {
                    if (content.contains(":core:network") || content.contains(":core:firebase") ||
                        content.contains("ktor") || content.contains("firebase")
                    ) {
                        violations += "PRIVACY VIOLATION: :feature:$feature:$layer must NEVER depend on network or firebase."
                    }
                }

                // القاعدة 2: منع الاعتماد المتبادل بين الميزات (Zero Feature-to-Feature Coupling)
                for (otherFeature in featureNames) {
                    if (otherFeature != feature && content.contains(":feature:$otherFeature")) {
                        violations += "COUPLING VIOLATION: :feature:$feature:$layer must NEVER depend on :feature:$otherFeature."
                    }
                }

                // القاعدة 3: نقاء طبقة domain
                if (layer == "domain") {
                    val forbiddenInDomain = listOf(
                        ":feature:$feature:data",
                        ":feature:$feature:presentation",
                        ":core:database",
                        ":core:datastore",
                        ":core:designsystem"
                    )
                    for (forbidden in forbiddenInDomain) {
                        if (content.contains(forbidden)) {
                            violations += "CLEAN ARCH VIOLATION: :feature:$feature:domain must NOT depend on $forbidden."
                        }
                    }
                }

                // القاعدة 4: عزل طبقة presentation عن طبقة data و database
                if (layer == "presentation") {
                    val forbiddenInPresentation = listOf(
                        ":feature:$feature:data",
                        ":core:database",
                        ":core:datastore"
                    )
                    for (forbidden in forbiddenInPresentation) {
                        if (content.contains(forbidden)) {
                            violations += "CLEAN ARCH VIOLATION: :feature:$feature:presentation must NOT depend on $forbidden."
                        }
                    }
                }
            }
        }

        // القاعدة 5: فحص ملفات الكود لمنع GlobalScope أو SharedPreferences أو استيرادات الشبكة في contacts/arham
        val sourceRoots = listOf("core", "feature", "sharedLogic", "sharedUI").map { rootDir.resolve(it) }
        for (root in sourceRoots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.contains("/commonTest/") }
                .forEach { ktFile ->
                    val text = ktFile.readText()
                    val path = ktFile.relativeTo(rootDir).invariantSeparatorsPath
                    if (Regex("""\bGlobalScope\b""").containsMatchIn(text)) {
                        violations += "CODING GUIDELINE VIOLATION: GlobalScope used in $path"
                    }
                    if (Regex("""\bSharedPreferences\b""").containsMatchIn(text)) {
                        violations += "SECURITY VIOLATION: SharedPreferences used in $path (use DataStore only)"
                    }
                    if ((path.startsWith("feature/contacts/") || path.startsWith("feature/arham/")) &&
                        (text.contains("import io.ktor") || text.contains("import com.google.firebase") || text.contains("import dev.gitlive.firebase"))
                    ) {
                        violations += "PRIVACY VIOLATION: Network/Firebase import found in local-only module file $path"
                    }
                    // القاعدة 6 (SOLID DIP): طبقة العرض (presentation / sharedUI) لا تستورد RepositoryImpl أو Dao مباشرة أبداً
                    // تستثنى مصادر اختبار المضيف (androidHostTest): اختبارات Koin الحقيقية تُصلح عناصرها عبر الـ Dao/الـ DB الفعلي،
                    // والقاعدة تستهدف كود الإنتاج (اتساقاً مع قاعدة Konsist 4b المحصورة بنطاق الإنتاج).
                    val isTestSourcePath = path.contains("/commonTest/") || path.contains("/androidHostTest/")
                    if (!isTestSourcePath &&
                        (path.contains("/presentation/") || path.startsWith("sharedUI/")) &&
                        (Regex("""\b\w+RepositoryImpl\b""").containsMatchIn(text) || Regex("""\b\w+Dao\b""").containsMatchIn(text))
                    ) {
                        violations += "SOLID DIP VIOLATION: Presentation/UI file $path references concrete RepositoryImpl or Dao directly."
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Architecture & Privacy Boundary Checks Failed (${violations.size} violations):\n" +
                    violations.joinToString("\n") { " - $it" }
            )
        } else {
            println("✅ All Clean Architecture & Privacy Boundaries verified across 19 modules (0 violations).")
        }
    }
}
