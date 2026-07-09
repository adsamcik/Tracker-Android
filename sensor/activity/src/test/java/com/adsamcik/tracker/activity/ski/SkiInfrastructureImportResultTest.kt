package com.adsamcik.tracker.activity.ski

import android.net.Uri
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SkiInfrastructureImportResultTest {

	// region Success
	@Test
	fun `success stores path correctly`() {
		val result = SkiInfrastructureImportResult.Success("/data/ski.db")
		result.path shouldBe "/data/ski.db"
	}

	@Test
	fun `success is instance of sealed class`() {
		val result = SkiInfrastructureImportResult.Success("/path")
		result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
	}

	@Test
	fun `success equality based on path`() {
		val a = SkiInfrastructureImportResult.Success("/a")
		val b = SkiInfrastructureImportResult.Success("/a")
		a shouldBe b
	}

	@Test
	fun `success copy can override path`() {
		val original = SkiInfrastructureImportResult.Success("/original")
		val modified = original.copy(path = "/modified")
		modified.path shouldBe "/modified"
	}
	// endregion

	// region SourceOpenFailed
	@Test
	fun `sourceOpenFailed stores uri correctly`() {
		val uri = Uri.parse("content://test/file")
		val result = SkiInfrastructureImportResult.SourceOpenFailed(uri)
		result.uri shouldBe uri
	}

	@Test
	fun `sourceOpenFailed is instance of sealed class`() {
		val uri = Uri.parse("content://test")
		val result = SkiInfrastructureImportResult.SourceOpenFailed(uri)
		result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
	}
	// endregion

	// region CopyFailed
	@Test
	fun `copyFailed stores cause correctly`() {
		val exception = RuntimeException("IO error")
		val result = SkiInfrastructureImportResult.CopyFailed(exception)
		result.cause shouldBe exception
	}

	@Test
	fun `copyFailed is instance of sealed class`() {
		val result = SkiInfrastructureImportResult.CopyFailed(IllegalStateException())
		result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
	}

	@Test
	fun `copyFailed cause message is preserved`() {
		val result = SkiInfrastructureImportResult.CopyFailed(
			RuntimeException("disk full")
		)
		result.cause.message shouldBe "disk full"
	}
	// endregion

	// region InvalidDatabase
	@Test
	fun `invalidDatabase stores message correctly`() {
		val result = SkiInfrastructureImportResult.InvalidDatabase("bad schema")
		result.message shouldBe "bad schema"
	}

	@Test
	fun `invalidDatabase is instance of sealed class`() {
		val result = SkiInfrastructureImportResult.InvalidDatabase("err")
		result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
	}

	@Test
	fun `invalidDatabase equality based on message`() {
		val a = SkiInfrastructureImportResult.InvalidDatabase("msg")
		val b = SkiInfrastructureImportResult.InvalidDatabase("msg")
		a shouldBe b
	}

	@Test
	fun `invalidDatabase empty message is allowed`() {
		val result = SkiInfrastructureImportResult.InvalidDatabase("")
		result.message shouldBe ""
	}
	// endregion

	// region Sealed class exhaustiveness
	@Test
	fun `when expression covers all variants`() {
		val results = listOf<SkiInfrastructureImportResult>(
			SkiInfrastructureImportResult.Success("/path"),
			SkiInfrastructureImportResult.SourceOpenFailed(Uri.parse("content://x")),
			SkiInfrastructureImportResult.CopyFailed(RuntimeException()),
			SkiInfrastructureImportResult.InvalidDatabase("err"),
		)

		results.forEach { result ->
			when (result) {
				is SkiInfrastructureImportResult.Success -> result.path shouldBe "/path"
				is SkiInfrastructureImportResult.SourceOpenFailed -> {}
				is SkiInfrastructureImportResult.CopyFailed -> {}
				is SkiInfrastructureImportResult.InvalidDatabase -> result.message shouldBe "err"
			}
		}
	}
	// endregion
}
