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
        targetSdk = Android.target

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

    tasks.withType<KotlinCompile> {
        with(kotlinOptions) {
            jvmTarget = Android.jvmTarget
        }
    }

    buildTypes {
        getByName("debug") {
            isTestCoverageEnabled = true
        }

        create("release_nominify") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
        }
    }

    lint {
        isCheckReleaseBuilds = true
        isAbortOnError = false
    }
}

dependencies {
    implementation(project(":sbase"))
    implementation(project(":sutils"))
    implementation(project(":spreferences"))
    implementation(project(":logger"))

    Dependencies.database(this)
    Dependencies.gpx(this)

    Dependencies.core(this)
    Dependencies.crashlytics(this)
    Dependencies.test(this)
}
