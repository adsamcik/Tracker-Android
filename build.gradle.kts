buildscript {
	repositories {
		jcenter()
		google()
	}
	dependencies {
		classpath("com.android.tools.build:gradle:4.0.0")
		classpath("com.google.gms:google-services:4.3.3")
		classpath("com.google.android.gms:oss-licenses-plugin:0.10.2")
		classpath("com.google.firebase:firebase-crashlytics-gradle:2.1.1")

		classpath("org.jetbrains.dokka:dokka-android-gradle-plugin:${Dependencies.Versions.dokka}")
		classpath(kotlin("gradle-plugin", Dependencies.Versions.kotlin))
	}
}

plugins {
	// gradlew dependencyUpdates -Drevision=release
	id("com.github.ben-manes.versions") version ("0.28.0")
}

allprojects {
	repositories {
		jcenter()
		google()
		maven("https://jitpack.io")
		maven("https://dl.bintray.com/adsamcik/android-forks")
		maven("https://kotlin.bintray.com/kotlinx/")
		mavenCentral()
	}
	gradle.projectsEvaluated {
		tasks.withType(JavaCompile::class.java) {
			options.compilerArgs = listOf("-Xlint:unchecked", "-Xlint:deprecation")
		}
	}

}

tasks.register("clean", Delete::class) {
	delete(rootProject.buildDir)
}
