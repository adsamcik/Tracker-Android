package com.adsamcik.tracker.impexp.exporter.research

import android.content.Context
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.shared.model.LocationSample
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Encrypted research trace exporter")
class EncryptedResearchTraceExporterTest {

	@Test
	fun `round trip authenticates header and recovers delegate payload`() = runTest {
		val plaintext = "precise trace: 50.087465,14.421254".toByteArray()
		val passphrase = "correct horse battery staple".toCharArray()
		val exporter = exporterWriting(plaintext, passphrase.copyOf())
		val output = ByteArrayOutputStream()

		val result = exporter.export(mockk(), emptySequence(), output, 10L..20L)

		result shouldBe ExportResult.Success
		decrypt(output.toByteArray(), passphrase) shouldBe plaintext
	}

	@Test
	fun `ciphertext does not contain the plaintext byte sequence`() = runTest {
		val plaintext = "home-location=50.087465,14.421254".toByteArray()
		val output = ByteArrayOutputStream()

		exporterWriting(plaintext, "a sufficiently long passphrase".toCharArray())
			.export(mockk(), emptySequence(), output, null)

		indexOf(output.toByteArray(), plaintext) shouldBe -1
	}

	@Test
	fun `tampering with ciphertext fails authentication`() = runTest {
		val passphrase = "another sufficiently long passphrase".toCharArray()
		val output = ByteArrayOutputStream()
		exporterWriting("sensitive".toByteArray(), passphrase.copyOf())
			.export(mockk(), emptySequence(), output, null)
		val tampered = output.toByteArray().also { bytes ->
			bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
		}

		shouldThrow<AEADBadTagException> {
			decrypt(tampered, passphrase)
		}
	}

	@Test
	fun `owned passphrase is overwritten after export`() = runTest {
		val ownedPassphrase = "this array must be destroyed".toCharArray()
		val exporter = EncryptedResearchTraceExporter(
			delegate = PayloadExporter(byteArrayOf(1, 2, 3)),
			passphraseProvider = { ownedPassphrase },
			pbkdf2Iterations = ResearchTraceEnvelope.MIN_PBKDF2_ITERATIONS,
		)

		exporter.export(mockk(), emptySequence(), ByteArrayOutputStream(), null)

		ownedPassphrase.all { it == '\u0000' } shouldBe true
	}

	@Test
	fun `wrapper preserves delegate date range capability`() {
		val delegate = PayloadExporter(byteArrayOf(), canSelectDateRange = false)
		val exporter = EncryptedResearchTraceExporter(
			delegate = delegate,
			passphraseProvider = { "a sufficiently long passphrase".toCharArray() },
		)

		exporter.canSelectDateRange shouldBe false
		exporter.mimeType shouldBe "application/vnd.tracker.research-trace"
		exporter.extension shouldBe "trackertrace"
	}

	private fun exporterWriting(
		payload: ByteArray,
		ownedPassphrase: CharArray,
	): EncryptedResearchTraceExporter = EncryptedResearchTraceExporter(
		delegate = PayloadExporter(payload),
		passphraseProvider = { ownedPassphrase },
		pbkdf2Iterations = ResearchTraceEnvelope.MIN_PBKDF2_ITERATIONS,
	)

	private class PayloadExporter(
		private val payload: ByteArray,
		override val canSelectDateRange: Boolean = true,
	) : Exporter {
		override val mimeType: String = "application/octet-stream"
		override val extension: String = "bin"

		override suspend fun export(
			context: Context,
			locationData: Sequence<LocationSample>,
			outputStream: OutputStream,
			dateRange: LongRange?,
		): ExportResult {
			outputStream.write(payload)
			return ExportResult.Success
		}
	}

	private fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
		val input = DataInputStream(ByteArrayInputStream(envelope))
		val magic = ByteArray(ResearchTraceEnvelope.MAGIC.size).also(input::readFully)
		magic shouldBe ResearchTraceEnvelope.MAGIC
		input.readUnsignedByte() shouldBe ResearchTraceEnvelope.VERSION
		input.readUnsignedByte() shouldBe ResearchTraceEnvelope.KDF_PBKDF2_SHA256
		input.readUnsignedByte() shouldBe ResearchTraceEnvelope.CIPHER_AES_256_GCM
		val saltLength = input.readUnsignedByte()
		val nonceLength = input.readUnsignedByte()
		val iterations = input.readInt()
		val salt = ByteArray(saltLength).also(input::readFully)
		val nonce = ByteArray(nonceLength).also(input::readFully)
		val headerLength = envelope.size - input.available()
		val header = envelope.copyOfRange(0, headerLength)
		val ciphertext = envelope.copyOfRange(headerLength, envelope.size)

		val specification = PBEKeySpec(passphrase, salt, iterations, ResearchTraceEnvelope.AES_KEY_LENGTH_BITS)
		val keyBytes = try {
			SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
				.generateSecret(specification)
				.encoded
		} finally {
			specification.clearPassword()
		}
		return try {
			Cipher.getInstance("AES/GCM/NoPadding").run {
				init(
					Cipher.DECRYPT_MODE,
					SecretKeySpec(keyBytes, "AES"),
					GCMParameterSpec(ResearchTraceEnvelope.GCM_TAG_LENGTH_BITS, nonce),
				)
				updateAAD(header)
				doFinal(ciphertext)
			}
		} finally {
			keyBytes.fill(0)
		}
	}

	private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
		if (needle.isEmpty()) return 0
		for (start in 0..haystack.size - needle.size) {
			if (needle.indices.all { offset -> haystack[start + offset] == needle[offset] }) {
				return start
			}
		}
		return -1
	}
}
