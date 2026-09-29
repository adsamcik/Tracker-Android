package com.adsamcik.tracker.shared.base.database.data

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Stable source-file v2 detail checksum, also used when reading imported details from Room. */
object PortableRadioChildIntegrity {
	fun digest(source: String, identities: List<String>, values: List<Any?>): String {
		require(source == "wifi" || source == "cell")
		val digest = MessageDigest.getInstance("SHA-256")
		(listOf("tracker-portable-radio-child-v2", source) + identities + values).forEach { value ->
			val bytes = value?.toString()?.toByteArray(Charsets.UTF_8)
			digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
			if (bytes != null) digest.update(bytes)
		}
		return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
	}
}
