plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION

	defaultConfig {
		minSdk = Android.MIN_VERSION
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
	}

	buildTypes {
		create("release_nominify") {
			isMinifyEnabled = false
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	namespace = "com.adsamcik.tracker.network"
}

dependencies {
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	// OkHttp is `api` because [OkHttpBackedGateway.okHttpCallFactory] returns
	// `okhttp3.Call.Factory` — that type must be visible to consumers like
	// `:map` (for `HttpRequestUtil.setOkHttpClient`) and any future module that
	// wants to integrate at the OkHttp level. The convention (enforced by code
	// review, future lint rule) is that NO module other than `:network` may
	// construct an `OkHttpClient` directly — get the call factory from the
	// gateway instead so requests share the kill-switch + allowlist + rate-limit
	// interceptor chain.
	api(libs.okhttp)

	// Unit Tests
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testRuntimeOnly(libs.junit5.vintage.engine)
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation(libs.junit4)
	testImplementation(libs.mockk)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.turbine)
	testImplementation(libs.okhttp.mockwebserver)
	// okhttp-tls provides HeldCertificate / HandshakeCertificates so MockWebServer
	// can serve HTTPS — required for end-to-end testing through the gateway's
	// HttpsOnlyInterceptor without disabling the HTTPS guard.
	testImplementation(libs.okhttp.tls)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}
