package com.adsamcik.tracker.statistics.data.source

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.contains
import com.adsamcik.tracker.shared.base.extension.forEachParallel
import com.adsamcik.tracker.shared.base.extension.sortByVertexes
import com.adsamcik.tracker.shared.base.graph.Edge
import com.adsamcik.tracker.shared.base.graph.Graph
import com.adsamcik.tracker.shared.base.graph.Vertex
import com.adsamcik.tracker.shared.base.graph.topSort
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.consumer.AscensionConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.AvgSpeedConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.DescensionConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.DistanceConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.DistanceInVehicleConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.DistanceOnFootConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.ElevationChartConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.LocationMapConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.MaxAltitudeConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.MaxSpeedConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.MinAltitudeConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.StepCountConsumer
import com.adsamcik.tracker.statistics.data.source.producer.LocationDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedAltitudeProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedLocationDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import com.adsamcik.tracker.statistics.data.source.producer.raw.RawLocationDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.raw.RawSessionDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.raw.RawWifiLocationProducer
import com.adsamcik.tracker.statistics.database.StatsDatabase
import com.adsamcik.tracker.statistics.database.dao.StatsCacheDao
import com.adsamcik.tracker.statistics.database.data.CacheStatData
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass


typealias StatDataMap = Map<KClass<out StatDataProducer>, ConcurrentCacheData<StatDataProducer>>
typealias RawDataMap = Map<StatDataSource, ConcurrentCacheData<RawDataProducer>>
typealias StatAddCallback = (stat: Stat) -> Unit

/**
 * Data manager for statistics. Provides simple API for accessing statistics.
 * Uses database caching.
 */
class StatisticDataManager : CoroutineScope {
	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job

	private val rawProducers: List<RawDataProducer> = listOf(
			RawLocationDataProducer(),
			RawWifiLocationProducer()
	)
	private val producers: List<StatDataProducer>
	private val consumers: List<StatDataConsumer> = listOf(
			DistanceConsumer(),
			DistanceOnFootConsumer(),
			DistanceInVehicleConsumer(),
			LocationMapConsumer(),
			StepCountConsumer(),
			ElevationChartConsumer(),
			AscensionConsumer(),
			DescensionConsumer(),
			MaxAltitudeConsumer(),
			MinAltitudeConsumer(),
			AvgSpeedConsumer(),
			MaxSpeedConsumer()
	)

	init {
		val producerList: List<StatDataProducer> = listOf(
				TrackerSessionProducer(),
				OptimizedLocationDataProducer(),
				OptimizedAltitudeProducer(),
				LocationDataProducer()
		)

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

	private fun classToProducer(producerType: KClass<out StatDataProducer>): StatDataProducer? {
		val instance = producers.find { it::class == producerType }
		if (instance == null) {
			Reporter.report("Required producer dependency of type $producerType not found.")
		}
		return instance
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
		return consumers
				.filterNot { consumer ->
					cacheList.contains { cacheData ->
						cacheData.providerId == consumer::class.java.simpleName
					}
				}.filter { consumer ->
					val allowedActivity = consumer.allowedSessionActivity
					allowedActivity.isEmpty() ||
							(sessionActivityId != null && allowedActivity.contains(sessionActivityId))
				}
	}

	private fun prepareDataMaps(
			consumers: Collection<StatDataConsumer>,
			session: TrackerSession
	): Pair<RawDataMap, StatDataMap> {
		val rawDataMap: MutableMap<StatDataSource, ConcurrentCacheData<RawDataProducer>> = mutableMapOf()
		val statDataMap: MutableMap<KClass<out StatDataProducer>, ConcurrentCacheData<StatDataProducer>> = mutableMapOf()

		@Suppress("UNCHECKED_CAST")
		rawDataMap[StatDataSource.SESSION] = ConcurrentCacheData(
				ReentrantLock(),
				RawSessionDataProducer(),
				session
		) as ConcurrentCacheData<RawDataProducer>

		consumers.forEach { consumer ->
			consumer.dependsOn.forEach dependForEach@{ producerClass ->
				val producer: StatDataProducer
				if (!statDataMap.containsKey(producerClass)) {
					producer = classToProducer(producerClass) ?: return@dependForEach
					statDataMap[producerClass] = ConcurrentCacheData(
							ReentrantLock(),
							producer,
							null
					)
				} else {
					producer = requireNotNull(statDataMap[producerClass]).producer
				}

				prepareRawDataMap(producer, rawDataMap)
			}
		}
		return Pair(rawDataMap, statDataMap)
	}

	private fun prepareRawDataMap(
			producer: StatDataProducer,
			map: MutableMap<StatDataSource, ConcurrentCacheData<RawDataProducer>>
	) {
		producer.requiredRawData.forEach { source ->
			if (!map.containsKey(source)) {
				val rawProducer = rawProducers.find { producer -> producer.type == source }
				if (rawProducer != null) {
					map[source] = ConcurrentCacheData(ReentrantLock(), rawProducer, null)
				}
			}
		}
	}

	private suspend fun requireRawProducers(
			context: Context,
			session: TrackerSession,
			source: Collection<StatDataSource>,
			rawCacheMap: RawDataMap
	): Boolean {
		return source.forEachParallel {
			val cacheData = rawCacheMap[it]
			if (cacheData != null) {
				cacheData.lock.withLock {
					if (cacheData.data == null) {
						cacheData.data = cacheData.producer.produce(
								context,
								session.start,
								session.end
						)
					}
				}
				true
			} else {
				false
			}
		}.awaitAll().all { it }
	}

	private suspend fun requireProducers(
			context: Context,
			session: TrackerSession,
			producers: Collection<KClass<out StatDataProducer>>,
			rawCacheMap: RawDataMap,
			cacheMap: StatDataMap
	): Boolean {
		return producers.forEachParallel { producerClass ->
			val cacheData = cacheMap[producerClass] ?: return@forEachParallel false

			cacheData.lock.withLock {
				if (cacheData.data != null) return@forEachParallel true
			}

			val hasAllRaw = requireRawProducers(
					context,
					session,
					cacheData.producer.requiredRawData,
					rawCacheMap
			)

			if (!hasAllRaw) {
				return@forEachParallel false
			}

			val hasAllProducers = requireProducers(
					context,
					session,
					cacheData.producer.dependsOn,
					rawCacheMap,
					cacheMap
			)

			if (!hasAllProducers) {
				return@forEachParallel false
			}

			cacheData.lock.withLock {
				if (cacheData.data == null) {
					cacheData.data = cacheData.producer.produce(rawCacheMap, cacheMap)
				}
			}
			true
		}.awaitAll().all { it }
	}

	private suspend fun handleConsumer(
			context: Context,
			session: TrackerSession,
			consumer: StatDataConsumer,
			rawCacheMap: RawDataMap,
			cacheMap: StatDataMap
	): Any? {
		val hasAllData = requireProducers(
				context,
				session,
				consumer.dependsOn,
				rawCacheMap,
				cacheMap
		)

		return if (hasAllData) {
			consumer.getData(context, cacheMap)
		} else {
			null
		}
	}

	private fun createStat(
			consumer: StatDataConsumer,
			value: Any
	): Stat {
		return Stat(consumer.nameRes, consumer.iconRes, consumer.displayType, value)
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
	private suspend fun generateStatisticData(
			context: Context,
			session: TrackerSession,
			cacheList: List<CacheStatData>,
			onStatFinished: StatAddCallback
	): List<CacheableStat> {
		val filteredConsumers = Logger.measureTimeMillis("Filter consumers") {
			filterConsumers(session, cacheList)
		}
		Log.d("TrackerPerf", "Consumer count ${filteredConsumers.size}")
		val (rawCacheMap, cacheMap) = prepareDataMaps(filteredConsumers, session)

		val result = filteredConsumers.forEachParallel {
			val value = handleConsumer(context, session, it, rawCacheMap, cacheMap)
			if (value != null) {
				val stat = createStat(it, value)
				onStatFinished(stat)
				CacheableStat(
						stat,
						it
				)
			} else {
				null
			}
		}


		return result.mapNotNull { it.await() }
	}

	/**
	 * Builds moshi with all required adapters
	 */
	private fun buildMoshi(): Moshi {
		val adapters = consumers.mapNotNull { it.requiredMoshiAdapter }.distinct()
		val builder = Moshi.Builder()
		adapters.forEach { builder.add(it) }
		return builder.build()
	}

	private fun cacheData(
			moshi: Moshi,
			dao: StatsCacheDao,
			sessionId: Long,
			list: Collection<CacheableStat>
	) {
		dao.upsert(list.map {
			CacheStatData(
					sessionId,
					it.consumer.providerId,
					it.consumer.serializeData(it.value.data, moshi)
			)
		})
	}

	/**
	 * Returns list of Stats available for session with id equal to [sessionId].
	 *
	 * @param context Context
	 * @param sessionId Session id
	 * @return List of available stats
	 */
	@WorkerThread
	suspend fun getForSession(
			context: Context,
			sessionId: Long,
			skipCache: Boolean,
			onStatFinished: StatAddCallback
	): List<Stat> {
		return Logger.measureTimeMillis("stats generation (skip cache: $skipCache)") {
			val session = getSession(context, sessionId)
			val cached: List<CacheStatData>
			var cacheDao: StatsCacheDao? = null
			if (skipCache) {
				cached = emptyList()
			} else {
				cacheDao = StatsDatabase.database(context).cacheDao()
				cached = Logger.measureTimeMillis("Cache loading") {
					cacheDao.getAllForSession(sessionId)
				}
			}

			val newList = generateStatisticData(context, session, cached, onStatFinished)

			if (!skipCache) {
				val moshi = buildMoshi()

				cacheData(moshi, cacheDao!!, sessionId, newList)

				val cachedStats = cached.mapNotNull { cacheData ->
					val consumer = consumers.find { it.providerId == cacheData.providerId }
							?: return@mapNotNull null
					createStat(consumer, consumer.deserializeData(cacheData.value, moshi))
				}.onEach {
					onStatFinished(it)
				}

				return@measureTimeMillis ArrayList<Stat>(newList.size + cachedStats.size).apply {
					addAll(newList.map { it.value })
					addAll(cachedStats)
				}
			} else {
				return@measureTimeMillis newList.map { it.value }
			}
		}
	}

	private data class CacheableStat(val value: Stat, val consumer: StatDataConsumer)
}
