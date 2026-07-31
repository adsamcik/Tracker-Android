package com.adsamcik.tracker.impexp.exporter.research

import android.content.Context
import com.adsamcik.tracker.impexp.R
import com.adsamcik.tracker.impexp.exporter.ExportResult
import com.adsamcik.tracker.impexp.exporter.Exporter
import com.adsamcik.tracker.shared.base.misc.LocalizedString
import com.adsamcik.tracker.shared.model.LocationSample
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException

/**
 * Debug-only [Exporter] decorator for producing encrypted research traces.
 *
 * This class deliberately lives in `src/debug`: it is absent from release artifacts and is not
 * registered in the user-facing format registry. Research tooling must explicitly construct it
 * around the desired delegate exporter, for example a JSON exporter.
 *
 * The delegate writes directly into an AES-256-GCM stream, so plaintext is never staged in a file.
 * A fresh random salt and nonce are generated for every export. The key is derived with
 * PBKDF2-HMAC-SHA256. Header bytes are authenticated as GCM additional authenticated data.
 *
 * [passphraseProvider] must return a newly owned character array. It is requested only when an
 * export starts and is overwritten before [export] returns (successfully or otherwise).
 */
class EncryptedResearchTraceExporter(
	private val delegate: Exporter,
	private val passphraseProvider: suspend () -> CharArray,
	private val secureRandom: SecureRandom = SecureRandom(),
	private val pbkdf2Iterations: Int = ResearchTraceEnvelope.DEFAULT_PBKDF2_ITERATIONS,
) : Exporter {

	override val canSelectDateRange: Boolean = delegate.canSelectDateRange
	override val mimeType: String = "application/vnd.tracker.research-trace"
	override val extension: String = "trackertrace"

	override suspend fun export(
		context: Context,
		locationData: Sequence<LocationSample>,
		outputStream: OutputStream,
		dateRange: LongRange?,
	): ExportResult {
		var passphrase: CharArray? = null
		return try {
			val ownedPassphrase = passphraseProvider()
			passphrase = ownedPassphrase
			ResearchTraceEnvelope.validatePassphrase(ownedPassphrase)
			ResearchTraceEnvelope.openEncryptingStream(
				destination = outputStream,
				passphrase = ownedPassphrase,
				secureRandom = secureRandom,
				pbkdf2Iterations = pbkdf2Iterations,
			).use { encryptedStream ->
				delegate.export(context, locationData, encryptedStream, dateRange)
			}
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			ExportResult.Error(
				LocalizedString(
					R.string.export_error_with_reason,
					exception.message ?: "Failed to encrypt research trace",
				),
			)
		} finally {
			passphrase?.fill('\u0000')
		}
	}
}

/**
 * Versioned binary envelope used by [EncryptedResearchTraceExporter].
 *
 * Version 1 layout, in network byte order:
 *
 * ```text
 * magic[8] | version[1] | kdf[1] | cipher[1] | saltLength[1] | nonceLength[1]
 * iterations[4] | salt[saltLength] | nonce[nonceLength] | AES-GCM ciphertext+tag
 * ```
 *
 * `kdf=1` is PBKDF2-HMAC-SHA256 and `cipher=1` is AES-256-GCM with a 128-bit tag. The complete
 * header, including the random salt and nonce, is authenticated as additional data. Keeping this
 * contract explicit lets offline research tooling decrypt a trace without depending on Android.
 */
internal object ResearchTraceEnvelope {
	internal val MAGIC: ByteArray = "TRKTRACE".toByteArray(Charsets.US_ASCII)
	internal const val VERSION: Int = 1
	internal const val KDF_PBKDF2_SHA256: Int = 1
	internal const val CIPHER_AES_256_GCM: Int = 1
	internal const val SALT_LENGTH_BYTES: Int = 16
	internal const val NONCE_LENGTH_BYTES: Int = 12
	internal const val GCM_TAG_LENGTH_BITS: Int = 128
	internal const val AES_KEY_LENGTH_BITS: Int = 256
	internal const val MIN_PASSPHRASE_LENGTH: Int = 12
	internal const val MIN_PBKDF2_ITERATIONS: Int = 100_000
	internal const val DEFAULT_PBKDF2_ITERATIONS: Int = 600_000

	internal fun validatePassphrase(passphrase: CharArray) {
		require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
			"Research trace passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
		}
	}

	internal fun openEncryptingStream(
		destination: OutputStream,
		passphrase: CharArray,
		secureRandom: SecureRandom,
		pbkdf2Iterations: Int,
	): OutputStream {
		require(pbkdf2Iterations >= MIN_PBKDF2_ITERATIONS) {
			"PBKDF2 iteration count must be at least $MIN_PBKDF2_ITERATIONS"
		}

		val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
		val nonce = ByteArray(NONCE_LENGTH_BYTES).also(secureRandom::nextBytes)
		val header = createHeader(salt, nonce, pbkdf2Iterations)
		val keyBytes = deriveKey(passphrase, salt, pbkdf2Iterations)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		try {
			cipher.init(
				Cipher.ENCRYPT_MODE,
				SecretKeySpec(keyBytes, "AES"),
				GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
			)
		} finally {
			keyBytes.fill(0)
		}
		cipher.updateAAD(header)
		destination.write(header)
		return CipherOutputStream(destination, cipher)
	}

	private fun createHeader(
		salt: ByteArray,
		nonce: ByteArray,
		pbkdf2Iterations: Int,
	): ByteArray = ByteArrayOutputStream().also { buffer ->
		DataOutputStream(buffer).use { header ->
			header.write(MAGIC)
			header.writeByte(VERSION)
			header.writeByte(KDF_PBKDF2_SHA256)
			header.writeByte(CIPHER_AES_256_GCM)
			header.writeByte(salt.size)
			header.writeByte(nonce.size)
			header.writeInt(pbkdf2Iterations)
			header.write(salt)
			header.write(nonce)
		}
	}.toByteArray()

	private fun deriveKey(
		passphrase: CharArray,
		salt: ByteArray,
		pbkdf2Iterations: Int,
	): ByteArray {
		val specification = PBEKeySpec(
			passphrase,
			salt,
			pbkdf2Iterations,
			AES_KEY_LENGTH_BITS,
		)
		return try {
			SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
				.generateSecret(specification)
				.encoded
		} finally {
			specification.clearPassword()
		}
	}
}
