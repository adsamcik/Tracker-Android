object Android {
	const val min = 21
	const val compile = 28
	const val target = compile
}



object Libraries {
	object Versions {
		const val constraintLayout = "2.0.0-alpha5"
		const val ktx = "1.1.0-alpha05"
		const val appcompat = "1.1.0-alpha04"
		const val room = "2.1.0-alpha07"
		const val kotlin = "1.3.31"
		const val dokka = "0.9.18"
	}

	const val kotlinStdLib = "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Versions.kotlin}"
	const val appCompat = "androidx.appcompat:appcompat:${Versions.appcompat}"
	const val constraintLayout = "androidx.constraintlayout:constraintlayout:${Versions.constraintLayout}"
	const val ktxCore = "androidx.core:core-ktx:${Versions.ktx}"

	private const val roomBaseString = "androidx.room:room"

	const val roomRuntime = "${roomBaseString}-runtime:${Versions.room}"
	const val roomCompiler = "${roomBaseString}-compiler:${Versions.room}"
	const val roomKtx = "${roomBaseString}-ktx:${Versions.room}"

	object Test {
		const val room = "androidx.room:room-testing:${Versions.room}"
	}
}