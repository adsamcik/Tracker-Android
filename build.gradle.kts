buildscript {
	repositories {
		jcenter()
		google()
	}
	dependencies {
		classpath("com.android.tools.build:gradle:4.1.2")
		classpath("com.google.gms:google-services:4.3.5")
		classpath("com.google.android.gms:oss-licenses-plugin:0.10.2")
		classpath("com.google.firebase:firebase-crashlytics-gradle:${Dependencies.Versions.crashlyticsGradle}")

		classpath("org.jetbrains.dokka:dokka-android-gradle-plugin:${Dependencies.Versions.dokka}")
		classpath(kotlin("gradle-plugin", Dependencies.Versions.kotlin))
	}
}

plugins {
	// gradlew dependencyUpdates -Drevision=release
	id("com.github.ben-manes.versions") version ("0.36.0")
}

allprojects {
	repositories {
		jcenter()
		google()
		maven("https://jitpack.io")
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

fun isNonStable(version: String): Boolean {
	val stableKeyword = listOf("RELEASE", "FINAL", "GA", "RC").any {
		version.toUpperCase()
				.contains(it)
	}
	val regex = "^[0-9,.v-]+(-r)?$".toRegex()
	val isStable = stableKeyword || regex.matches(version)
	return isStable.not()
}


tasks.named(
		"dependencyUpdates",
		com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask::class.java
).configure {
	// Example 1: reject all non stable versions
	rejectVersionIf {
		isNonStable(candidate.version)
	}

	// Example 2: disallow release candidates as upgradable versions from stable versions
	rejectVersionIf {
		isNonStable(candidate.version) && !isNonStable(currentVersion)
	}

	// Example 3: using the full syntax
	resolutionStrategy {
		componentSelection {
			all {
				if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
					reject("Release candidate")
				}
			}
		}
	}
}
