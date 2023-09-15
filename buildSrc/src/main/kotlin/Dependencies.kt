import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.kotlin

/**
 * Object with common dependency groups for easy and central dependency management.
 */
@Suppress("TooManyFunctions", "SpellCheckingInspection")
object Dependencies {
    /**
     * Object containing versions for various dependencies.
     * Dependencies that are not project are required to be internal.
     */
    object Versions {
        internal const val constraintLayout = "2.1.4"
        internal const val coreKtx = "1.9.0"
        internal const val appcompat = "1.6.1"
        internal const val fragment = "1.6.1"
        const val dokka: String = "0.9.18"
        internal const val moshi = "1.15.0"
        internal const val work = "2.8.1"
        internal const val lifecycle = "2.6.2"
        internal const val preference = "1.2.1"
        internal const val material = "1.9.0"
        internal const val desugar = "2.0.3"

        const val kotlin: String = "1.9.10"
        internal const val coroutines = "1.7.3"

        internal const val sqlite = "3.43.0"
        internal const val room = "2.5.2"

        internal const val recyclerView = "1.3.1"
        internal const val paging = "3.2.1"

        internal const val firebaseBom = "32.2.3"
        internal const val crashlytics = "18.4.1"
        const val crashlyticsGradle: String = "2.9.9"

        internal const val playServicesBase = "18.2.0"
        internal const val playLocation = "21.0.1"
        internal const val playCore = "1.10.3"
        internal const val maps = "18.1.0"

        internal const val stax = "1.0.1"
        internal const val jpx = "3.0.1"
        internal const val xml = "1.3.2"

        internal const val spotlight = "2.2.3"
        internal const val dialogs = "3.3.0"

        internal const val componentsRecycler = "1.0.0"
        internal const val componentsDraggable = "1.0.4"
        internal const val componentSlider = "2.1.0"

        internal const val dexter = "6.2.3"

        internal const val suncalc = "3.7"

        /**
         * Testing specific dependencies
         */
        internal object Test {
            internal const val androidxTest: String = "1.4.0"
            internal const val espresso: String = "3.5.1"
            internal const val coreTesting: String = "2.2.0"
            internal const val testingKtx: String = "1.3.0"
        }
    }

    private fun DependencyHandler.api(name: String) = add("api", name)
    private fun DependencyHandler.implementation(name: Any) = add("implementation", name)
    private fun DependencyHandler.ksp(name: String) = add("ksp", name)
    private fun DependencyHandler.androidTestImplementation(name: String) =
        add("androidTestImplementation", name)

    private fun DependencyHandler.compileOnly(name: String) = add("compileOnly", name)
    private fun DependencyHandler.coreLibraryDesugaring(dependencyNotation: Any) =
        add("coreLibraryDesugaring", dependencyNotation)

    fun json(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.squareup.moshi:moshi:${Versions.moshi}")
            ksp("com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}")
        }
    }

    fun database(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            val roomBaseString = "androidx.room:room"
            api("$roomBaseString-runtime:${Versions.room}")
            ksp("$roomBaseString-compiler:${Versions.room}")
            implementation("$roomBaseString-ktx:${Versions.room}")
            implementation("$roomBaseString-paging:${Versions.room}")
            implementation("com.github.requery:sqlite-android:${Versions.sqlite}")
            androidTestImplementation("androidx.room:room-testing:${Versions.room}")
        }
    }

    fun core(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            // Fix for compile error caused by missing annotation in JDK9+
            implementation("com.github.pengrad:jdk9-deps:1.0")
            coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${Versions.desugar}")

            implementation("androidx.appcompat:appcompat:${Versions.appcompat}")
            implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Versions.kotlin}")
            implementation("androidx.core:core-ktx:${Versions.coreKtx}")
            implementation("androidx.constraintlayout:constraintlayout:${Versions.constraintLayout}")

            // Recycler
            implementation("com.github.adsamcik:Recycler:${Versions.componentsRecycler}")
            implementation("androidx.recyclerview:recyclerview:${Versions.recyclerView}")

            implementation("androidx.lifecycle:lifecycle-runtime-ktx:${Versions.lifecycle}")
            implementation("androidx.lifecycle:lifecycle-service:${Versions.lifecycle}")
            implementation("androidx.lifecycle:lifecycle-process:${Versions.lifecycle}")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}")
            implementation("androidx.fragment:fragment:${Versions.fragment}")
            implementation("androidx.fragment:fragment-ktx:${Versions.fragment}")
            implementation("com.google.android.material:material:${Versions.material}")
            implementation("com.google.android.gms:play-services-base:${Versions.playServicesBase}")
            implementation("com.google.android.play:core:${Versions.playCore}")

            implementation("com.afollestad.material-dialogs:core:${Versions.dialogs}")
            // implementation("com.codezjx.library:andlinker:0.7.2")

            implementation("com.karumi:dexter:${Versions.dexter}")

            work(this)

            implementation("androidx.lifecycle:lifecycle-common-java8:${Versions.lifecycle}")

        }
    }

    fun corePlugins(scope: org.gradle.plugin.use.PluginDependenciesSpec) {
        with(scope) {
            kotlin("android")
            id("org.jetbrains.kotlin.plugin.parcelize")
            id("com.google.devtools.ksp").version("1.9.10-1.0.13")
        }
    }

    private fun work(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("androidx.work:work-runtime-ktx:${Versions.work}")
            androidTestImplementation("androidx.work:work-testing:${Versions.work}")
        }
    }

    fun map(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.google.android.gms:play-services-maps:${Versions.maps}")
        }
    }

    fun location(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.google.android.gms:play-services-location:${Versions.playLocation}")
        }
    }

    fun crashlytics(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation(platform("com.google.firebase:firebase-bom:${Versions.firebaseBom}"))
            implementation("com.google.firebase:firebase-crashlytics-ktx:${Versions.crashlytics}")
        }
    }

    fun draggable(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.github.adsamcik:Draggable:${Versions.componentsDraggable}")
        }
    }

    fun slider(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.github.adsamcik:slider:${Versions.componentSlider}")
        }
    }

    fun preference(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("androidx.preference:preference:${Versions.preference}")
        }
    }

    fun inputDialog(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.afollestad.material-dialogs:input:${Versions.dialogs}")
        }
    }

    fun colorChooser(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.afollestad.material-dialogs:color:${Versions.dialogs}")
        }
    }

    fun gpx(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("stax:stax-api:${Versions.stax}")
            implementation("com.fasterxml:aalto-xml:${Versions.xml}")
            implementation("io.jenetics:jpx:${Versions.jpx}")
        }
    }

    fun introduction(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("com.github.adsamcik:spotlight:${Versions.spotlight}")
        }
    }

    fun sunCalculator(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("org.shredzone.commons:commons-suncalc:${Versions.suncalc}")
        }
    }

    fun paging(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            implementation("androidx.paging:paging-runtime:${Versions.paging}")
        }
    }

    fun test(dependencyHandler: DependencyHandler) {
        with(dependencyHandler) {
            androidTestImplementation("junit:junit:4.12")
            androidTestImplementation("androidx.test:runner:${Versions.Test.androidxTest}")
            androidTestImplementation("androidx.test:rules:${Versions.Test.androidxTest}")
            androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
            androidTestImplementation("androidx.test.ext:junit:1.1.2")
            androidTestImplementation("androidx.arch.core:core-testing:${Versions.Test.coreTesting}")
            androidTestImplementation("com.jraska.livedata:testing-ktx:${Versions.Test.testingKtx}")
            androidTestImplementation("androidx.test.espresso:espresso-core:${Versions.Test.espresso}")
            androidTestImplementation("androidx.test.espresso:espresso-contrib:${Versions.Test.espresso}")
        }
    }
}
