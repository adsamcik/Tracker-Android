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

	lint {
		isCheckReleaseBuilds = true
		isAbortOnError = false
	}
}

dependencies {
	implementation(project(":sbase"))

	Dependencies.core(this)
	Dependencies.database(this)
	Dependencies.preference(this)

	Dependencies.test(this)
}
