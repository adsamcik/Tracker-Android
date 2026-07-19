package com.adsamcik.tracker.game.integration

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

class GameSessionManifestTest {
	private val manifest by lazy {
		val file = File("src/main/AndroidManifest.xml")
		check(file.exists()) {
			"Expected game manifest at ${file.absolutePath} (working dir ${File(".").absolutePath})"
		}
		DocumentBuilderFactory.newInstance().apply {
			isNamespaceAware = true
		}.newDocumentBuilder().parse(file)
	}

	@Test
	fun `game session service is private location foreground service`() {
		val service = manifest.getElementsByTagName("service")
			.asElements()
			.single {
				it.androidAttribute("name") ==
					"com.adsamcik.tracker.game.session.GameSessionService"
			}

		service.androidAttribute("exported") shouldBe "false"
		service.androidAttribute("foregroundServiceType") shouldBe "location"
	}

	@Test
	fun `game manifest declares session runtime permissions exactly once`() {
		val permissions = manifest.getElementsByTagName("uses-permission")
			.asElements()
			.map { it.androidAttribute("name") }
			.filter { it in REQUIRED_PERMISSIONS }

		permissions shouldContainExactlyInAnyOrder REQUIRED_PERMISSIONS
	}

	private fun org.w3c.dom.NodeList.asElements(): List<Element> =
		(0 until length).map { item(it) as Element }

	private fun Element.androidAttribute(name: String): String =
		getAttributeNS(ANDROID_NAMESPACE, name)

	private companion object {
		const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
		val REQUIRED_PERMISSIONS = listOf(
			"android.permission.FOREGROUND_SERVICE",
			"android.permission.FOREGROUND_SERVICE_LOCATION",
			"android.permission.ACCESS_FINE_LOCATION",
			"android.permission.ACCESS_COARSE_LOCATION",
			"android.permission.VIBRATE",
		)
	}
}
