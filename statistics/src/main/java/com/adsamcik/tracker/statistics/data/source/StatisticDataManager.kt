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
import com.adsamcik.tracker.shared.utils.debug.Reporter
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.consumer.DistanceConsumer
import com.adsamcik.tracker.statistics.database.StatsDatabase
import com.adsamcik.tracker.statistics.database.data.CacheStatData
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

typealias StatDataMap = MultiTypeMap<KClass<out StatDataProducer>, Any>
typealias RawDataMap = Map<StatDataSource, Any>

/**
 * Data manager for statistics. Provides simple API for accessing statistics.
 * Uses database caching.
 */
class StatisticDataManager {
	private val rawProducers: List<RawDataProducer> = listOf()
	private val producers: List<StatDataProducer>
	private val consumers: List<StatDataConsumer> = listOf(DistanceConsumer())

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

	private fun classToProducer(producerType: KClass<StatDataProducer>): StatDataProducer? {
		val instance = producers.find { it::class == producerType }
		if (instance == null) {
			Reporter.report("Required producer dependency of type $producerType not found.")
		}
		return instance
	}

	private fun addProducersToQueue(
			queue: Queue<StatDataProducer>,
			list: Collection<KClass<StatDataProducer>>
	) {
		list.forEach { producerClass ->
			classToProducer(producerClass)?.let { producer ->
				queue.add(producer)
			}

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
			cacheList: List<CacheStatData>
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
			session: TrackerSession,
			rawDataProducers: Collection<RawDataProducer>
	): RawDataMap {
		val dataMap: MutableMap<StatDataSource, Any> = mutableMapOf()
		rawDataProducers.forEach {
			val data = it.produce(context)
			if (data != null) {
				dataMap[it.type] = data
			}
		}
		if (!dataMap.containsKey(StatDataSource.SESSION)) {
			dataMap[StatDataSource.SESSION] = session
		}
		return dataMap
	}

	private fun produceData(
			dataProducers: Collection<StatDataProducer>,
			rawDataMap: RawDataMap
	): StatDataMap {
		val dataMap: MutableMultiTypeMap<KClass<out StatDataProducer>, Any> = MutableMultiTypeMap()
		dataProducers.forEach { producer ->
			val hasRequiredRawData = producer.requiredRawData.all { rawDataMap.containsKey(it) }
			val hasRequiredData = producer.dependsOn.all { dataMap.containsKey(it) }

			if (hasRequiredRawData && hasRequiredData) {
				dataMap[producer::class] = producer.produce(rawDataMap, dataMap)
			}
		}
		return dataMap
	}

	private fun consumeAndCacheData(
			context: Context,
			sessionId: Long,
			consumer: StatDataConsumer,
			dataMap: StatDataMap
	): Any {
		val data = consumer.getData(context, dataMap)
		if (data is String) {
			val cacheData = CacheStatData(sessionId, consumer::class.java.simpleName, data)
			StatsDatabase.database(context).cacheDao().upsert(cacheData)
		}
		return data
	}

	private fun consumeData(
			context: Context,
			sessionId: Long,
			consumers: Collection<StatDataConsumer>,
			dataMap: StatDataMap
	): List<Stat> {
		val statList = mutableListOf<Stat>()
		consumers.forEach { consumer ->
			if (consumer.dependsOn.all { dataMap.containsKey(it) }) {
				val value = consumeAndCacheData(context, sessionId, consumer, dataMap)
				val stat = Stat(consumer.nameRes, consumer.iconRes, consumer.displayType, value)
				statList.add(stat)
			}
		}
		return statList
	}

	/**
	 * Generates statistics data for given [session].
	 *
	 * @param context Context
	 * @param session Tracker session
	 * @param cacheList List of cached [CacheStatData]
	 *
	 * @return List of statistics including cached data.
	 */
	@WorkerThread
	private fun generateStatisticData(
			context: Context,
			session: TrackerSession,
			cacheList: List<CacheStatData>
	): List<Stat> {
		val filteredConsumers = filterConsumers(session, cacheList)

		val requiredProducers = getRequiredProducers(filteredConsumers)
		val rawProducers = getRawDataProducers(requiredProducers)

		val rawData = produceRawData(context, session, rawProducers)
		val dataMap = produceData(requiredProducers, rawData)

		val newData = consumeData(context, session.id, filteredConsumers, dataMap)
		return ArrayList<Stat>(newData.size + cacheList.size).apply {
			addAll(newData)
			cacheList.forEach { statData ->
				val consumer = consumers.find { it.providerId == statData.providerId }

				if (consumer != null) {
					add(Stat(consumer.nameRes, consumer.iconRes, consumer.displayType, statData))
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
