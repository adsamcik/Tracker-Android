import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    Dependencies.corePlugins(this)
}

android {
    compileSdk = Android.compile
    buildToolsVersion = Android.buildTools

    defaultConfig {
        minSdk = Android.min

	    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = Android.javaTarget
        targetCompatibility = Android.javaTarget
    }

    kotlin {
        jvmToolchain(Android.javaVersion)
    }

    buildTypes {
        getByName("debug") {
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
        }

        create("release_nominify") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }
    namespace = "com.adsamcik.tracker.impexp"
}

dependencies {
    implementation(project(":sbase"))
    implementation(project(":sutils"))
    implementation(project(":spreferences"))
    implementation(project(":logger"))

    Dependencies.database(this)
    Dependencies.gpx(this)

    Dependencies.core(this)
    Dependencies.test(this)
}
