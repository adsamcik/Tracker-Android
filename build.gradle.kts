buildscript {
	repositories {
		jcenter()
		google()
		maven("https://maven.fabric.io/public")
	}
	dependencies {
		classpath("com.android.tools.build:gradle:3.5.0")
		classpath("com.google.gms:google-services:4.3.1")
		classpath("com.google.gms:oss-licenses:0.9.2")
		classpath("io.fabric.tools:gradle:1.31.0")

		classpath("org.jetbrains.dokka:dokka-android-gradle-plugin:${Dependencies.Versions.dokka}")
		classpath(kotlin("gradle-plugin", Dependencies.Versions.kotlin))
	}
}

plugins {
	// gradlew dependencyUpdates -Drevision=release
	id("com.github.ben-manes.versions") version ("0.24.0")
}

allprojects {
	repositories {
		jcenter()
		google()
		maven("https://jitpack.io")
		maven("https://dl.bintray.com/adsamcik/android-forks")
		maven("https://kotlin.bintray.com/kotlinx/")
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
