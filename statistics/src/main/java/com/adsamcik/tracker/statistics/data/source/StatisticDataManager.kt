package com.adsamcik.tracker.statistics.data.source

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.contains
import com.adsamcik.tracker.shared.base.extension.sortByVertexes
import com.adsamcik.tracker.shared.base.graph.Edge
import com.adsamcik.tracker.shared.base.graph.Graph
import com.adsamcik.tracker.shared.base.graph.Vertex
import com.adsamcik.tracker.shared.base.graph.topSort
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.database.StatsDatabase
import java.util.*
import kotlin.reflect.KClass

typealias StatDataMap = Map<KClass<StatDataProducer>, String>
typealias SessionDataMap = Map<StatDataSource, Any>

/**
 * Data manager for statistics. Provides simple API for accessing statistics.
 * Uses database caching.
 */
class StatisticDataManager {
	private val producers: List<StatDataProducer>
	private val consumers: List<StatDataConsumer> = listOf()

	init {
		val producerList: List<StatDataProducer> = listOf()

		val vertexList = MutableList(producerList.size) { Vertex(it) }
		val edgeList = producerList.asSequence().withIndex().flatMap { producer ->
			producer.value.dependsOn.asSequence().map { dependent ->
				val dependentIndex = producerList
						.indexOfFirst { it::class == dependent }
						.also { index -> require(index >= 0) }
				Edge(vertexList[producer.index], vertexList[dependentIndex])
			}
		}.toList()
		val sortedVertexes = Graph(vertexList, edgeList).topSort()

		producers = producerList.sortByVertexes(sortedVertexes)
	}

	private fun getSession(context: Context, id: Long): TrackerSession {
		val sessionDao = AppDatabase.database(context).sessionDao()
		return requireNotNull(sessionDao.get(id))
	}

	private fun classToProducer(producerType: KClass<StatDataProducer>): StatDataProducer {
		val instance = producers.find { it::class == producerType }
		return requireNotNull(instance) { "Required producer dependency of type $producerType not found." }
	}

	private fun addProducersToQueue(
			queue: Queue<StatDataProducer>,
			list: Collection<KClass<StatDataProducer>>
	) {
		list.forEach {
			queue.add(classToProducer(it))
		}
	}

	private fun getRequiredProducers(consumers: List<StatDataConsumer>): Collection<StatDataProducer> {
		if (consumers.isEmpty()) return emptyList()

		val requiredProducerList = mutableSetOf<StatDataProducer>()
		val queue = ArrayDeque<StatDataProducer>(producers.size)

		val initialProducers = consumers
				.asSequence()
				.flatMap { it.dependsOn.asSequence() }
				.toSet()

		addProducersToQueue(queue, initialProducers)

		while (queue.isNotEmpty()) {
			val next = queue.pop()
			requiredProducerList.add(next)
			addProducersToQueue(queue, next.dependsOn)
		}

		return requiredProducerList
	}

	/**
	 * Returns list of Stats available for session with id equal to [sessionId].
	 *
	 * @param context Context
	 * @param sessionId Session id
	 * @return List of available stats
	 */
	@WorkerThread
	fun getForSession(context: Context, sessionId: Long): List<Stat> {
		val session = getSession(context, sessionId)

		val cacheDao = StatsDatabase.database(context).cacheDao()
		val cached = cacheDao.getAllForSession(sessionId)

		val filteredConsumers = consumers.filter { consumer ->
			cached.contains { cacheData ->
				cacheData.cacheId == consumer::class.java.simpleName
			}
		}.filter { consumer ->
			val allowedActivity = consumer.allowedSessionActivity
			allowedActivity.isEmpty() || allowedActivity.contains(session.sessionActivityId)
		}

		val dataProducers: MutableMap<KClass<StatDataProducer>, String> = mutableMapOf()

		getRequiredProducers(filteredConsumers).forEach {
			it.produce()
		}
	}
}
