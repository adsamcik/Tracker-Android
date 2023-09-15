import java.util.Locale

buildscript {
	repositories {
		google()
	}
	dependencies {
		classpath("com.android.tools.build:gradle:8.1.1")
		classpath("com.google.gms:google-services:4.4.0")
		classpath("com.google.android.gms:oss-licenses-plugin:0.10.6")
		classpath("com.google.firebase:firebase-crashlytics-gradle:${Dependencies.Versions.crashlyticsGradle}")

		classpath("org.jetbrains.dokka:dokka-android-gradle-plugin:${Dependencies.Versions.dokka}")
		classpath(kotlin("gradle-plugin", Dependencies.Versions.kotlin))
	}
}

allprojects {
	repositories {
		google()
		maven("https://jitpack.io")
		mavenCentral()
		jcenter()
	}
	gradle.projectsEvaluated {
		tasks.withType(JavaCompile::class.java) {
			options.compilerArgs = listOf("-Xlint:unchecked", "-Xlint:deprecation")
		}
	}
}

tasks.register("clean", Delete::class) {
	delete(rootProject.layout.buildDirectory)
}

plugins {
	// gradlew dependencyUpdates -Drevision=release
	id("com.github.ben-manes.versions") version ("0.48.0")
}

/**
 * Returns true if version is not considered stable.
 */
fun isStable(version: String): Boolean {
	val stableKeyword = listOf("RELEASE", "FINAL", "GA", "RC").any {
		version.uppercase(Locale.getDefault())
			.contains(it)
	}
	val regex = "^[0-9,.v-]+(-r)?$".toRegex()
	return stableKeyword || regex.matches(version)
}


tasks.named(
	"dependencyUpdates",
	com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask::class.java
).configure {
	resolutionStrategy {
		componentSelection {
			all {
				if (!isStable(candidate.version) && isStable(currentVersion)) {
					reject("Release candidate")
				}
			}
		}
	}
}
