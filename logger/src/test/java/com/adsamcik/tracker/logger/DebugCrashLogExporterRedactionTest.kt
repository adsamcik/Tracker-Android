package com.adsamcik.tracker.logger

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter

@DisplayName("DebugCrashLogExporter redaction")
class DebugCrashLogExporterRedactionTest {

	@Test
	fun `writeExceptionInfo redacts exception metadata before external debug export`() {
		val output = invokeWriterMethod("writeExceptionInfo") { writer ->
			val exception = RuntimeException(
				"failed at 52.52000 for alice@example.com",
				IllegalStateException("phone=+1 206-555-0199")
			)
			arrayOf(writer, Thread("worker-48.858844"), exception)
		}

		output.shouldNotContain("52.52000")
		output.shouldNotContain("48.858844")
		output.shouldNotContain("alice@example.com")
		output.shouldNotContain("206-555-0199")
		output.shouldContain("[REDACTED]")
	}

	@Test
	fun `writeStackTrace redacts throwable stack trace before external debug export`() {
		val output = invokeWriterMethod("writeStackTrace") { writer ->
			arrayOf(writer, RuntimeException("bad coordinate lat=48.858844 email alice@example.com"))
		}

		output.shouldNotContain("48.858844")
		output.shouldNotContain("alice@example.com")
		output.shouldContain("[REDACTED]")
	}

	private fun invokeWriterMethod(
		methodName: String,
		args: (PrintWriter) -> Array<Any>
	): String {
		val stringWriter = StringWriter()
		PrintWriter(stringWriter).use { writer ->
			val args = args(writer)
			val parameterTypes = when (methodName) {
				"writeExceptionInfo" -> arrayOf(PrintWriter::class.java, Thread::class.java, Throwable::class.java)
				"writeStackTrace" -> arrayOf(PrintWriter::class.java, Throwable::class.java)
				else -> error("Unsupported method $methodName")
			}
			val method = DebugCrashLogExporter::class.java.getDeclaredMethod(
				methodName,
				*parameterTypes
			)
			method.isAccessible = true
			method.invoke(DebugCrashLogExporter, *args)
			writer.flush()
		}
		return stringWriter.toString()
	}
}
