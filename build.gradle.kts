buildscript {
	repositories {
		jcenter()
		google()
		maven("https://maven.fabric.io/public")
	}
	dependencies {
		classpath("com.android.tools.build:gradle:3.4.0")
		classpath("com.google.gms:google-services:4.2.0")
		classpath("com.google.firebase:firebase-plugins:1.2.0")
		classpath("com.google.gms:oss-licenses:0.9.2")
		classpath("io.fabric.tools:gradle:1.28.1")

		classpath("org.jetbrains.dokka:dokka-android-gradle-plugin:${Kotlin.dokkaVersion}")
		classpath(kotlin("gradle-plugin", Kotlin.version))
	}
}

plugins {
	id("com.github.ben-manes.versions") version ("0.21.0")
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
