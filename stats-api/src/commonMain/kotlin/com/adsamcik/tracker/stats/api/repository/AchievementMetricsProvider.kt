package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.metric.MetricSnapshot

interface AchievementMetricsProvider { suspend fun collect(): MetricSnapshot }
