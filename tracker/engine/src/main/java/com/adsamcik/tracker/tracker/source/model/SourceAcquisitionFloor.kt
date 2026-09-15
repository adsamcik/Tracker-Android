package com.adsamcik.tracker.tracker.source.model

/** Direct-demand purpose used to derive one immutable acquisition floor. */
internal enum class DirectSourceDemandPurpose {
	SESSION_CAPTURE,
	CONTROL_AUTOSTART,
	CONTROL_CONTINUATION,
	AMBIENT_PRODUCT,
}

sealed interface SourceAcquisitionFloor {
	val source: SourceKind
}

internal enum class LocationFloorMode(val level: Int) {
	PASSIVE(1),
	LOW_POWER(2),
	BALANCED(3),
	HIGH_ACCURACY(4),
}

internal data class LocationAcquisitionFloor(
	val minimumMode: LocationFloorMode,
) : SourceAcquisitionFloor {
	override val source = SourceKind.LOCATION
}

/** Wake-capable transition evidence and sampled classification evidence are not interchangeable. */
internal enum class ActivityAcquisitionCapability { TRANSITIONS, CLASSIFICATIONS }

internal data class ActivityAcquisitionFloor(
	val requiredCapabilities: Set<ActivityAcquisitionCapability>,
) : SourceAcquisitionFloor {
	override val source = SourceKind.ACTIVITY

	init {
		require(requiredCapabilities.isNotEmpty())
	}

	fun union(other: ActivityAcquisitionFloor) = ActivityAcquisitionFloor(
		requiredCapabilities = requiredCapabilities + other.requiredCapabilities,
	)
}

internal enum class StepsAcquisitionMechanism { DIRECT_COUNTER }

internal data class StepsAcquisitionFloor(
	val mechanism: StepsAcquisitionMechanism,
	val maximumReportLatencyMs: Long,
	val continuousCoverageRequired: Boolean,
) : SourceAcquisitionFloor {
	override val source = SourceKind.STEPS

	init {
		require(maximumReportLatencyMs >= 0L)
	}
}

/** System-owned providers that may supply opportunistic, sessionless Step history. */
internal enum class AmbientStepsAcquisitionMechanism {
	HEALTH_CONNECT_MOBILE_STEPS,
	LOCAL_RECORDING_STEPS,
}

/**
 * Ambient Steps is imported from one system continuity provider and is never a direct sensor plan.
 * Provider-native record cursors, rather than Tracker's same-boot callback age, decide whether a
 * historical record is fresh and new in effect.
 */
internal data class AmbientStepsAcquisitionFloor(
	val mechanism: AmbientStepsAcquisitionMechanism,
) : SourceAcquisitionFloor {
	override val source = SourceKind.STEPS
}

internal data class PressureAcquisitionFloor(
	val maximumSamplePeriodMicros: Int,
	val maximumReportLatencyMicros: Int,
	val maximumAggregationWindowMs: Long,
) : SourceAcquisitionFloor {
	override val source = SourceKind.PRESSURE

	init {
		require(maximumSamplePeriodMicros > 0)
		require(maximumReportLatencyMicros >= 0)
		require(maximumAggregationWindowMs > 0L)
	}
}

/** A direct Wi-Fi floor requires provider broadcasts; cached reads alone are bootstrap state. */
internal data object WifiBroadcastAcquisitionFloor : SourceAcquisitionFloor {
	override val source = SourceKind.WIFI
}

/** A direct Cell floor requires change callbacks; explicit refresh remains an optional target. */
internal data object CellCallbackAcquisitionFloor : SourceAcquisitionFloor {
	override val source = SourceKind.CELL
}

internal data class SourceDemandContract(
	val floor: SourceAcquisitionFloor,
	val maximumProviderItemAgeMs: Long,
	/** Resolver/controller target signal; unlike requestedDeliveryLatencyMs, this is not a provider guarantee. */
	val targetPlanningLatencyMs: Long,
	val requestedDeliveryLatencyMs: Long?,
	val adaptiveReductionAllowed: Boolean,
) {
	val source: SourceKind get() = floor.source

	init {
		require(maximumProviderItemAgeMs >= 0L)
		require(targetPlanningLatencyMs >= 0L)
		require(requestedDeliveryLatencyMs == null || requestedDeliveryLatencyMs >= 0L)
	}

	fun encodeFloor(): String = SourceAcquisitionFloorCodec.encode(floor)

	companion object {
		fun decode(
			source: SourceKind,
			floorSpec: String,
			maximumProviderItemAgeMs: Long,
			targetPlanningLatencyMs: Long,
			requestedDeliveryLatencyMs: Long?,
			adaptiveReductionAllowed: Boolean,
		): SourceDemandContract {
			val floor = SourceAcquisitionFloorCodec.decode(floorSpec)
			require(floor.source == source) { "Persisted acquisition floor belongs to another source" }
			return SourceDemandContract(
				floor,
				maximumProviderItemAgeMs,
				targetPlanningLatencyMs,
				requestedDeliveryLatencyMs,
				adaptiveReductionAllowed,
			)
		}
	}
}

/**
 * The one app-specific contract factory used by live planning and durable broker demand creation.
 * QoS supplies the target-planning signal; acquisition floors remain separate lower bounds.
 */
internal object SourceDemandContractFactory {
	/**
	 * Declares provider identity without inventing a delivery cadence for system-owned history.
	 * The eventual adapter must stamp each import attempt with a fresh Tracker monotonic observation
	 * and enforce provider-native cursor/new-in-effect rules before durable ingress.
	 */
	fun forAmbientSteps(mechanism: AmbientStepsAcquisitionMechanism): SourceDemandContract =
		SourceDemandContract(
			floor = AmbientStepsAcquisitionFloor(mechanism),
			maximumProviderItemAgeMs = Long.MAX_VALUE,
			targetPlanningLatencyMs = Long.MAX_VALUE,
			requestedDeliveryLatencyMs = null,
			adaptiveReductionAllowed = false,
		)

	fun forQos(
		source: SourceKind,
		qosCode: Int,
		purpose: DirectSourceDemandPurpose,
	): SourceDemandContract {
		require(source != SourceKind.PRESSURE || purpose == DirectSourceDemandPurpose.SESSION_CAPTURE) {
			"Pressure supports direct session capture demand only"
		}
		val effectiveQos = when {
			qosCode in 1..3 -> qosCode
			qosCode == 0 && purpose != DirectSourceDemandPurpose.SESSION_CAPTURE -> 1
			else -> error("Captured source demand requires enabled QoS")
		}
		return when (source) {
			SourceKind.LOCATION -> SourceDemandContract(
				floor = LocationAcquisitionFloor(
					if (effectiveQos >= 3) LocationFloorMode.BALANCED else LocationFloorMode.LOW_POWER,
				),
				maximumProviderItemAgeMs = if (effectiveQos >= 3) 2_000L else 120_000L,
				targetPlanningLatencyMs = if (effectiveQos >= 3) 500L else 30_000L,
				requestedDeliveryLatencyMs = if (effectiveQos >= 3) 2_000L else 120_000L,
				adaptiveReductionAllowed = true,
			)
			SourceKind.ACTIVITY -> activityContract(effectiveQos, purpose)
			SourceKind.STEPS -> {
				require(purpose != DirectSourceDemandPurpose.AMBIENT_PRODUCT) {
					"Ambient Steps requires one explicitly selected system continuity provider"
				}
				SourceDemandContract(
					floor = StepsAcquisitionFloor(
						mechanism = StepsAcquisitionMechanism.DIRECT_COUNTER,
						maximumReportLatencyMs = 300_000L,
						continuousCoverageRequired = false,
					),
					maximumProviderItemAgeMs = 300_000L,
					targetPlanningLatencyMs = when (effectiveQos) {
						1 -> 300_000L
						2 -> 60_000L
						else -> 5_000L
					},
					requestedDeliveryLatencyMs = 300_000L,
					adaptiveReductionAllowed = true,
				)
			}
			SourceKind.PRESSURE -> SourceDemandContract(
				floor = PressureAcquisitionFloor(
					maximumSamplePeriodMicros = 1_000_000,
					maximumReportLatencyMicros = 60_000_000,
					maximumAggregationWindowMs = 60_000L,
				),
				maximumProviderItemAgeMs = 60_000L,
				targetPlanningLatencyMs = when (effectiveQos) {
					1 -> 60_000L
					2 -> 10_000L
					else -> 1_000L
				},
				requestedDeliveryLatencyMs = 60_000L,
				adaptiveReductionAllowed = true,
			)
			SourceKind.WIFI -> SourceDemandContract(
				floor = WifiBroadcastAcquisitionFloor,
				maximumProviderItemAgeMs = when (effectiveQos) {
					1 -> 10 * 60_000L
					2 -> 5 * 60_000L
					else -> 60_000L
				},
				targetPlanningLatencyMs = Long.MAX_VALUE,
				requestedDeliveryLatencyMs = null,
				adaptiveReductionAllowed = true,
			)
			SourceKind.CELL -> SourceDemandContract(
				floor = CellCallbackAcquisitionFloor,
				maximumProviderItemAgeMs = when (effectiveQos) {
					1 -> 10 * 60_000L
					2 -> 5 * 60_000L
					else -> 60_000L
				},
				targetPlanningLatencyMs = Long.MAX_VALUE,
				requestedDeliveryLatencyMs = null,
				adaptiveReductionAllowed = true,
			)
		}
	}

	private fun activityContract(
		qosCode: Int,
		purpose: DirectSourceDemandPurpose,
	): SourceDemandContract {
		// Autostart needs a wake-capable transition registration. Once a session is alive,
		// sampled classifications are the richer evidence used for both movement bands and
		// continuation decisions, so capture + continuation do not require incompatible plans.
		val classifications = purpose != DirectSourceDemandPurpose.CONTROL_AUTOSTART
		return SourceDemandContract(
			floor = ActivityAcquisitionFloor(setOf(
				if (classifications) {
					ActivityAcquisitionCapability.CLASSIFICATIONS
				} else {
					ActivityAcquisitionCapability.TRANSITIONS
				},
			)),
			maximumProviderItemAgeMs = when (qosCode) {
				1 -> 120_000L
				2 -> 30_000L
				else -> 5_000L
			},
			targetPlanningLatencyMs = when (qosCode) {
				1 -> 60_000L
				2 -> 30_000L
				else -> 5_000L
			},
			requestedDeliveryLatencyMs = if (classifications) when (qosCode) {
				1 -> 60_000L
				2 -> 30_000L
				else -> 5_000L
			} else null,
			adaptiveReductionAllowed = true,
		)
	}
}

internal object SourceAcquisitionFloorCodec {
	fun encode(floor: SourceAcquisitionFloor): String = when (floor) {
		is LocationAcquisitionFloor -> "location:v1:min_mode=${floor.minimumMode.name}"
		is ActivityAcquisitionFloor -> "activity:v1:capabilities=" + floor.requiredCapabilities
			.map(ActivityAcquisitionCapability::name)
			.sorted()
			.joinToString(",")
		is StepsAcquisitionFloor -> listOf(
			"steps:v1:mechanism=${floor.mechanism.name}",
			"continuity=${if (floor.continuousCoverageRequired) "REQUIRED" else "BEST_EFFORT"}",
			"max_report_latency_ms=${floor.maximumReportLatencyMs}",
		).joinToString(";")
		is AmbientStepsAcquisitionFloor -> listOf(
			"ambient-steps:v1:mechanism=${floor.mechanism.name}",
			"coverage=OPPORTUNISTIC",
			"record_freshness=SOURCE_NATIVE_CURSOR",
		).joinToString(";")
		is PressureAcquisitionFloor -> listOf(
			"pressure:v1:max_sample_period_us=${floor.maximumSamplePeriodMicros}",
			"max_report_latency_us=${floor.maximumReportLatencyMicros}",
			"max_window_ms=${floor.maximumAggregationWindowMs}",
		).joinToString(";")
		WifiBroadcastAcquisitionFloor -> "wifi:v1:required=BROADCAST_CALLBACK"
		CellCallbackAcquisitionFloor -> "cell:v1:required=CHANGE_CALLBACK"
	}

	fun decode(spec: String): SourceAcquisitionFloor {
		require(spec.isNotBlank())
		val floor = when {
			spec.startsWith("location:v1:") -> LocationAcquisitionFloor(
				LocationFloorMode.valueOf(fields(spec).getValue("min_mode")),
			)
			spec.startsWith("activity:v1:") -> ActivityAcquisitionFloor(
				fields(spec).getValue("capabilities").split(',')
					.map(ActivityAcquisitionCapability::valueOf)
					.toSet(),
			)
			spec.startsWith("steps:v1:") -> StepsAcquisitionFloor(
				mechanism = StepsAcquisitionMechanism.valueOf(fields(spec).getValue("mechanism")),
				continuousCoverageRequired = when (fields(spec).getValue("continuity")) {
					"REQUIRED" -> true
					"BEST_EFFORT" -> false
					else -> error("Unknown Steps continuity contract")
				},
				maximumReportLatencyMs = fields(spec).getValue("max_report_latency_ms").toLong(),
			)
			spec.startsWith("ambient-steps:v1:") -> {
				val values = fields(spec)
				require(values.getValue("coverage") == "OPPORTUNISTIC")
				require(values.getValue("record_freshness") == "SOURCE_NATIVE_CURSOR")
				AmbientStepsAcquisitionFloor(
					mechanism = AmbientStepsAcquisitionMechanism.valueOf(values.getValue("mechanism")),
				)
			}
			spec.startsWith("pressure:v1:") -> PressureAcquisitionFloor(
				maximumSamplePeriodMicros = fields(spec).getValue("max_sample_period_us").toInt(),
				maximumReportLatencyMicros = fields(spec).getValue("max_report_latency_us").toInt(),
				maximumAggregationWindowMs = fields(spec).getValue("max_window_ms").toLong(),
			)
			spec == "wifi:v1:required=BROADCAST_CALLBACK" -> WifiBroadcastAcquisitionFloor
			spec == "cell:v1:required=CHANGE_CALLBACK" -> CellCallbackAcquisitionFloor
			else -> error("Unsupported acquisition floor spec")
		}
		require(encode(floor) == spec) { "Acquisition floor spec is not canonical" }
		return floor
	}

	private fun fields(spec: String): Map<String, String> = spec.substringAfter(":v1:")
		.split(';')
		.associate { part ->
			val separator = part.indexOf('=')
			require(separator > 0 && separator < part.lastIndex)
			part.substring(0, separator) to part.substring(separator + 1)
		}
}
