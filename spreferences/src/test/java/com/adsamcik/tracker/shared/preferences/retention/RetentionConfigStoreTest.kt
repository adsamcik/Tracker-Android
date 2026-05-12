package com.adsamcik.tracker.shared.preferences.retention

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class RetentionConfigStoreTest {

	private lateinit var context: Context
	private lateinit var store: RetentionConfigStore

	@BeforeEach
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
		PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
		val dsDir = context.filesDir.resolve("datastore")
		if (dsDir.exists()) {
			dsDir.listFiles()?.forEach { file -> file.delete() }
		}
		LegacyPreferenceStore.resetForTests()
		kotlinx.coroutines.runBlocking {
			Preferences(context).editSuspend { clear() }
			resetRetentionConfigForTests(context)
		}
		LegacyPreferenceStore.resetForTests()
		store = RetentionConfigStore(context, Dispatchers.IO)
	}

	// region Defaults

	@Test
	fun `default state has expected raw days`() {
		val state = RetentionConfigState()
		state.rawDataRetentionDays shouldBe 365
	}

	@Test
	fun `default state has expected daily summary days`() {
		val state = RetentionConfigState()
		state.dailySummaryRetentionDays shouldBe 730
	}

	@Test
	fun `default state has auto purge disabled`() {
		val state = RetentionConfigState()
		state.autoPurgeEnabled shouldBe false
		state.exportBeforePurge shouldBe false
	}

	@Test
	fun `default state has zero exploration days`() {
		val state = RetentionConfigState()
		state.explorationRetentionDays shouldBe 0
	}

	@Test
	fun `store emits default config on first read`()  { runTest {
		val config = store.config.first()
		config shouldBe RetentionConfigState()
	} }

	@Test
	fun `legacy migration preserves keep forever data setting`()  { runTest {
		Preferences(context).editSuspend {
			setBoolean("autoCleanupOldData", true)
			setString("dataRetentionYears", "0")
		}
		Preferences(context).fetchBoolean("autoCleanupOldData", false) shouldBe true
		Preferences(context).fetchString("dataRetentionYears") shouldBe "0"
		val config = store.config.first()

		config.autoCleanupEnabled shouldBe true
		config.dataRetentionYears shouldBe 0
	} }

	// endregion

	// region Proto serialization round-trip

	@Test
	fun `proto serialization round-trip preserves all fields`() {
		val proto = RetentionConfigProto.newBuilder()
			.setRawDataRetentionDays(30)
			.setWifiCellRetentionDays(60)
			.setTripRetentionDays(90)
			.setDailySummaryRetentionDays(180)
			.setExplorationRetentionDays(365)
			.setAutoPurgeEnabled(true)
			.setExportBeforePurge(true)
			.setLegacySessionRetentionDays(120)
			.setInitialized(true)
			.build()

		val bytes = proto.toByteArray()
		val parsed = RetentionConfigProto.parseFrom(bytes)

		parsed.rawDataRetentionDays shouldBe 30
		parsed.wifiCellRetentionDays shouldBe 60
		parsed.tripRetentionDays shouldBe 90
		parsed.dailySummaryRetentionDays shouldBe 180
		parsed.explorationRetentionDays shouldBe 365
		parsed.autoPurgeEnabled shouldBe true
		parsed.exportBeforePurge shouldBe true
		parsed.legacySessionRetentionDays shouldBe 120
		parsed.initialized shouldBe true
	}

	@Test
	fun `proto default instance has all zeros and false`() {
		val proto = RetentionConfigProto.getDefaultInstance()

		proto.rawDataRetentionDays shouldBe 0
		proto.wifiCellRetentionDays shouldBe 0
		proto.tripRetentionDays shouldBe 0
		proto.dailySummaryRetentionDays shouldBe 0
		proto.explorationRetentionDays shouldBe 0
		proto.autoPurgeEnabled shouldBe false
		proto.exportBeforePurge shouldBe false
		proto.legacySessionRetentionDays shouldBe 0
		proto.initialized shouldBe false
	}

	@Test
	fun `empty proto bytes round-trip to default instance`() {
		val bytes = RetentionConfigProto.getDefaultInstance().toByteArray()
		val parsed = RetentionConfigProto.parseFrom(bytes)
		parsed shouldBe RetentionConfigProto.getDefaultInstance()
	}

	// endregion

	// region Store update round-trip

	@Test
	fun `update persists and reads back custom values`()  { runTest {
		store.update {
			copy(
				rawDataRetentionDays = 30,
				wifiCellRetentionDays = 60,
				tripRetentionDays = 90,
				dailySummaryRetentionDays = 180,
				explorationRetentionDays = 365,
				autoPurgeEnabled = true,
				exportBeforePurge = true,
				legacySessionRetentionDays = 120,
			)
		}

		val config = store.config.first()
		config.rawDataRetentionDays shouldBe 30
		config.wifiCellRetentionDays shouldBe 60
		config.tripRetentionDays shouldBe 90
		config.dailySummaryRetentionDays shouldBe 180
		config.explorationRetentionDays shouldBe 365
		config.autoPurgeEnabled shouldBe true
		config.exportBeforePurge shouldBe true
		config.legacySessionRetentionDays shouldBe 120
	} }

	@Test
	fun `update toggles boolean fields`()  { runTest {
		store.update { copy(autoPurgeEnabled = true) }
		store.config.first().autoPurgeEnabled shouldBe true

		store.update { copy(autoPurgeEnabled = false) }
		store.config.first().autoPurgeEnabled shouldBe false
	} }

	@Test
	fun `sequential updates accumulate independently`()  { runTest {
		store.update { copy(rawDataRetentionDays = 7) }
		store.update { copy(tripRetentionDays = 14) }

		val config = store.config.first()
		config.rawDataRetentionDays shouldBe 7
		config.tripRetentionDays shouldBe 14
	} }

	// endregion

	// region Edge cases — zero means keep forever

	@Test
	fun `zero raw data days reads back as keep forever after domain-proto round-trip`()  { runTest {
		store.update { copy(rawDataRetentionDays = 0) }
		store.config.first().rawDataRetentionDays shouldBe 0
	} }

	@Test
	fun `negative raw data days reads back as default`()  { runTest {
		store.update { copy(rawDataRetentionDays = -1) }
		store.config.first().rawDataRetentionDays shouldBe RetentionConfigState.DEFAULT_RAW_DAYS
	} }

	@Test
	fun `zero daily summary days reads back as keep forever`()  { runTest {
		store.update { copy(dailySummaryRetentionDays = 0) }
		store.config.first().dailySummaryRetentionDays shouldBe 0
	} }

	@Test
	fun `negative exploration days coerced to zero`()  { runTest {
		store.update { copy(explorationRetentionDays = -10) }
		store.config.first().explorationRetentionDays shouldBe 0
	} }

	@Test
	fun `large retention values preserved`()  { runTest {
		store.update {
			copy(
				rawDataRetentionDays = Int.MAX_VALUE,
				dailySummaryRetentionDays = Int.MAX_VALUE,
			)
		}
		val config = store.config.first()
		config.rawDataRetentionDays shouldBe Int.MAX_VALUE
		config.dailySummaryRetentionDays shouldBe Int.MAX_VALUE
	} }

	@Test
	fun `one-day retention preserved`()  { runTest {
		store.update { copy(rawDataRetentionDays = 1) }
		store.config.first().rawDataRetentionDays shouldBe 1
	} }

	@Test
	fun `zero wifi cell days reads back as keep forever`()  { runTest {
		store.update { copy(wifiCellRetentionDays = 0) }
		store.config.first().wifiCellRetentionDays shouldBe 0
	} }

	@Test
	fun `zero legacy session days reads back as keep forever`()  { runTest {
		store.update { copy(legacySessionRetentionDays = 0) }
		store.config.first().legacySessionRetentionDays shouldBe 0
	} }

	@Test
	fun `zero data retention years reads back as keep forever`()  { runTest {
		store.update { copy(dataRetentionYears = 0) }
		store.config.first().dataRetentionYears shouldBe 0
	} }

	// endregion

	// region RetentionConfigState data class

	@Test
	fun `data class equality for identical values`() {
		val a = RetentionConfigState(rawDataRetentionDays = 30, autoPurgeEnabled = true)
		val b = RetentionConfigState(rawDataRetentionDays = 30, autoPurgeEnabled = true)
		a shouldBe b
	}

	@Test
	fun `copy preserves unmodified fields`() {
		val original = RetentionConfigState(
			rawDataRetentionDays = 30,
			autoPurgeEnabled = true,
		)
		val copied = original.copy(rawDataRetentionDays = 60)
		copied.rawDataRetentionDays shouldBe 60
		copied.autoPurgeEnabled shouldBe true
	}

	// endregion
}
