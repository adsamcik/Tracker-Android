import java.util.Locale

buildscript {
	configurations.configureEach {
		resolutionStrategy.eachDependency {
			if (requested.group == "com.squareup" && requested.name == "javapoet") {
				useVersion("1.13.0")
				because("Hilt 2.59.2 running on Kotlin 2.2.20 needs the canonicalName API from JavaPoet 1.13.0 on the buildscript classpath")
			}
		}
	}
	dependencies {
		classpath("com.squareup:javapoet:1.13.0")
	}
}

plugins {
	// gradlew dependencyUpdates -Drevision=release
	alias(libs.plugins.benmanes.versions)
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.android.dynamic.feature) apply false
	alias(libs.plugins.android.test) apply false
	alias(libs.plugins.kotlin.android) apply false
	alias(libs.plugins.kotlin.parcelize) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.kotlin.compose) apply false
	alias(libs.plugins.ksp) apply false
}

allprojects {
	repositories {
		google()
		maven("https://jitpack.io")
		mavenCentral()
	}
	gradle.projectsEvaluated {
		tasks.withType(JavaCompile::class.java) {
			options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
		}
	}
	
	// Configure Kotlin compiler options for all projects
	tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
		compilerOptions {
			freeCompilerArgs.add("-Xannotation-default-target=param-property")
			freeCompilerArgs.add("-Xmetadata-version=2.1.0")
		}
	}
}

tasks.register("clean", Delete::class) {
	delete(rootProject.layout.buildDirectory)
}

/**
 * Returns true if version is considered stable.
 */
fun isStable(version: String): Boolean {
	val stableKeyword = listOf("RELEASE", "FINAL", "GA").any {
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
			all(Action<com.github.benmanes.gradle.versions.updates.resolutionstrategy.ComponentSelectionWithCurrent> {
				if (!isStable(candidate.version) && isStable(currentVersion)) {
					reject("Release candidate")
				}
			})
		}
	}
}
