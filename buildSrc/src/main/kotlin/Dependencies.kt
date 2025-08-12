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
    const val KOTLIN = "2.2.0"
        const val DOKKA = "0.9.18"
    const val KSP = "2.2.0-2.0.2"

        const val CONSTRAINT_LAYOUT = "2.2.1"
        const val CORE_KTX = "1.16.0"
        const val APPCOMPAT = "1.7.1"
        const val FRAGMENT = "1.8.8"
        const val MOSHI = "1.15.2"
    const val WORK = "2.10.3"
    const val LIFECYCLE = "2.9.2"
        const val PREFERENCE = "1.2.1"
        const val MATERIAL = "1.12.0"

        const val COROUTINES = "1.10.2"

        const val SQLITE = "3.49.0"
        const val ROOM = "2.7.2"

        const val RECYCLER_VIEW = "1.4.0"
        const val PAGING = "3.3.6"

    const val PLAY_SERVICES_BASE = "18.7.2"
        const val PLAY_LOCATION = "21.3.0"
        const val PLAY_FEATURE_DELIVERY = "2.1.0"
        const val MAPS = "19.2.0"

        const val STAX = "1.0.1"
        const val JPX = "3.2.1"
        const val XML = "1.3.3"

        const val SPOTLIGHT = "2.2.3"
        const val DIALOGS = "3.3.0"

        const val COMPONENTS_RECYCLER = "1.0.0"
        const val COMPONENTS_DRAGGABLE = "1.0.4"
        const val COMPONENT_SLIDER = "2.1.0"

        const val DEXTER = "6.2.3"
        const val SUNCALC = "3.11"
        
    const val DESUGAR_JDK_LIBS = "2.1.5"

    const val COMPOSE_BOM = "2025.07.00"
        const val ACTIVITY_COMPOSE = "1.11.0-rc01"
    const val NAVIGATION_COMPOSE = "2.9.3"
        const val CONSTRAINT_LAYOUT_COMPOSE = "1.1.1"
        const val ACCOMPANIST = "0.36.0"

        /**
         * Testing specific dependencies
         */
        object Test {
            const val JUNIT = "4.13.2"
            const val JUNIT_EXT = "1.3.0"
            const val UIAUTOMATOR = "2.3.0"
            const val RULES = "1.6.2"
            const val RUNNER = "1.7.0"
            const val ESPRESSO = "3.7.0"
            const val CORE_TESTING = "2.2.0"
            const val TESTING_KTX = "1.3.0"
        }
    }

    private fun DependencyHandlerScope.implementation(dependency: Any) =
        add("implementation", dependency)

    private fun DependencyHandlerScope.api(dependency: Any) =
        add("api", dependency)

    private fun DependencyHandlerScope.ksp(dependency: Any) =
        add("ksp", dependency)

    private fun DependencyHandlerScope.androidTestImplementation(dependency: Any) =
        add("androidTestImplementation", dependency)

    private fun DependencyHandlerScope.debugImplementation(dependency: Any) =
        add(
            "debugImplementation",
            dependency
        )    // Extension functions on DependencyHandlerScope for adding dependencies

    fun json(scope: DependencyHandlerScope) {
        scope.implementation("com.squareup.moshi:moshi:${Versions.MOSHI}")
        scope.ksp("com.squareup.moshi:moshi-kotlin-codegen:${Versions.MOSHI}")
    }

    fun database(scope: DependencyHandlerScope) {
        val roomBase = "androidx.room:room"
        scope.api("$roomBase-runtime:${Versions.ROOM}")
        scope.ksp("$roomBase-compiler:${Versions.ROOM}")
        scope.implementation("$roomBase-ktx:${Versions.ROOM}")
        scope.implementation("$roomBase-paging:${Versions.ROOM}")
        scope.implementation("com.github.requery:sqlite-android:${Versions.SQLITE}")
        scope.androidTestImplementation("$roomBase-testing:${Versions.ROOM}")
    }

    fun core(scope: DependencyHandlerScope) {
        // Kotlin Symbol Processing API
        scope.implementation("com.google.devtools.ksp:symbol-processing-api:${Versions.KSP}")

        // AndroidX Libraries
        scope.implementation("androidx.appcompat:appcompat:${Versions.APPCOMPAT}")
        scope.implementation("androidx.core:core-ktx:${Versions.CORE_KTX}")
        scope.implementation("androidx.constraintlayout:constraintlayout:${Versions.CONSTRAINT_LAYOUT}")
        scope.implementation("androidx.recyclerview:recyclerview:${Versions.RECYCLER_VIEW}")
        scope.implementation("androidx.lifecycle:lifecycle-runtime-ktx:${Versions.LIFECYCLE}")
        scope.implementation("androidx.lifecycle:lifecycle-service:${Versions.LIFECYCLE}")
        scope.implementation("androidx.lifecycle:lifecycle-process:${Versions.LIFECYCLE}")
        scope.implementation("androidx.fragment:fragment:${Versions.FRAGMENT}")
        scope.implementation("androidx.fragment:fragment-ktx:${Versions.FRAGMENT}")
        scope.implementation("androidx.preference:preference:${Versions.PREFERENCE}")
        scope.implementation("androidx.lifecycle:lifecycle-common-java8:${Versions.LIFECYCLE}")

        // Google Play Services
        scope.implementation("com.google.android.gms:play-services-base:${Versions.PLAY_SERVICES_BASE}")
        scope.implementation("com.google.android.play:feature-delivery:${Versions.PLAY_FEATURE_DELIVERY}")
        scope.implementation("com.google.android.play:feature-delivery-ktx:${Versions.PLAY_FEATURE_DELIVERY}")

        // Material Design Components
        scope.implementation("com.google.android.material:material:${Versions.MATERIAL}")

        // Kotlin Libraries
        scope.implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Versions.KOTLIN}")
        scope.implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.COROUTINES}")

        // Third-Party Libraries
        scope.implementation("com.github.adsamcik:Recycler:${Versions.COMPONENTS_RECYCLER}")
        scope.implementation("com.afollestad.material-dialogs:core:${Versions.DIALOGS}")
        scope.implementation("com.karumi:dexter:${Versions.DEXTER}")

        // Work Manager
        work(scope)
    }

    fun work(scope: DependencyHandlerScope) {
        scope.implementation("androidx.work:work-runtime-ktx:${Versions.WORK}")
        scope.androidTestImplementation("androidx.work:work-testing:${Versions.WORK}")
    }

    fun map(scope: DependencyHandlerScope) {
        scope.implementation("com.google.android.gms:play-services-maps:${Versions.MAPS}")
    }

    fun location(scope: DependencyHandlerScope) {
        scope.implementation("com.google.android.gms:play-services-location:${Versions.PLAY_LOCATION}")
    }

    fun draggable(scope: DependencyHandlerScope) {
        scope.implementation("com.github.adsamcik:Draggable:${Versions.COMPONENTS_DRAGGABLE}")
    }

    fun slider(scope: DependencyHandlerScope) {
        scope.implementation("com.github.adsamcik:slider:${Versions.COMPONENT_SLIDER}")
    }

    fun preference(scope: DependencyHandlerScope) {
        scope.implementation("androidx.preference:preference:${Versions.PREFERENCE}")
    }

    fun inputDialog(scope: DependencyHandlerScope) {
        scope.implementation("com.afollestad.material-dialogs:input:${Versions.DIALOGS}")
    }

    fun colorChooser(scope: DependencyHandlerScope) {
        scope.implementation("com.afollestad.material-dialogs:color:${Versions.DIALOGS}")
    }

    fun gpx(scope: DependencyHandlerScope) {
        scope.implementation("stax:stax-api:${Versions.STAX}")
        scope.implementation("com.fasterxml:aalto-xml:${Versions.XML}")
        scope.implementation("io.jenetics:jpx:${Versions.JPX}")
    }

    fun introduction(scope: DependencyHandlerScope) {
        scope.implementation("com.github.adsamcik:spotlight:${Versions.SPOTLIGHT}")
    }

    fun sunCalculator(scope: DependencyHandlerScope) {
        scope.implementation("org.shredzone.commons:commons-suncalc:${Versions.SUNCALC}")
    }

    fun paging(scope: DependencyHandlerScope) {
        scope.implementation("androidx.paging:paging-runtime:${Versions.PAGING}")
    }

    fun compose(scope: DependencyHandlerScope) {
        // Import the Compose BOM (Bill of Materials) to manage versions
        val composeBom = scope.platform("androidx.compose:compose-bom:${Versions.COMPOSE_BOM}")
        scope.implementation(composeBom)
        scope.androidTestImplementation(composeBom)

        // Core Compose libraries
        scope.implementation("androidx.compose.material3:material3")
        scope.implementation("androidx.compose.ui:ui-tooling-preview")
        scope.debugImplementation("androidx.compose.ui:ui-tooling")
        scope.implementation("androidx.activity:activity-compose:${Versions.ACTIVITY_COMPOSE}")
        scope.implementation("androidx.compose.material:material-icons-extended")

        // Additional Compose libraries
        scope.implementation("androidx.compose.animation:animation")
        scope.implementation("androidx.compose.animation:animation-graphics")
        scope.implementation("androidx.navigation:navigation-compose:${Versions.NAVIGATION_COMPOSE}")
        scope.implementation("androidx.lifecycle:lifecycle-viewmodel-compose:${Versions.LIFECYCLE}")
        scope.implementation("androidx.compose.runtime:runtime")
        scope.implementation("androidx.compose.runtime:runtime-livedata")
        scope.implementation("androidx.constraintlayout:constraintlayout-compose:${Versions.CONSTRAINT_LAYOUT_COMPOSE}")

        // Testing
        scope.androidTestImplementation("androidx.compose.ui:ui-test-junit4")
        scope.debugImplementation("androidx.compose.ui:ui-test-manifest")

        // Updated Accompanist libraries
        scope.implementation("com.google.accompanist:accompanist-pager:${Versions.ACCOMPANIST}")
        scope.implementation("com.google.accompanist:accompanist-swiperefresh:${Versions.ACCOMPANIST}")
    }

    fun test(scope: DependencyHandlerScope) {
        scope.androidTestImplementation("junit:junit:${Versions.Test.JUNIT}")
        scope.androidTestImplementation("androidx.test:runner:${Versions.Test.RUNNER}")
        scope.androidTestImplementation("androidx.test.uiautomator:uiautomator:${Versions.Test.UIAUTOMATOR}")
        scope.androidTestImplementation("androidx.test.ext:junit:${Versions.Test.JUNIT_EXT}")
        scope.androidTestImplementation("androidx.arch.core:core-testing:${Versions.Test.CORE_TESTING}")
        scope.androidTestImplementation("com.jraska.livedata:testing-ktx:${Versions.Test.TESTING_KTX}")
        scope.androidTestImplementation("androidx.test.espresso:espresso-core:${Versions.Test.ESPRESSO}")
        scope.androidTestImplementation("androidx.test.espresso:espresso-contrib:${Versions.Test.ESPRESSO}")
    }

    fun corePlugins(scope: org.gradle.plugin.use.PluginDependenciesSpec) {
        with(scope) {
            kotlin("android")
            id("org.jetbrains.kotlin.plugin.parcelize")
            id("com.google.devtools.ksp") version Versions.KSP
        }
    }

    fun composePlugins(scope: org.gradle.plugin.use.PluginDependenciesSpec) {
        with(scope) {
            id("org.jetbrains.kotlin.plugin.compose") version Dependencies.Versions.KOTLIN
        }
    }
}
