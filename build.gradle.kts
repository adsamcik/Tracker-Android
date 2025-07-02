import java.util.Locale

buildscript {
	repositories {
		google()
	}
	dependencies {
		classpath("com.android.tools.build:gradle:8.11.0")
		classpath("com.google.gms:google-services:4.4.3")
		classpath("com.google.android.gms:oss-licenses-plugin:0.10.6")
		classpath("com.google.android.libraries.mapsplatform.secrets-gradle-plugin:secrets-gradle-plugin:2.0.1")
		classpath("org.jetbrains.dokka:dokka-android-gradle-plugin:${Dependencies.Versions.DOKKA}")
		classpath(kotlin("gradle-plugin", Dependencies.Versions.KOTLIN))
	}

	plugins {
		id("com.google.devtools.ksp") version Dependencies.Versions.KSP apply false

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
	
	// Configure Kotlin compiler options for all projects
	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
		compilerOptions {
			freeCompilerArgs.add("-Xannotation-default-target=param-property")
		}
	}
}

tasks.register("clean", Delete::class) {
	delete(rootProject.layout.buildDirectory)
}

plugins {
	// gradlew dependencyUpdates -Drevision=release
	id("com.github.ben-manes.versions") version ("0.52.0")
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
