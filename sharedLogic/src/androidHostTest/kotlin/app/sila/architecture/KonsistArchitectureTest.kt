package app.sila.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoInterfaceDeclaration
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.provider.KoFullyQualifiedNameProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.KoParentProvider
import com.lemonappdev.konsist.api.provider.KoResideInPackageProvider
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse as kotlinAssertFalse
import kotlin.test.assertTrue as kotlinAssertTrue

/**
 * اختبارات معمارية صِلَة باستخدام Konsist 0.18.1 (Phase 0 KR3 — Issue #6 & Issue #8).
 *
 * تفحص شجرة الصياغة المجردة (AST) لجميع ملفات الإنتاج عبر الموديولات وتعمل جنباً إلى جنب
 * مع مهمة Gradle `checkArchitectureBoundaries`.
 */
class KonsistArchitectureTest {

    private val productionScope = Konsist.scopeFromProduction()

    companion object {
        private val FEATURE_NAMES = listOf("contacts", "arham", "services", "settings")
        private val LAYER_NAMES = listOf("domain", "data", "presentation")
        private val PRIVATE_FEATURES = setOf("contacts", "arham")

        private const val MVI_VIEW_MODEL_FQN = "app.sila.core.common.MviViewModel"
        private const val BASE_MVI_VIEW_MODEL_FQN = "app.sila.core.common.BaseMviViewModel"
        private val TARGET_MVI_CONTRACT_FQNS = setOf(MVI_VIEW_MODEL_FQN, BASE_MVI_VIEW_MODEL_FQN)

        private val FORBIDDEN_PRIVACY_PACKAGE_PREFIXES = listOf(
            "io.ktor",
            "com.google.firebase",
            "dev.gitlive.firebase",
            "app.sila.core.network",
            "app.sila.core.firebase"
        )

        private val FORBIDDEN_DOMAIN_IMPORT_PREFIXES = listOf(
            "app.sila.core.database",
            "app.sila.core.datastore",
            "app.sila.core.designsystem",
            "androidx.compose",
            "androidx.room"
        ) + FORBIDDEN_PRIVACY_PACKAGE_PREFIXES

        private val FORBIDDEN_PRESENTATION_IMPORT_PREFIXES = listOf(
            "app.sila.core.database",
            "app.sila.core.datastore"
        )

        private val FEATURE_LAYER_PATH_REGEX =
            Regex("""(?:^|/)feature/([^/]+)/([^/]+)/src/[^/]+/kotlin/""")

        private val FEATURE_PACKAGE_REGEX =
            Regex("""^app\.sila\.feature\.([a-zA-Z0-9_]+)(?:\..+)?$""")

        private val FORBIDDEN_PRIVACY_FQN_REGEX = Regex(
            """\b(?:io\.ktor|com\.google\.firebase|dev\.gitlive\.firebase|app\.sila\.core\.network|app\.sila\.core\.firebase)\.[A-Za-z_]"""
        )

        private val FORBIDDEN_PRESENTATION_REF_REGEX = Regex(
            """\b[A-Za-z0-9_]*(?:RepositoryImpl|Dao)\b"""
        )
    }

    /**
     * فحص عدم الفراغ (Non-Vacuity Guard):
     * يضمن أن نطاق الإنتاج (productionScope) غير فارغ ويشمل جميع الموديولات والميزات والطبقات المقصودة،
     * ولا يقتصر على موديول واحد أو يتجاوز ملفات الإنتاج.
     */
    @Test
    fun `production scope is non-empty and includes all core shared and feature layer source sets`() {
        val files = productionScope.files
        kotlinAssertTrue(files.isNotEmpty(), "Konsist productionScope must not be empty.")

        val normalizedPaths = files.map { it.path.replace('\\', '/') }

        // Ensure Zero test files leaked into productionScope
        val leakedTestFiles = normalizedPaths.filter { path ->
            path.contains("/commonTest/") ||
                path.contains("/androidHostTest/") ||
                path.contains("/iosTest/") ||
                path.contains("/test/")
        }
        kotlinAssertTrue(
            leakedTestFiles.isEmpty(),
            "Production scope must not contain test files, but found: $leakedTestFiles"
        )

        // Ensure core modules are present
        val expectedCoreModules = listOf("common", "model", "database", "datastore", "designsystem")
        for (coreMod in expectedCoreModules) {
            kotlinAssertTrue(
                normalizedPaths.any { it.contains("/core/$coreMod/src/") },
                "Missing production files for :core:$coreMod in Konsist.scopeFromProduction()"
            )
        }

        // Ensure all 4 features x 3 layers are present
        for (feature in FEATURE_NAMES) {
            for (layer in LAYER_NAMES) {
                kotlinAssertTrue(
                    normalizedPaths.any { it.contains("/feature/$feature/$layer/src/") },
                    "Missing production files for :feature:$feature:$layer in Konsist.scopeFromProduction()"
                )
            }
        }

        // Ensure sharedLogic (commonMain, androidMain, iosMain) and sharedUI are present
        kotlinAssertTrue(
            normalizedPaths.any { it.contains("/sharedLogic/src/commonMain/") },
            "Missing :sharedLogic commonMain files in Konsist.scopeFromProduction()"
        )
        kotlinAssertTrue(
            normalizedPaths.any { it.contains("/sharedLogic/src/androidMain/") },
            "Missing :sharedLogic androidMain files in Konsist.scopeFromProduction()"
        )
        kotlinAssertTrue(
            normalizedPaths.any { it.contains("/sharedLogic/src/iosMain/") },
            "Missing :sharedLogic iosMain files in Konsist.scopeFromProduction()"
        )
        kotlinAssertTrue(
            normalizedPaths.any { it.contains("/sharedUI/src/commonMain/") },
            "Missing :sharedUI commonMain files in Konsist.scopeFromProduction()"
        )
    }

    /**
     * Rule 1 — Package-to-layer alignment:
     * كل تصريح إنتاجي داخل `feature/<feature>/<layer>` يجب أن يتبع حزمة `app.sila.feature.<feature>.<layer>(..)`.
     */
    @Test
    fun `rule 1 - declarations under feature layer directories align with feature and layer package hierarchy`() {
        val featureLayerFiles = productionScope.files.filter { file ->
            val normalizedPath = file.path.replace('\\', '/')
            normalizedPath.contains("/feature/") &&
                !normalizedPath.contains("/sharedLogic/") &&
                !normalizedPath.contains("/sharedUI/") &&
                !normalizedPath.contains("/core/") &&
                !normalizedPath.contains("/androidApp/")
        }
        kotlinAssertTrue(
            featureLayerFiles.isNotEmpty(),
            "Expected non-empty set of files under feature/<feature>/<layer> modules."
        )

        featureLayerFiles.assertTrue(
            additionalMessage = "Every production file and declaration under feature/<feature>/<layer> must reside in package app.sila.feature.<feature>.<layer>.."
        ) { file ->
            val normalizedPath = file.path.replace('\\', '/')
            val match = FEATURE_LAYER_PATH_REGEX.find(normalizedPath) ?: return@assertTrue false
            val feature = match.groupValues[1]
            val layer = match.groupValues[2]
            if (feature !in FEATURE_NAMES || layer !in LAYER_NAMES) {
                return@assertTrue false
            }
            val expectedPackage = "app.sila.feature.$feature.$layer"
            val actualPackage = file.packagee?.name ?: return@assertTrue false
            val fileMatches = actualPackage == expectedPackage || actualPackage.startsWith("$expectedPackage.")
            val packageAwareDeclarations = file.declarations(includeNested = false)
                .filterIsInstance<KoResideInPackageProvider>()
            val declarationsMatch = packageAwareDeclarations.isNotEmpty() &&
                packageAwareDeclarations.all { decl ->
                    decl.resideInPackage("$expectedPackage..")
                }
            fileMatches && declarationsMatch
        }
    }

    /**
     * Rule 2 — Privacy by architecture:
     * ملفات `app.sila.feature.contacts..` و `app.sila.feature.arham..` لا تستورد (استيراداً عادياً أو بنجمة أو باسم مستعار)
     * ولا تشير عبر اسم مؤهل بالكامل (FQN) إلى أي حزمة شبكية أو سحابية (`io.ktor..`, `com.google.firebase..`,
     * `dev.gitlive.firebase..`, `app.sila.core.network..`, `app.sila.core.firebase..`).
     */
    @Test
    fun `rule 2 - contacts and arham features never import or reference network or firebase packages`() {
        val privateFeatureFiles = productionScope.files.withPackage(
            *PRIVATE_FEATURES.map { "app.sila.feature.$it.." }.toTypedArray()
        )
        kotlinAssertTrue(
            privateFeatureFiles.isNotEmpty(),
            "Expected non-empty set of production files in contacts and arham features."
        )

        privateFeatureFiles.assertFalse(
            additionalMessage = "Privacy-sensitive features (contacts, arham) must never import or FQN-reference Ktor, Firebase, :core:network, or :core:firebase."
        ) { file ->
            val hasForbiddenImport = file.imports.any { importDecl ->
                FORBIDDEN_PRIVACY_PACKAGE_PREFIXES.any { prefix ->
                    matchesPackageOrSubpackage(importDecl.name, prefix)
                }
            }
            val strippedCode = stripCommentsAndStrings(file.text)
            val hasForbiddenFqn = FORBIDDEN_PRIVACY_FQN_REGEX.containsMatchIn(strippedCode)
            hasForbiddenImport || hasForbiddenFqn
        }
    }

    /**
     * Rule 3 — Zero feature-to-feature coupling:
     * ملفات `app.sila.feature.<x>..` لا تستورد ولا تشير إلى `app.sila.feature.<y>..` عندما `x != y`.
     */
    @Test
    fun `rule 3 - features never import or reference declarations from other features`() {
        val featureFiles = productionScope.files.withPackage(
            *FEATURE_NAMES.map { "app.sila.feature.$it.." }.toTypedArray()
        )
        kotlinAssertTrue(
            featureFiles.isNotEmpty(),
            "Expected non-empty set of feature files across contacts, arham, services, and settings."
        )

        featureFiles.assertFalse(
            additionalMessage = "Feature packages must remain completely decoupled from other feature packages (app.sila.feature.<x>.. must not import or reference app.sila.feature.<y>.. when x != y)."
        ) { file ->
            val pkgName = file.packagee?.name ?: return@assertFalse false
            val currentFeature = FEATURE_PACKAGE_REGEX.find(pkgName)?.groupValues?.get(1)
                ?: return@assertFalse false

            val hasCrossFeatureImport = file.imports.any { importDecl ->
                val importedFeature = FEATURE_PACKAGE_REGEX.find(importDecl.name)?.groupValues?.get(1)
                importedFeature != null && importedFeature in FEATURE_NAMES && importedFeature != currentFeature
            }

            val strippedCode = stripCommentsAndStrings(file.text)
            val hasCrossFeatureFqn = FEATURE_NAMES
                .filter { it != currentFeature }
                .any { otherFeature ->
                    Regex("""\bapp\.sila\.feature\.$otherFeature\.[A-Za-z_*]""").containsMatchIn(strippedCode)
                }

            hasCrossFeatureImport || hasCrossFeatureFqn
        }
    }

    /**
     * Rule 4 — Complete Clean Architecture layer purity:
     * - طبقة `domain` لا تستورد `data` أو `presentation` أو `app.sila.core.database..` أو `app.sila.core.datastore..`
     *   أو `app.sila.core.designsystem..` أو `androidx.compose..` أو `androidx.room..` أو حزم الشبكة/Firebase.
     * - طبقة `presentation` وموديول `sharedUI` لا يستوردان حزم `data` أو `app.sila.core.database..` أو `app.sila.core.datastore..`
     *   ولا يشيران إلى `*RepositoryImpl` أو `*Dao`.
     */
    @Test
    fun `rule 4a - domain layer files remain pure and free of data presentation database datastore compose room and network imports`() {
        val domainFiles = productionScope.files.withPackage("..domain..")
        kotlinAssertTrue(domainFiles.isNotEmpty(), "Expected non-empty set of domain files.")

        domainFiles.assertFalse(
            additionalMessage = "Domain layer files must not import or reference data, presentation, database, datastore, designsystem, compose, room, network, or firebase."
        ) { file ->
            val hasForbiddenImport = file.imports.any { importDecl ->
                val imported = importDecl.name
                val importsDataOrPresentation = FEATURE_NAMES.any { feature ->
                    matchesPackageOrSubpackage(imported, "app.sila.feature.$feature.data") ||
                        matchesPackageOrSubpackage(imported, "app.sila.feature.$feature.presentation")
                } || Regex("""^app\.sila\.(?:[^.]+\.)*(?:data|presentation)(?:\..+)?$""").matches(imported)

                val importsForbiddenFramework = FORBIDDEN_DOMAIN_IMPORT_PREFIXES.any { prefix ->
                    matchesPackageOrSubpackage(imported, prefix)
                }
                importsDataOrPresentation || importsForbiddenFramework
            }

            val strippedCode = stripCommentsAndStrings(file.text)
            val hasForbiddenFqn = FORBIDDEN_DOMAIN_IMPORT_PREFIXES.any { prefix ->
                Regex("""\b${Regex.escape(prefix)}\.[A-Za-z_*]""").containsMatchIn(strippedCode)
            } || FEATURE_NAMES.any { feature ->
                Regex("""\bapp\.sila\.feature\.$feature\.(?:data|presentation)\.[A-Za-z_*]""")
                    .containsMatchIn(strippedCode)
            }

            hasForbiddenImport || hasForbiddenFqn
        }
    }

    @Test
    fun `rule 4b - presentation and sharedUI files never import data database or datastore or reference RepositoryImpl or Dao`() {
        val presentationAndUiFiles = productionScope.files.filter { file ->
            val normalizedPath = file.path.replace('\\', '/')
            val pkg = file.packagee?.name.orEmpty()
            pkg.contains(".presentation") ||
                normalizedPath.contains("/presentation/src/") ||
                normalizedPath.contains("/sharedUI/src/")
        }
        kotlinAssertTrue(
            presentationAndUiFiles.isNotEmpty(),
            "Expected non-empty set of presentation and sharedUI files."
        )

        presentationAndUiFiles.assertFalse(
            additionalMessage = "Presentation and sharedUI files must never import data-layer, database, or datastore packages, nor reference *RepositoryImpl or *Dao."
        ) { file ->
            val hasForbiddenImport = file.imports.any { importDecl ->
                val imported = importDecl.name
                val importsDataLayer = FEATURE_NAMES.any { feature ->
                    matchesPackageOrSubpackage(imported, "app.sila.feature.$feature.data")
                } || Regex("""^app\.sila\.(?:[^.]+\.)*data(?:\..+)?$""").matches(imported)

                val importsDatabaseOrDatastore = FORBIDDEN_PRESENTATION_IMPORT_PREFIXES.any { prefix ->
                    matchesPackageOrSubpackage(imported, prefix)
                }

                val importsRepoImplOrDao = imported.endsWith("RepositoryImpl") || imported.endsWith("Dao")
                importsDataLayer || importsDatabaseOrDatastore || importsRepoImplOrDao
            }

            // AST declaration / parameter / property / type reference check + stripped source check
            val astReferencesRepoImplOrDao = file.classes().any { cls ->
                cls.constructors.flatMap { it.parameters }.any { param ->
                    val typeName = param.type.name
                    typeName.endsWith("RepositoryImpl") || typeName.endsWith("Dao")
                } || cls.properties().any { prop ->
                    val typeName = prop.type?.name.orEmpty()
                    typeName.endsWith("RepositoryImpl") || typeName.endsWith("Dao")
                }
            } || file.functions().any { fn ->
                fn.parameters.any { param ->
                    val typeName = param.type.name
                    typeName.endsWith("RepositoryImpl") || typeName.endsWith("Dao")
                }
            }

            val strippedCode = stripCommentsAndStrings(file.text)
            val textReferencesRepoImplOrDao = FORBIDDEN_PRESENTATION_REF_REGEX.containsMatchIn(strippedCode)
            val textReferencesForbiddenPkg = FORBIDDEN_PRESENTATION_IMPORT_PREFIXES.any { prefix ->
                Regex("""\b${Regex.escape(prefix)}\.[A-Za-z_*]""").containsMatchIn(strippedCode)
            } || FEATURE_NAMES.any { feature ->
                Regex("""\bapp\.sila\.feature\.$feature\.data\.[A-Za-z_*]""").containsMatchIn(strippedCode)
            }

            hasForbiddenImport || astReferencesRepoImplOrDao || textReferencesRepoImplOrDao || textReferencesForbiddenPkg
        }
    }

    /**
     * Rule 5 — UseCase and ViewModel structural contracts:
     * - كل صنف إنتاجي ينتهي اسمه بـ `UseCase` يجب أن يقع في حزمة `..domain..` ويصرّح عن دالة `operator fun invoke`.
     * - كل صنف إنتاجي ينتهي اسمه بـ `ViewModel` يجب أن ينفّذ `MviViewModel` أو يرث من `BaseMviViewModel` (بما يشمل الوراثة غير المباشرة).
     */
    @Test
    fun `rule 5a - every UseCase class resides in a domain package and declares operator fun invoke`() {
        val useCaseClasses = productionScope.classes().withNameEndingWith("UseCase")
        kotlinAssertTrue(useCaseClasses.isNotEmpty(), "Expected non-empty set of *UseCase classes.")

        useCaseClasses.assertTrue(
            additionalMessage = "Every *UseCase class must reside in a ..domain.. package and declare an `operator fun invoke`."
        ) { useCaseClass ->
            val residesInDomain = useCaseClass.resideInPackage("..domain..")
            val hasOperatorInvoke = useCaseClass.functions().any { function ->
                function.name == "invoke" && function.hasOperatorModifier
            }
            residesInDomain && hasOperatorInvoke
        }
    }

    @Test
    fun `rule 5b - every ViewModel class implements MviViewModel or extends BaseMviViewModel`() {
        val viewModelClasses = productionScope.classes().withNameEndingWith("ViewModel")
        kotlinAssertTrue(viewModelClasses.isNotEmpty(), "Expected non-empty set of *ViewModel classes.")

        // Supplementary check: verify MviViewModel interface itself defines state: StateFlow<*> and onIntent
        val mviInterfaces = productionScope.interfaces().filter { it.fullyQualifiedName == MVI_VIEW_MODEL_FQN }
        kotlinAssertTrue(
            mviInterfaces.size == 1,
            "Expected exactly one $MVI_VIEW_MODEL_FQN interface in productionScope."
        )
        val mviInterface = mviInterfaces.single()
        kotlinAssertTrue(
            mviInterface.properties().any { it.name == "state" && it.type?.name?.startsWith("StateFlow") == true } &&
                mviInterface.functions().any { it.name == "onIntent" },
            "MviViewModel contract must declare `val state: StateFlow<S>` and `fun onIntent(intent: I)`."
        )

        viewModelClasses.assertTrue(
            additionalMessage = "Every production *ViewModel class must implement MviViewModel or extend BaseMviViewModel (directly or indirectly)."
        ) { viewModelClass ->
            inheritsFromMviContract(viewModelClass, productionScope)
        }
    }

    /**
     * Regression Test 1 — `stripCommentsAndStrings` lexical scanner:
     * يتحقق من أن وجود تعليقات السطر أو الكتلة داخل النصوص العادية أو متعددة الأسطر أو المحتوية على علامات اقتباس مهربة (`\"`)
     * لا يؤدي إلى حذف بقية السطر أو إخفاء مراجع FQN الممنوعة التي تلي النص على نفس السطر.
     */
    @Test
    fun `regression - stripCommentsAndStrings preserves code after URLs escaped quotes and triple-quoted strings`() {
        // Case 1: URL with `//` inside double-quoted string followed by forbidden FQN on the same line
        val urlThenForbiddenFqn = """val endpoint = "https://example.com/api"; val client = io.ktor.client.HttpClient()"""
        val strippedUrlLine = stripCommentsAndStrings(urlThenForbiddenFqn)
        kotlinAssertTrue(
            FORBIDDEN_PRIVACY_FQN_REGEX.containsMatchIn(strippedUrlLine),
            "Forbidden FQN after a string containing 'https://' on the same line must be detected, but stripped line was: $strippedUrlLine"
        )

        // Case 2: Escaped quotes and `//` inside double-quoted string followed by forbidden FQN on the same line
        val escapedQuoteThenFqn = """val note = "say \"hello // world\" here"; val app = com.google.firebase.FirebaseApp.getInstance()"""
        val strippedEscapedLine = stripCommentsAndStrings(escapedQuoteThenFqn)
        kotlinAssertTrue(
            FORBIDDEN_PRIVACY_FQN_REGEX.containsMatchIn(strippedEscapedLine),
            "Forbidden FQN after escaped quotes and '//' must be detected, but stripped line was: $strippedEscapedLine"
        )

        // Case 3: Triple-quoted string containing `//`, block comment markers, and quotes followed by forbidden FQN on the same line
        val openBlock = "/" + "*"
        val closeBlock = "*" + "/"
        val tripleQuote = "\"\"\""
        val tripleQuotedThenFqn =
            "val raw = ${tripleQuote}https://example.com // not a comment $openBlock still string $closeBlock \"quote\"$tripleQuote; val fb = dev.gitlive.firebase.Firebase"
        val strippedTripleLine = stripCommentsAndStrings(tripleQuotedThenFqn)
        kotlinAssertTrue(
            FORBIDDEN_PRIVACY_FQN_REGEX.containsMatchIn(strippedTripleLine),
            "Forbidden FQN after triple-quoted string must be detected, but stripped line was: $strippedTripleLine"
        )

        // Case 4: Forbidden FQN inside comments or string literals only (must NOT trigger false positives)
        val commentsAndStringsOnly = listOf(
            "// io.ktor.client.HttpClient in line comment",
            "$openBlock com.google.firebase.FirebaseApp in block comment $openBlock nested dev.gitlive.firebase.Firebase $closeBlock $closeBlock",
            "val str = \"io.ktor.client.HttpClient\"",
            "val raw = ${tripleQuote}app.sila.core.network.ApiClient$tripleQuote"
        ).joinToString("\n")
        val strippedSafeCode = stripCommentsAndStrings(commentsAndStringsOnly)
        kotlinAssertFalse(
            FORBIDDEN_PRIVACY_FQN_REGEX.containsMatchIn(strippedSafeCode),
            "Commented-out or string-literal FQNs must be stripped and not trigger false positives, but got: $strippedSafeCode"
        )
    }

    /**
     * Regression Test 2 — `inheritsFromMviContract` direct, indirect class/interface, and FQN collision checks:
     * يتحقق من دعم سلاسل الوراثة غير المباشرة عبر الأصناف الوسيطة والواجهات الوسيطة، ومنع انتحال الاسم البسيط
     * (`MviViewModel` أو `BaseMviViewModel`) في حزمة أخرى غير `app.sila.core.common`.
     */
    @Test
    fun `regression - inheritsFromMviContract supports indirect classes and interfaces and rejects spoofed type names`() {
        val tempDir = Files.createTempDirectory("konsist_mvi_regression_")
        try {
            val fixtureFile = tempDir.resolve("ViewModelHierarchyFixtures.kt").toFile()
            fixtureFile.writeText(
                """
                package app.sila.feature.contacts.presentation

                import app.sila.core.common.BaseMviViewModel
                import app.sila.core.common.MviViewModel

                // 1. Direct BaseMviViewModel subclass
                class DirectBaseViewModel : BaseMviViewModel<String, String, String>("") {
                    override fun onIntent(intent: String) = Unit
                }

                // 2. Direct MviViewModel implementation
                abstract class DirectInterfaceViewModel : MviViewModel<String, String, String>

                // 3. Indirect via intermediate abstract class
                abstract class IntermediateFeatureBase : BaseMviViewModel<String, String, String>("")
                class IndirectViaClassViewModel : IntermediateFeatureBase() {
                    override fun onIntent(intent: String) = Unit
                }

                // 4. Indirect via intermediate interface + multi-hop
                interface FeatureContract<S, I, E> : MviViewModel<S, I, E>
                interface ContactsContract : FeatureContract<String, String, String>
                abstract class IndirectViaInterfaceViewModel : ContactsContract

                // 5. Negative: plain class with no MviViewModel/BaseMviViewModel inheritance
                class UnrelatedPlainViewModel
                """.trimIndent()
            )

            val spoofFile = tempDir.resolve("SpoofedMviFixtures.kt").toFile()
            spoofFile.writeText(
                """
                package app.sila.spoof

                interface MviViewModel<S, I, E>
                abstract class BaseMviViewModel<S, I, E>

                class SpoofedInterfaceViewModel : MviViewModel<String, String, String>
                class SpoofedBaseClassViewModel : BaseMviViewModel<String, String, String>()
                """.trimIndent()
            )

            val fixtureScope = productionScope + Konsist.scopeFromExternalDirectory(tempDir.toString())
            val classesByFqn = fixtureScope.classes().associateBy { it.fullyQualifiedName }

            kotlinAssertTrue(
                inheritsFromMviContract(
                    classesByFqn.getValue("app.sila.feature.contacts.presentation.DirectBaseViewModel"),
                    fixtureScope
                ),
                "DirectBaseViewModel extending app.sila.core.common.BaseMviViewModel must pass."
            )
            kotlinAssertTrue(
                inheritsFromMviContract(
                    classesByFqn.getValue("app.sila.feature.contacts.presentation.DirectInterfaceViewModel"),
                    fixtureScope
                ),
                "DirectInterfaceViewModel implementing app.sila.core.common.MviViewModel must pass."
            )
            kotlinAssertTrue(
                inheritsFromMviContract(
                    classesByFqn.getValue("app.sila.feature.contacts.presentation.IndirectViaClassViewModel"),
                    fixtureScope
                ),
                "IndirectViaClassViewModel inheriting via IntermediateFeatureBase must pass."
            )
            kotlinAssertTrue(
                inheritsFromMviContract(
                    classesByFqn.getValue("app.sila.feature.contacts.presentation.IndirectViaInterfaceViewModel"),
                    fixtureScope
                ),
                "IndirectViaInterfaceViewModel implementing MviViewModel via intermediate interfaces must pass."
            )
            kotlinAssertFalse(
                inheritsFromMviContract(
                    classesByFqn.getValue("app.sila.feature.contacts.presentation.UnrelatedPlainViewModel"),
                    fixtureScope
                ),
                "UnrelatedPlainViewModel without MviViewModel inheritance must fail."
            )
            kotlinAssertFalse(
                inheritsFromMviContract(
                    classesByFqn.getValue("app.sila.spoof.SpoofedInterfaceViewModel"),
                    fixtureScope
                ),
                "SpoofedInterfaceViewModel implementing app.sila.spoof.MviViewModel must fail qualified identity check."
            )
            kotlinAssertFalse(
                inheritsFromMviContract(
                    classesByFqn.getValue("app.sila.spoof.SpoofedBaseClassViewModel"),
                    fixtureScope
                ),
                "SpoofedBaseClassViewModel extending app.sila.spoof.BaseMviViewModel must fail qualified identity check."
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun inheritsFromMviContract(
        declaration: KoParentProvider,
        scope: KoScope = productionScope,
        visitedFqns: Set<String> = emptySet()
    ): Boolean {
        val currentFqn = (declaration as? KoFullyQualifiedNameProvider)?.fullyQualifiedName
            ?: (declaration as? KoNameProvider)?.name
            ?: return false
        if (currentFqn in TARGET_MVI_CONTRACT_FQNS) return true
        if (currentFqn in visitedFqns) return false

        val containingFile = when (declaration) {
            is KoClassDeclaration -> declaration.containingFile
            is KoInterfaceDeclaration -> declaration.containingFile
            else -> null
        }

        val allTypeDeclarations: Map<String, KoParentProvider> = buildMap {
            scope.classes().forEach { cls ->
                cls.fullyQualifiedName?.let { put(it, cls) }
            }
            scope.interfaces().forEach { iface ->
                iface.fullyQualifiedName?.let { put(it, iface) }
            }
        }

        val resolvedParentFqns = declaration.parents().mapNotNull { parent ->
            val rawTypeRef = parent.name
                .substringBefore('<')
                .substringBefore('(')
                .trim()
            resolveQualifiedTypeName(rawTypeRef, containingFile, allTypeDeclarations.keys)
        }

        if (resolvedParentFqns.any { it in TARGET_MVI_CONTRACT_FQNS }) {
            return true
        }

        val nextVisited = visitedFqns + currentFqn
        return resolvedParentFqns.any { parentFqn ->
            val parentDecl = allTypeDeclarations[parentFqn] ?: return@any false
            inheritsFromMviContract(parentDecl, scope, nextVisited)
        }
    }

    private fun resolveQualifiedTypeName(
        rawTypeRef: String,
        containingFile: KoFileDeclaration?,
        knownFqns: Set<String>
    ): String? {
        if (rawTypeRef.isEmpty()) return null

        // 1. Already a fully qualified reference in source
        if (rawTypeRef.contains('.')) {
            if (rawTypeRef in knownFqns || rawTypeRef in TARGET_MVI_CONTRACT_FQNS) {
                return rawTypeRef
            }
            val firstSegment = rawTypeRef.substringBefore('.')
            val rest = rawTypeRef.substringAfter('.')
            val aliasedPrefix = containingFile?.imports
                ?.firstOrNull { it.alias?.name == firstSegment }
                ?.name
            if (aliasedPrefix != null) {
                return "$aliasedPrefix.$rest"
            }
            return rawTypeRef
        }

        if (containingFile != null) {
            // 2. Imported via alias (`import pkg.Type as Alias`)
            containingFile.imports.firstOrNull { it.alias?.name == rawTypeRef }?.let { aliasedImport ->
                return aliasedImport.name
            }

            // 3. Imported via explicit non-wildcard import (`import pkg.Type`)
            containingFile.imports.firstOrNull { importDecl ->
                !importDecl.isWildcard &&
                    importDecl.alias == null &&
                    importDecl.name.substringAfterLast('.') == rawTypeRef
            }?.let { explicitImport ->
                return explicitImport.name
            }

            // 4. Declared in the same package as the containing file
            val currentPkg = containingFile.packagee?.name.orEmpty()
            val samePackageCandidate = if (currentPkg.isNotEmpty()) "$currentPkg.$rawTypeRef" else rawTypeRef
            if (samePackageCandidate in knownFqns || samePackageCandidate in TARGET_MVI_CONTRACT_FQNS) {
                return samePackageCandidate
            }

            // 5. Imported via wildcard (`import pkg.*`)
            for (wildcardImport in containingFile.imports.filter { it.isWildcard }) {
                val pkgPrefix = wildcardImport.name.removeSuffix(".*")
                val candidate = "$pkgPrefix.$rawTypeRef"
                if (candidate in knownFqns || candidate in TARGET_MVI_CONTRACT_FQNS) {
                    return candidate
                }
            }
        }

        // 6. Fallback only if uniquely identifiable across known FQNs
        val simpleMatches = (knownFqns + TARGET_MVI_CONTRACT_FQNS)
            .filter { it.substringAfterLast('.') == rawTypeRef }
        return simpleMatches.singleOrNull()
    }

    private fun matchesPackageOrSubpackage(importName: String, packagePrefix: String): Boolean {
        val normalizedImport = importName.removeSuffix(".*")
        return normalizedImport == packagePrefix || normalizedImport.startsWith("$packagePrefix.")
    }

    /**
     * ماسح معجمي أحادي التمرير (Single-Pass Lexical Scanner) يزيل التعليقات ومحتويات النصوص
     * بأمان دون أن تتسبب سلاسل مثل `https://` أو علامات الاقتباس المهربة `\"` أو النصوص الثلاثية `"""`
     * في ابتلاع الكود الفعلي الواقع بعدها على نفس السطر.
     */
    internal fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length

        while (i < n) {
            val c = source[i]

            // 1. Line comment `// ...` (only when outside strings and block comments)
            if (c == '/' && i + 1 < n && source[i + 1] == '/') {
                i += 2
                while (i < n && source[i] != '\n' && source[i] != '\r') {
                    i++
                }
                continue
            }

            // 2. Block comment `/* ... */` with Kotlin nested block-comment support
            if (c == '/' && i + 1 < n && source[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < n && depth > 0) {
                    if (source[i] == '/' && i + 1 < n && source[i + 1] == '*') {
                        depth++
                        i += 2
                    } else if (source[i] == '*' && i + 1 < n && source[i + 1] == '/') {
                        depth--
                        i += 2
                    } else {
                        if (source[i] == '\n' || source[i] == '\r') {
                            out.append(source[i])
                        }
                        i++
                    }
                }
                out.append(' ')
                continue
            }

            // 3. Triple-quoted raw string `""" ... """`
            if (c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                out.append("\"\"")
                i += 3
                while (i < n) {
                    if (source[i] == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                        i += 3
                        while (i < n && source[i] == '"') {
                            i++
                        }
                        break
                    }
                    if (source[i] == '\n' || source[i] == '\r') {
                        out.append(source[i])
                    }
                    i++
                }
                continue
            }

            // 4. Standard double-quoted string `" ... "` with backslash escape handling
            if (c == '"') {
                out.append("\"\"")
                i++
                while (i < n) {
                    val sc = source[i]
                    if (sc == '\\') {
                        i += 2
                        continue
                    }
                    if (sc == '"') {
                        i++
                        break
                    }
                    if (sc == '\n' || sc == '\r') {
                        out.append(sc)
                        i++
                        break
                    }
                    i++
                }
                continue
            }

            // 5. Character literal `'c'` or `'\''`
            if (c == '\'') {
                out.append("''")
                i++
                while (i < n) {
                    val cc = source[i]
                    if (cc == '\\') {
                        i += 2
                        continue
                    }
                    if (cc == '\'' || cc == '\n' || cc == '\r') {
                        if (cc == '\n' || cc == '\r') out.append(cc)
                        i++
                        break
                    }
                    i++
                }
                continue
            }

            out.append(c)
            i++
        }

        return out.toString()
    }
}
