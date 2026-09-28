package com.adsamcik.tracker.impexp.portable

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionBundleManifestV1Test {
	private val codec = SessionBundleManifestCodecV1

	@Test
	fun `metadata only session remains a valid distinct format`() {
		val manifest = SessionBundleManifestV1(
			name = "Evening walk", activityLabel = "Walk", fromMs = 100, toMs = 200,
			zoneId = "Europe/Prague", selectedSources = listOf("steps"),
			children = emptyList(), omissions = listOf("Steps were unavailable"),
			selectedPurposes = listOf("session", "background"),
			selectedOrigins = listOf("local", "imported"),
		)
		codec.decode(codec.encode(manifest)) shouldBe manifest
		JSONObject(codec.encode(manifest).toString(Charsets.UTF_8)).getString("format") shouldBe
			SessionBundleManifestCodecV1.FORMAT
	}

	@Test
	fun `purpose and origin descriptions reject duplicate or unknown values`() {
		val base = SessionBundleManifestV1(null, null, 100, 200, "UTC", emptyList(),
			emptyList(), emptyList())
		shouldThrow<InvalidSessionBundle> {
			codec.encode(base.copy(selectedPurposes = listOf("session", "session")))
		}
		shouldThrow<InvalidSessionBundle> {
			codec.encode(base.copy(selectedOrigins = listOf("live-provider")))
		}
		shouldThrow<InvalidSessionBundle> {
			codec.encode(base.copy(selectedSources = listOf("unknown-source")))
		}
	}

	@Test
	fun `duplicate or unsafe children cannot be dispatched`() {
		val child = SessionBundleChildV1(
			"sources/0/steps.trackersteps", "portable-steps-v1", 2, 10,
			"a".repeat(64), 100, 200, true,
		)
		val base = SessionBundleManifestV1(null, null, 100, 200, "UTC", listOf("steps"),
			listOf(child), emptyList())
		shouldThrow<InvalidSessionBundle> { codec.encode(base.copy(children = listOf(child, child))) }
		shouldThrow<InvalidSessionBundle> {
			codec.encode(base.copy(children = listOf(child.copy(path = "../steps.trackersteps"))))
		}
		shouldThrow<InvalidSessionBundle> {
			codec.encode(base.copy(children = listOf(child.copy(
				fromMs = 50, wholeEntries = false))))
		}
	}

	@Test
	fun `unsupported version and excessive declared size fail before source import`() {
		val child = SessionBundleChildV1(
			"sources/0/steps.trackersteps", "portable-steps-v1", 2,
			SessionBundleManifestCodecV1.MAX_CHILD_BYTES + 1, "a".repeat(64), 100, 200, true,
		)
		val manifest = SessionBundleManifestV1(null, null, 100, 200, "UTC", listOf("steps"),
			listOf(child), emptyList())
		shouldThrow<InvalidSessionBundle> { codec.encode(manifest) }
		val valid = codec.encode(manifest.copy(children = emptyList()))
		val altered = JSONObject(valid.toString(Charsets.UTF_8)).put("version", 2)
		shouldThrow<InvalidSessionBundle> { codec.decode(altered.toString().toByteArray()) }
	}
}
