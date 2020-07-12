import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    Dependencies.corePlugins(this)
}

android {
    compileSdkVersion(Android.compile)
    buildToolsVersion(Android.buildTools)

    defaultConfig {
        minSdkVersion(Android.min)
        targetSdkVersion(Android.target)
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        kapt {
            arguments {
                this.arg("room.schemaLocation", "$projectDir/schemas")
            }
        }
    }

    sourceSets {
        this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }

    compileOptions {
        coreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<KotlinCompile> {
        with(kotlinOptions) {
            jvmTarget = "1.8"
            freeCompilerArgs = listOf("-XXLanguage:+InlineClasses")
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

    lintOptions {
        isCheckReleaseBuilds = true
        isAbortOnError = false
    }
}

dependencies {
    implementation(project(":sbase"))
	implementation(project(":spreferences"))
    implementation("org.shredzone.commons:commons-suncalc:3.3")

    Dependencies.core(this)
    Dependencies.moshi(this)
    Dependencies.database(this)
    Dependencies.crashlytics(this)
    Dependencies.location(this)
    Dependencies.preference(this)
    Dependencies.introduction(this)

    Dependencies.test(this)
}
