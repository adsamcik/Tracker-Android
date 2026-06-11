package com.adsamcik.tracker.activity.ski

import android.net.Uri
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@DisplayName("SkiInfrastructureImportResult")
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class SkiInfrastructureImportResultTest {

	@Nested
	@DisplayName("Success")
	inner class SuccessTests {

		@Test
		fun `stores path correctly`() {
			val result = SkiInfrastructureImportResult.Success("/data/ski.db")
			result.path shouldBe "/data/ski.db"
		}

		@Test
		fun `is instance of sealed class`() {
			val result = SkiInfrastructureImportResult.Success("/path")
			result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
		}

		@Test
		fun `equality based on path`() {
			val a = SkiInfrastructureImportResult.Success("/a")
			val b = SkiInfrastructureImportResult.Success("/a")
			a shouldBe b
		}

		@Test
		fun `copy can override path`() {
			val original = SkiInfrastructureImportResult.Success("/original")
			val modified = original.copy(path = "/modified")
			modified.path shouldBe "/modified"
		}
	}

	@Nested
	@DisplayName("SourceOpenFailed")
	inner class SourceOpenFailedTests {

		@Test
		fun `stores uri correctly`() {
			val uri = Uri.parse("content://test/file")
			val result = SkiInfrastructureImportResult.SourceOpenFailed(uri)
			result.uri shouldBe uri
		}

		@Test
		fun `is instance of sealed class`() {
			val uri = Uri.parse("content://test")
			val result = SkiInfrastructureImportResult.SourceOpenFailed(uri)
			result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
		}
	}

	@Nested
	@DisplayName("CopyFailed")
	inner class CopyFailedTests {

		@Test
		fun `stores cause correctly`() {
			val exception = RuntimeException("IO error")
			val result = SkiInfrastructureImportResult.CopyFailed(exception)
			result.cause shouldBe exception
		}

		@Test
		fun `is instance of sealed class`() {
			val result = SkiInfrastructureImportResult.CopyFailed(IllegalStateException())
			result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
		}

		@Test
		fun `cause message is preserved`() {
			val result = SkiInfrastructureImportResult.CopyFailed(
				RuntimeException("disk full")
			)
			result.cause.message shouldBe "disk full"
		}
	}

	@Nested
	@DisplayName("InvalidDatabase")
	inner class InvalidDatabaseTests {

		@Test
		fun `stores message correctly`() {
			val result = SkiInfrastructureImportResult.InvalidDatabase("bad schema")
			result.message shouldBe "bad schema"
		}

		@Test
		fun `is instance of sealed class`() {
			val result = SkiInfrastructureImportResult.InvalidDatabase("err")
			result.shouldBeInstanceOf<SkiInfrastructureImportResult>()
		}

		@Test
		fun `equality based on message`() {
			val a = SkiInfrastructureImportResult.InvalidDatabase("msg")
			val b = SkiInfrastructureImportResult.InvalidDatabase("msg")
			a shouldBe b
		}

		@Test
		fun `empty message is allowed`() {
			val result = SkiInfrastructureImportResult.InvalidDatabase("")
			result.message shouldBe ""
		}
	}

	@Nested
	@DisplayName("Sealed class exhaustiveness")
	inner class Exhaustiveness {

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
	}
}
