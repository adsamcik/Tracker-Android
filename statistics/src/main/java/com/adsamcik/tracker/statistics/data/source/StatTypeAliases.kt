package com.adsamcik.tracker.statistics.data.source

import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import kotlin.reflect.KClass

typealias StatDataMap = Map<KClass<out StatDataProducer>, ConcurrentCacheData<StatDataProducer>>
typealias RawDataMap = Map<StatDataSource, ConcurrentCacheData<RawDataProducer>>
