import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.kotlin
import org.gradle.kotlin.dsl.*

/**
 * Object with common dependency groups for easy and central dependency management.
 */
object Dependencies {
    /**
     * Object containing versions for various dependencies.
     */
    object Versions {
        const val kotlin = "2.0.0"
        const val dokka = "0.9.18"
        const val ksp = "$kotlin-1.0.21"

        const val constraintLayout = "2.2.0"
        const val coreKtx = "1.15.0"
        const val appcompat = "1.7.0"
        const val fragment = "1.8.5"
        const val moshi = "1.15.1"
        const val work = "2.10.0"
        const val lifecycle = "2.8.7"
        const val preference = "1.2.1"
        const val material = "1.12.0"
        const val desugar = "2.1.3"

        const val coroutines = "1.8.1"

        const val sqlite = "3.45.0"
        const val room = "2.6.1"

        const val recyclerView = "1.3.2"
        const val paging = "3.3.4"

        const val playServicesBase = "18.5.0"
        const val playLocation = "21.3.0"
        const val playFeatureDelivery = "2.1.0"
        const val maps = "19.0.0"

        const val stax = "1.0.1"
        const val jpx = "3.2.0"
        const val xml = "1.3.3"

        const val spotlight = "2.2.3"
        const val dialogs = "3.3.0"

        const val componentsRecycler = "1.0.0"
        const val componentsDraggable = "1.0.4"
        const val componentSlider = "2.1.0"

        const val dexter = "6.2.3"

        const val suncalc = "3.11"

        const val androidxBom = "2024.11.00"
        const val composeBom = "2024.10.01"
        const val gmsBom = "21.3.0"

        /**
         * Testing specific dependencies
         */
        object Test {
            const val rules = "1.5.0"
            const val runner = "1.5.2"
            const val espresso = "3.5.1"
            const val coreTesting = "2.2.0"
            const val testingKtx = "1.3.0"
        }
    }

    private fun DependencyHandlerScope.implementation(dependency: Any) =
        add("implementation", dependency)

    private fun DependencyHandlerScope.api(dependency: Any) =
        add("api", dependency)

    private fun DependencyHandlerScope.ksp(dependency: Any) =
        add("ksp", dependency)

    private fun DependencyHandlerScope.coreLibraryDesugaring(dependency: Any) =
        add("coreLibraryDesugaring", dependency)

    private fun DependencyHandlerScope.androidTestImplementation(dependency: Any) =
        add("androidTestImplementation", dependency)

    private fun DependencyHandlerScope.debugImplementation(dependency: Any) =
        add("debugImplementation", dependency)

    // Extension functions on DependencyHandlerScope for adding dependencies
    fun json(scope: DependencyHandlerScope) {
        scope.implementation("com.squareup.moshi:moshi:${Versions.moshi}")
        scope.ksp("com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}")
    }

    fun database(scope: DependencyHandlerScope) {
        val androidxBom = scope.platform("androidx:androidx-bom:${Versions.androidxBom}")
        scope.implementation(androidxBom)

        val roomBase = "androidx.room:room"
        scope.api("$roomBase-runtime")
        scope.ksp("$roomBase-compiler")
        scope.implementation("$roomBase-ktx")
        scope.implementation("$roomBase-paging")
        scope.implementation("com.github.requery:sqlite-android:${Versions.sqlite}")
        scope.androidTestImplementation("$roomBase-testing")
    }

    fun core(scope: DependencyHandlerScope) {
        // Apply BOMs
        val androidxBom = scope.platform("androidx.compose:compose-bom:${Versions.composeBom}")
        val gmsBom = scope.platform("com.google.android.gms:play-services-bom:${Versions.gmsBom}")
        val kotlinBom = scope.platform("org.jetbrains.kotlin:kotlin-bom:${Versions.kotlin}")

        scope.implementation(androidxBom)
        scope.implementation(gmsBom)
        scope.implementation(kotlinBom)

        // Core Library Desugaring
        scope.coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${Versions.desugar}")

        // Kotlin Symbol Processing API
        scope.implementation("com.google.devtools.ksp:symbol-processing-api:${Versions.ksp}")

        // AndroidX Libraries
        scope.implementation("androidx.appcompat:appcompat")
        scope.implementation("androidx.core:core-ktx")
        scope.implementation("androidx.constraintlayout:constraintlayout")
        scope.implementation("androidx.recyclerview:recyclerview")
        scope.implementation("androidx.lifecycle:lifecycle-runtime-ktx")
        scope.implementation("androidx.lifecycle:lifecycle-service")
        scope.implementation("androidx.lifecycle:lifecycle-process")
        scope.implementation("androidx.fragment:fragment")
        scope.implementation("androidx.fragment:fragment-ktx")
        scope.implementation("androidx.preference:preference")
        scope.implementation("androidx.lifecycle:lifecycle-common-java8")

        // Google Play Services
        scope.implementation("com.google.android.gms:play-services-base")
        scope.implementation("com.google.android.play:feature-delivery")
        scope.implementation("com.google.android.play:feature-delivery-ktx")

        // Material Design Components
        scope.implementation("com.google.android.material:material")

        // Kotlin Libraries
        scope.implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        scope.implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android")

        // Third-Party Libraries
        scope.implementation("com.github.adsamcik:Recycler:${Versions.componentsRecycler}")
        scope.implementation("com.afollestad.material-dialogs:core:${Versions.dialogs}")
        scope.implementation("com.karumi:dexter:${Versions.dexter}")

        // Work Manager
        work(scope)
    }

    fun work(scope: DependencyHandlerScope) {
        scope.implementation("androidx.work:work-runtime-ktx:${Versions.work}")
        scope.androidTestImplementation("androidx.work:work-testing:${Versions.work}")
    }

    fun map(scope: DependencyHandlerScope) {
        scope.implementation("com.google.android.gms:play-services-maps:${Versions.maps}")
    }

    fun location(scope: DependencyHandlerScope) {
        scope.implementation("com.google.android.gms:play-services-location:${Versions.playLocation}")
    }

    fun draggable(scope: DependencyHandlerScope) {
        scope.implementation("com.github.adsamcik:Draggable:${Versions.componentsDraggable}")
    }

    fun slider(scope: DependencyHandlerScope) {
        scope.implementation("com.github.adsamcik:slider:${Versions.componentSlider}")
    }

    fun preference(scope: DependencyHandlerScope) {
        scope.implementation("androidx.preference:preference:${Versions.preference}")
    }

    fun inputDialog(scope: DependencyHandlerScope) {
        scope.implementation("com.afollestad.material-dialogs:input:${Versions.dialogs}")
    }

    fun colorChooser(scope: DependencyHandlerScope) {
        scope.implementation("com.afollestad.material-dialogs:color:${Versions.dialogs}")
    }

    fun gpx(scope: DependencyHandlerScope) {
        scope.implementation("stax:stax-api:${Versions.stax}")
        scope.implementation("com.fasterxml:aalto-xml:${Versions.xml}")
        scope.implementation("io.jenetics:jpx:${Versions.jpx}")
    }

    fun introduction(scope: DependencyHandlerScope) {
        scope.implementation("com.github.adsamcik:spotlight:${Versions.spotlight}")
    }

    fun sunCalculator(scope: DependencyHandlerScope) {
        scope.implementation("org.shredzone.commons:commons-suncalc:${Versions.suncalc}")
    }

    fun paging(scope: DependencyHandlerScope) {
        scope.implementation("androidx.paging:paging-runtime:${Versions.paging}")
    }

    fun compose(scope: DependencyHandlerScope) {
        // Import the Compose BOM (Bill of Materials) to manage versions
        val composeBom = scope.platform("androidx.compose:compose-bom:2024.10.01")
        scope.implementation(composeBom)
        scope.androidTestImplementation(composeBom)

        // Core Compose libraries
        scope.implementation("androidx.compose.material3:material3")
        scope.implementation("androidx.compose.ui:ui-tooling-preview")
        scope.debugImplementation("androidx.compose.ui:ui-tooling")
        scope.implementation("androidx.activity:activity-compose:1.9.2")
        scope.implementation("androidx.compose.material:material-icons-extended")

        // Additional Compose libraries
        scope.implementation("androidx.compose.animation:animation")
        scope.implementation("androidx.compose.animation:animation-graphics")
        scope.implementation("androidx.navigation:navigation-compose:2.9.0-alpha03")
        scope.implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
        scope.implementation("androidx.compose.runtime:runtime-livedata")
        scope.implementation("androidx.constraintlayout:constraintlayout-compose:1.0.1")

        // Testing
        scope.androidTestImplementation("androidx.compose.ui:ui-test-junit4")
        scope.debugImplementation("androidx.compose.ui:ui-test-manifest")

        // Updated Accompanist libraries
        scope.implementation("com.google.accompanist:accompanist-pager:0.36.0")
        scope.implementation("com.google.accompanist:accompanist-swiperefresh:0.36.0")
    }



    fun test(scope: DependencyHandlerScope) {
        scope.androidTestImplementation("junit:junit:4.12")
        scope.androidTestImplementation("androidx.test:runner:${Versions.Test.rules}")
        scope.androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
        scope.androidTestImplementation("androidx.test.ext:junit:1.1.5")
        scope.androidTestImplementation("androidx.arch.core:core-testing:${Versions.Test.coreTesting}")
        scope.androidTestImplementation("com.jraska.livedata:testing-ktx:${Versions.Test.testingKtx}")
        scope.androidTestImplementation("androidx.test.espresso:espresso-core:${Versions.Test.espresso}")
        scope.androidTestImplementation("androidx.test.espresso:espresso-contrib:${Versions.Test.espresso}")
    }

    fun corePlugins(scope: org.gradle.plugin.use.PluginDependenciesSpec) {
        with(scope) {
            kotlin("android")
            id("org.jetbrains.kotlin.plugin.parcelize")
            id("com.google.devtools.ksp")
        }
    }
}
