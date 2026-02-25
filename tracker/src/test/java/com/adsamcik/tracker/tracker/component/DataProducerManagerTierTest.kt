package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.component.producer.ActivityDataProducer
import com.adsamcik.tracker.tracker.component.producer.CellDataProducer
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer
import com.adsamcik.tracker.tracker.component.producer.WifiDataProducer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that [DataProducerManager] filters producers based on [PolicyTier].
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class DataProducerManagerTierTest {

	private lateinit var context: Context

	@BeforeEach
	fun setup() {
		context = ApplicationProvider.getApplicationContext()
	}

	@Test
	fun `AMBIENT tier creates only 2 producers (activity + steps)`() {
		val manager = DataProducerManager(context, PolicyTier.AMBIENT)
		val producerList = getProducerList(manager)
		assertEquals(2, producerList.size, "AMBIENT should have exactly 2 producers")
		assertTrue(producerList.any { it is ActivityDataProducer })
		assertTrue(producerList.any { it is StepDataProducer })
	}

	@Test
	fun `ACTIVE tier creates all 4 producers`() {
		val manager = DataProducerManager(context, PolicyTier.ACTIVE)
		val producerList = getProducerList(manager)
		assertEquals(4, producerList.size, "ACTIVE should have exactly 4 producers")
		assertTrue(producerList.any { it is WifiDataProducer })
		assertTrue(producerList.any { it is CellDataProducer })
		assertTrue(producerList.any { it is ActivityDataProducer })
		assertTrue(producerList.any { it is StepDataProducer })
	}

	@Test
	fun `PRECISION tier creates all 4 producers`() {
		val manager = DataProducerManager(context, PolicyTier.PRECISION)
		val producerList = getProducerList(manager)
		assertEquals(4, producerList.size, "PRECISION should have exactly 4 producers")
	}

	@Test
	fun `default tier is PRECISION (backward compatible)`() {
		val manager = DataProducerManager(context)
		val producerList = getProducerList(manager)
		assertEquals(4, producerList.size, "Default should have all 4 producers")
	}

	@Test
	fun `AMBIENT tier does not contain WifiDataProducer`() {
		val manager = DataProducerManager(context, PolicyTier.AMBIENT)
		val producerList = getProducerList(manager)
		assertTrue(producerList.none { it is WifiDataProducer })
	}

	@Test
	fun `AMBIENT tier does not contain CellDataProducer`() {
		val manager = DataProducerManager(context, PolicyTier.AMBIENT)
		val producerList = getProducerList(manager)
		assertTrue(producerList.none { it is CellDataProducer })
	}

	@Suppress("UNCHECKED_CAST")
	private fun getProducerList(manager: DataProducerManager): List<TrackerDataProducerComponent> {
		val field = DataProducerManager::class.java.getDeclaredField("producerList")
		field.isAccessible = true
		return field.get(manager) as List<TrackerDataProducerComponent>
	}
}
