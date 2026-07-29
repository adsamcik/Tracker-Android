plugins {
	id("org.jetbrains.kotlin.jvm")
	application
}

dependencies {
	implementation(libs.sqlite.jdbc)
	implementation(libs.gson)
}

application {
	mainClass.set("SkiDataGeneratorKt")
}

tasks.named<JavaExec>("run") {
	// Default input path; override with --args="path/to/lifts.geojson"
	val defaultInput = project.file("input/lifts.geojson").absolutePath
	args = listOf(defaultInput)
	workingDir = project.projectDir
}

kotlin {
	jvmToolchain(17)
}
