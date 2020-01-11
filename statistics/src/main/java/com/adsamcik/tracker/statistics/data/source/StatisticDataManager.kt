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
import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.database.StatsDatabase
import com.adsamcik.tracker.statistics.database.data.StatData
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

typealias StatDataMap = Map<KClass<out StatDataProducer>, String>
typealias RawDataMap = Map<StatDataSource, Any>

/**
 * Data manager for statistics. Provides simple API for accessing statistics.
 * Uses database caching.
 */
class StatisticDataManager {
	private val rawProducers: List<RawDataProducer> = listOf()
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

	private fun getRequiredProducers(consumers: Collection<StatDataConsumer>): Collection<StatDataProducer> {
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

	private fun getRawDataProducers(producers: Collection<StatDataProducer>): List<RawDataProducer> {
		val requiredRawData = producers.flatMap { it.requiredRawData }.toSet()
		return requiredRawData.mapNotNull { source ->
			rawProducers.find { producer -> producer.type == source }
		}
	}

	/**
	 * Filters consumers to only those that are compatible with given session.
	 *
	 * @param session Tracker session
	 * @param cacheList List of cached statistics
	 */
	private fun filterConsumers(
			session: TrackerSession,
			cacheList: List<StatData>
	): List<StatDataConsumer> {
		val sessionActivityId = session.sessionActivityId
		return consumers.filter { consumer ->
			cacheList.contains { cacheData ->
				cacheData.providerId == consumer::class.java.simpleName
			}
		}.filter { consumer ->
			val allowedActivity = consumer.allowedSessionActivity
			allowedActivity.isEmpty() ||
					(sessionActivityId != null && allowedActivity.contains(sessionActivityId))
		}
	}

	private fun produceRawData(
			context: Context,
			rawDataProducers: Collection<RawDataProducer>
	): RawDataMap {
		val dataMap: MutableMap<StatDataSource, Any> = mutableMapOf()
		rawDataProducers.forEach {
			val data = it.produce(context)
			if (data != null) {
				dataMap[it.type] = data
			}
		}
		return dataMap
	}

	private fun produceData(
			dataProducers: Collection<StatDataProducer>,
			rawDataMap: RawDataMap
	): StatDataMap {
		val dataMap: MutableMap<KClass<out StatDataProducer>, String> = mutableMapOf()
		dataProducers.forEach { producer ->
			val hasRequiredRawData = producer.requiredRawData.all { rawDataMap.containsKey(it) }
			val hasRequiredData = producer.dependsOn.all { dataMap.containsKey(it) }

			if (hasRequiredRawData && hasRequiredData) {
				dataMap[producer::class] = producer.produce(rawDataMap, dataMap)
			}
		}
		return dataMap
	}

	private fun consumeData(
			consumers: Collection<StatDataConsumer>,
			dataMap: StatDataMap
	): List<Stat> {
		val statList = mutableListOf<Stat>()
		consumers.forEach { consumer ->
			if (consumer.dependsOn.all { dataMap.containsKey(it) }) {
				statList.add(consumer.getStat(, dataMap))
			}
		}
		return statList
	}

	/**
	 * Generates statistics data for given [session].
	 *
	 * @param context Context
	 * @param session Tracker session
	 * @param cacheList List of cached [StatData]
	 *
	 * @return List of statistics including cached data.
	 */
	private fun generateStatisticData(
			context: Context,
			session: TrackerSession,
			cacheList: List<StatData>
	): List<Stat> {
		val filteredConsumers = filterConsumers(session, cacheList)

		val requiredProducers = getRequiredProducers(filteredConsumers)
		val rawProducers = getRawDataProducers(requiredProducers)

		val rawData = produceRawData(context, rawProducers)
		val dataMap = produceData(requiredProducers, rawData)

		val newData = consumeData(filteredConsumers, dataMap)
		return ArrayList<Stat>(newData.size + cacheList.size).apply {
			addAll(newData)
			cacheList.forEach { statData ->
				val consumer = consumers.find { it.providerId == statData.providerId }

				if (consumer != null) {
					add(Stat(consumer.getName(context), statData))
				}
			}
		}
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

		return generateStatisticData(context, session, cached)
	}
}
