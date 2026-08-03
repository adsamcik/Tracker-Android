pluginManagement {
	includeBuild("build-logic")
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		// An exact locally published Tracebox build may override the immutable GitHub package.
		// Restrict Maven Local to the Tracebox group so it cannot affect other dependencies.
		mavenLocal {
			content {
				includeGroup("io.github.tracebox")
			}
		}
		maven {
			name = "TraceboxGitHubPackages"
			url = uri("https://maven.pkg.github.com/adsamcik/tracebox")
			credentials {
				username = providers.gradleProperty("gpr.user")
					.orElse(providers.environmentVariable("GITHUB_ACTOR"))
					.orNull
				password = providers.gradleProperty("gpr.key")
					.orElse(providers.environmentVariable("GITHUB_TOKEN"))
					.orNull
			}
			content {
				includeGroup("io.github.tracebox")
			}
		}
		google()
		maven("https://jitpack.io")
		mavenCentral()
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include(":app")
include(":tracker:api", ":tracker:control", ":tracker:engine")
project(":tracker:api").projectDir = file("tracker/api-module")
include(
	":core:base",
	":core:common",
	":core:model",
	":core:ui",
	":core:diagnostics",
	":core:network",
	":core:sqlite-runtime",
	":core:testing",
)
include(":data:preferences")
include(":stats:api", ":stats:engine", ":stats:data")
include(":domain:points", ":domain:osm", ":domain:geocoder")
include(":sensor:activity-api", ":sensor:activity")
include(":feature:map:api", ":feature:map", ":feature:statistics:api", ":feature:statistics", ":feature:dashboard:api", ":feature:dashboard", ":feature:game:api", ":feature:game", ":feature:activity", ":feature:import-export", ":feature:tracker")
include(":tools:ski-data-generator")
