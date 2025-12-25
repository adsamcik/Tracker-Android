plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    compileSdk = libs.versions.android.compile.get().toInt()
    buildToolsVersion = libs.versions.android.build.tools.get()

    defaultConfig {
        minSdk = libs.versions.android.min.get().toInt()

	    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
        getByName("debug") {
            java.srcDir("build/generated/source/proto/debug/java")
        }
        getByName("release") {
            java.srcDir("build/generated/source/proto/release/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    kotlin {
        jvmToolchain(libs.versions.java.get().toInt())
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
            isMinifyEnabled = false
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
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.core)
    implementation(libs.protobuf.java)

    // Compose
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.runtime)
    implementation(libs.constraintlayout.compose)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
	// Accompanist removed

    // DB
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.sqlite.android)
    androidTestImplementation(libs.androidx.room.testing)

    // GPX
    implementation(libs.stax.api)
    implementation(libs.aalto.xml)
    implementation(libs.jpx)

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

    // Tests
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.arch.core.testing)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(project(":testing-common"))

    // JVM unit tests
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") }
        }
    }
}

afterEvaluate {
    tasks.forEach { task ->
        if (task.name.startsWith("ksp") && task.name.endsWith("Kotlin")) {
            if (task.name.contains("Debug")) {
                task.dependsOn("generateDebugProto")
            } else if (task.name.contains("Release")) {
                task.dependsOn("generateReleaseProto")
            }
        }
    }
}
