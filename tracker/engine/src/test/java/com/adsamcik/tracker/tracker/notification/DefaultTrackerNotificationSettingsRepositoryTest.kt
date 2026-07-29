package com.adsamcik.tracker.tracker.notification

import com.adsamcik.tracker.shared.base.database.dao.NotificationPreferenceDao
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DefaultTrackerNotificationSettingsRepositoryTest {
	@Test
	fun `loads the complete catalog in its stable first-run order`() = runTest {
		val repository = DefaultTrackerNotificationSettingsRepository(FakeNotificationPreferenceDao())

		val settings = repository.loadSettings()

		settings.map(TrackerNotificationSetting::id).shouldContainExactly(
			"ActivityNotificationComponent",
			"AltitudeNotificationComponent",
			"CellCountNotificationComponent",
			"CellCurrentNotificationComponent",
			"CollectionCountNotificationComponent",
			"DistanceInVehicleNotificationComponent",
			"DistanceNotificationComponent",
			"DistanceOnFootNotificationComponent",
			"DurationNotificationComponent",
			"HorizontalAccuracyNotificationComponent",
			"LastUpdateNotificationComponent",
			"LatitudeNotificationComponent",
			"LongitudeNotificationComponent",
			"SkiNotificationComponent",
			"SpeedNotificationComponent",
			"StartTimeNotificationComponent",
			"WiFiCountNotificationComponent",
			"BatteryNotificationComponent",
		)
	}

	@Test
	fun `loads persisted order and flags while ignoring stale ids`() = runTest {
		val initialRepository =
			DefaultTrackerNotificationSettingsRepository(FakeNotificationPreferenceDao())
		val defaults = initialRepository.loadSettings()
		val persisted = defaults.reversed().mapIndexed { index, setting ->
			NotificationPreference(
				id = setting.id,
				order = index,
				isInTitle = index % 2 == 0,
				isInContent = index % 3 == 0,
			)
		} + NotificationPreference(
			id = "RemovedNotificationComponent",
			order = -1,
			isInTitle = true,
			isInContent = true,
		)
		val repository = DefaultTrackerNotificationSettingsRepository(
			FakeNotificationPreferenceDao(persisted)
		)

		val loaded = repository.loadSettings()

		loaded.map(TrackerNotificationSetting::id)
			.shouldContainExactly(defaults.asReversed().map(TrackerNotificationSetting::id))
		loaded.map(TrackerNotificationSetting::isInTitle)
			.shouldContainExactly(defaults.indices.map { it % 2 == 0 })
		loaded.map(TrackerNotificationSetting::isInContent)
			.shouldContainExactly(defaults.indices.map { it % 3 == 0 })
	}

	@Test
	fun `saves list position as normalized order and applies flag changes`() = runTest {
		val dao = FakeNotificationPreferenceDao()
		val repository = DefaultTrackerNotificationSettingsRepository(dao)
		val settings = repository.loadSettings().reversed().mapIndexed { index, setting ->
			setting.copy(
				isInTitle = index == 0,
				isInContent = index != 0,
			)
		}

		repository.saveSettings(settings)

		dao.rows.map(NotificationPreference::id)
			.shouldContainExactly(settings.map(TrackerNotificationSetting::id))
		dao.rows.map(NotificationPreference::order)
			.shouldContainExactly(settings.indices.toList())
		dao.rows.first().isInTitle shouldBe true
		dao.rows.first().isInContent shouldBe false

		val reloaded = repository.loadSettings()
		reloaded.shouldContainExactly(settings)
	}

	@Test
	fun `rejects incomplete or duplicate catalogs`() = runTest {
		val repository = DefaultTrackerNotificationSettingsRepository(FakeNotificationPreferenceDao())
		val settings = repository.loadSettings()

		shouldThrow<IllegalArgumentException> {
			repository.saveSettings(settings.dropLast(1))
		}
		shouldThrow<IllegalArgumentException> {
			repository.saveSettings(settings.dropLast(1) + settings.first())
		}
	}
}

private class FakeNotificationPreferenceDao(
	initialRows: List<NotificationPreference> = emptyList(),
) : NotificationPreferenceDao {
	var rows: List<NotificationPreference> = initialRows
		private set

	override suspend fun getAll(): List<NotificationPreference> = rows

	override suspend fun insert(obj: NotificationPreference): Long {
		if (rows.any { it.id == obj.id }) return -1L
		rows = rows + obj
		return rows.size.toLong()
	}

	override suspend fun insert(
		obj: Collection<NotificationPreference>,
	): List<Long> = obj.map { insert(it) }

	override suspend fun update(obj: NotificationPreference) {
		rows = rows.map { existing -> if (existing.id == obj.id) obj else existing }
	}

	override suspend fun update(obj: Collection<NotificationPreference>) {
		obj.forEach { update(it) }
	}

	override suspend fun delete(obj: NotificationPreference) {
		rows = rows.filterNot { it.id == obj.id }
	}

	override suspend fun delete(obj: Collection<NotificationPreference>) {
		val ids = obj.mapTo(mutableSetOf(), NotificationPreference::id)
		rows = rows.filterNot { it.id in ids }
	}
}
