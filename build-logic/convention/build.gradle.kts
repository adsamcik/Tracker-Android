plugins {
    `kotlin-dsl`
}

group = "com.adsamcik.tracker.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    implementation(libs.protobuf.gradlePlugin)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "tracker.android.application"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "tracker.android.library"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "tracker.android.compose"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "tracker.android.hilt"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "tracker.android.room"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidRoomConventionPlugin"
        }
        register("androidTest") {
            id = "tracker.android.test"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidTestConventionPlugin"
        }
        register("androidInstrumentedTest") {
            id = "tracker.android.instrumented-test"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidInstrumentedTestConventionPlugin"
        }
        register("androidProtobuf") {
            id = "tracker.android.protobuf"
            implementationClass = "com.adsamcik.tracker.buildlogic.AndroidProtobufConventionPlugin"
        }
        register("kotlinMultiplatformAndroidJvm") {
            id = "tracker.kotlin.multiplatform.android-jvm"
            implementationClass =
                "com.adsamcik.tracker.buildlogic.KotlinMultiplatformAndroidJvmConventionPlugin"
        }
        register("rootVerification") {
            id = "tracker.root.verification"
            implementationClass = "com.adsamcik.tracker.buildlogic.RootVerificationConventionPlugin"
        }
    }
}
