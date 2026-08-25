package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.TrackingRolloutStateEntity
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton

data class ExecutableSourceLaneBinding(
	val source: SourceKind,
	val bindingGeneration: Long,
	val projectionId: String,
	val projectionVersion: Int,
	val captureModes: Set<CaptureReachabilityMode>,
) {
	init {
		require(bindingGeneration > 0L)
		require(projectionId.isNotBlank())
		require(projectionVersion > 0)
		require(captureModes.isNotEmpty())
	}

	val captureModeMask: Long = captureModes.fold(0L) { mask, mode -> mask or mode.mask }
}

/** App-owned immutable list of source lanes that this binary can actually execute. */
@Singleton
class ExecutableSourceLaneCatalog internal constructor(
	bindings: Set<ExecutableSourceLaneBinding>,
) : SourceProductLaneExecutionAuthority {
	@Inject constructor() : this(emptySet())

	private val bindingsByGeneration = bindings.associateBy { binding ->
		binding.source to binding.bindingGeneration
	}.also { indexed ->
		require(indexed.size == bindings.size) {
			"Each source binding generation must name exactly one executable writer"
		}
		bindings.groupBy { it.projectionId to it.projectionVersion }.forEach { (writer, generations) ->
			require(generations.map(ExecutableSourceLaneBinding::source).distinct().size == 1) {
				"Writer identity ${writer.first}:${writer.second} cannot belong to multiple sources"
			}
		}
	}

	fun owns(binding: ExecutableSourceLaneBinding): Boolean =
		bindingsByGeneration[binding.source to binding.bindingGeneration] == binding

	fun bindingFor(lane: SourceProductProjectionLaneEntity): ExecutableSourceLaneBinding? {
		val source = SourceKind.entries.singleOrNull { it.stableCode == lane.sourceKind } ?: return null
		return bindingsByGeneration[source to lane.bindingGeneration]?.takeIf { binding ->
			binding.projectionId == lane.projectionId &&
				binding.projectionVersion == lane.projectionVersion &&
				binding.captureModeMask == lane.captureModeMask
		}
	}

	override fun owns(lane: SourceProductProjectionLaneEntity): Boolean = bindingFor(lane) != null

	companion object {
		fun explicit(vararg bindings: ExecutableSourceLaneBinding) =
			ExecutableSourceLaneCatalog(bindings.toSet())
	}
}

interface TrackingRolloutStateStore {
	suspend fun load(): TrackingRolloutState
	suspend fun save(state: TrackingRolloutState, updatedAtMs: Long)
}

@Singleton
class RoomTrackingRolloutStateStore @Inject constructor(
	private val database: AppDatabase,
	private val executableLaneCatalog: ExecutableSourceLaneCatalog,
) : TrackingRolloutStateStore {
	constructor(database: AppDatabase) : this(database, ExecutableSourceLaneCatalog())

	override suspend fun load(): TrackingRolloutState = database.withTransaction {
		val dao = database.trackingRolloutStateDao()
		val currentEntity = dao.get()
		val current = currentEntity?.decodeCurrentModelOrNull()
		if (current != null) {
			val activeLanes = database.sourceProjectionStateDao().allActiveProductLanes()
			val authorized = current.withoutUnbackedProductLanes(
				database,
				activeLanes,
				executableLaneCatalog,
			)
			if (authorized == current) {
				retireUnauthorizedActiveLanes(activeLanes, current, System.currentTimeMillis())
				return@withTransaction current
			}

			check(current.revision < Long.MAX_VALUE) { "Tracking rollout revision exhausted" }
			return@withTransaction authorized.copy(revision = current.revision + 1L).also { repaired ->
				dao.save(repaired.toEntity(System.currentTimeMillis()))
				retireUnauthorizedActiveLanes(activeLanes, repaired, System.currentTimeMillis())
			}
		}

		// v28 never shipped. Any v27/global-v2 rollout row predates source-local product reachability
		// and therefore cannot authorize a provider in this binary. Preserve its revision history, but
		// contain every acquisition owner until an explicit source-local shadow gate is persisted.
		val currentRevision = currentEntity?.revision ?: 0L
		check(currentRevision < Long.MAX_VALUE) { "Tracking rollout revision exhausted" }
		TrackingRolloutState.contained(revision = currentRevision + 1L).also { migrated ->
			val repairedAtMs = System.currentTimeMillis()
			dao.save(migrated.toEntity(repairedAtMs))
			retireUnauthorizedActiveLanes(
				database.sourceProjectionStateDao().allActiveProductLanes(),
				migrated,
				repairedAtMs,
			)
		}
	}

	override suspend fun save(state: TrackingRolloutState, updatedAtMs: Long) {
		require(updatedAtMs >= 0L)
		database.withTransaction {
			val current = database.trackingRolloutStateDao().get()
			require(current == null || state.revision > current.revision) {
				"Tracking rollout revisions must advance monotonically"
			}
			val authorized = state.withoutUnbackedProductLanes(
				database,
				database.sourceProjectionStateDao().allActiveProductLanes(),
				executableLaneCatalog,
			)
			require(authorized == state) {
				"Capture rollout requires an active matching source-local product lane and cursor"
			}
			database.trackingRolloutStateDao().save(state.toEntity(updatedAtMs))
		}
	}

	/**
	 * Atomically installs the first concrete shadow product [binding] and makes only that source
	 * capture-reachable. The activation starts after the durable WAL high-water and its
	 * initialized cursor is a mandatory retention pin. Canonical cutover intentionally has no API
	 * here; it needs source-specific shadow evidence and legacy-writer fencing first. The binding's
	 * projection ID/version names the writer semantics; its source-local binding generation names
	 * the independently gated set of capture modes that this binary can execute.
	 */
	suspend fun installAndActivateShadowLane(
		binding: ExecutableSourceLaneBinding,
		rolloutRevision: Long,
		updatedAtMs: Long,
	): SourceProductLaneActivation = activateShadowLane(
		binding = binding,
		rolloutRevision = rolloutRevision,
		updatedAtMs = updatedAtMs,
		rearm = false,
	)

	suspend fun rearmAndActivateShadowLane(
		binding: ExecutableSourceLaneBinding,
		rolloutRevision: Long,
		updatedAtMs: Long,
	): SourceProductLaneActivation = activateShadowLane(
		binding = binding,
		rolloutRevision = rolloutRevision,
		updatedAtMs = updatedAtMs,
		rearm = true,
	)

	private suspend fun activateShadowLane(
		binding: ExecutableSourceLaneBinding,
		rolloutRevision: Long,
		updatedAtMs: Long,
		rearm: Boolean,
	): SourceProductLaneActivation {
		require(executableLaneCatalog.owns(binding)) {
			"Source lane binding is not executable by this app binary"
		}
		require(rolloutRevision > 0L) { "Rollout revision must be positive" }
		require(updatedAtMs >= 0L)
		return database.withTransaction {
			val rolloutDao = database.trackingRolloutStateDao()
			val projectionDao = database.sourceProjectionStateDao()
			val currentEntity = rolloutDao.get()
			require(currentEntity == null || rolloutRevision > currentEntity.revision) {
				"Tracking rollout revisions must advance monotonically"
			}
			require(projectionDao.activeProductLane(binding.source.stableCode) == null) {
				"Source ${binding.source} already has an active product lane"
			}
			val latest = projectionDao.latestProductLane(binding.source.stableCode)
			if (rearm) {
				val terminalLatest = requireNotNull(latest) {
					"Source ${binding.source} has no terminal lane to re-arm"
				}
				require(terminalLatest.status == SourceProductProjectionLaneEntity.STATUS_RETIRED) {
					"Source ${binding.source} has no terminal lane to re-arm"
				}
				require(binding.bindingGeneration > terminalLatest.bindingGeneration) {
					"Re-arm requires a newer executable binding generation"
				}
			} else {
				require(latest == null) { "Initial activation cannot replace source lane history" }
			}
			require(
				projectionDao.productLanesByProjection(binding.projectionId, binding.projectionVersion)
					.all { it.sourceKind == binding.source.stableCode },
			) {
				"Projection identity ${binding.projectionId}:${binding.projectionVersion} belongs to another source"
			}
			require(projectionDao.registration(binding.projectionId, binding.projectionVersion) == null) {
				"Projection identity ${binding.projectionId}:${binding.projectionVersion} is already registered globally"
			}
			requireCaptureAdmissionFenced(binding.source.stableCode)

			val activeLanes = projectionDao.allActiveProductLanes()
			val current = currentEntity?.decodeCurrentModelOrNull()
				?.withoutUnbackedProductLanes(database, activeLanes, executableLaneCatalog)
				?: TrackingRolloutState.contained(revision = currentEntity?.revision ?: 0L)
			val activationOrdinal = database.liveSourceProjectionActivationOrdinal()
			val lane = SourceProductProjectionLaneEntity(
				sourceKind = binding.source.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				captureModeMask = binding.captureModeMask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = rolloutRevision,
				activationOrdinal = activationOrdinal,
				contiguousAdmissionOrdinal = activationOrdinal - 1L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = updatedAtMs,
				updatedAtMs = updatedAtMs,
			)
			projectionDao.installProductLane(lane)
			val rollout = current.copy(
				revision = rolloutRevision,
				sourceOwners = current.sourceOwners + (binding.source to SourceOwner.EVENT),
				productProjectionStages = current.productProjectionStages +
					(binding.source to ProductProjectionStage.EVENT_SHADOW),
				captureModeMasks = current.captureModeMasks +
					(binding.source to binding.captureModeMask),
			)
			check(
				rollout.withoutUnbackedProductLanes(
					database,
					projectionDao.allActiveProductLanes(),
					executableLaneCatalog,
				) == rollout,
			) { "Installed source lane did not authorize its exact rollout stage" }
			rolloutDao.save(rollout.toEntity(updatedAtMs))
			SourceProductLaneActivation(rollout, lane)
		}
	}

	/**
	 * Phase one of lane retirement. Every nonterminal provider generation must first publish a
	 * durable callback-barrier acknowledgement through its last capture authorization. The
	 * source-local admission cutoff and contained rollout are then committed together, closing a
	 * complete WAL interval without dropping a callback that entered before capture was fenced.
	 */
	suspend fun beginLaneContainment(
		binding: ExecutableSourceLaneBinding,
		rolloutRevision: Long,
		updatedAtMs: Long,
	): SourceProductLaneRetirementFence {
		require(executableLaneCatalog.owns(binding))
		require(updatedAtMs >= 0L)
		return database.withTransaction {
			val rolloutDao = database.trackingRolloutStateDao()
			val current = requireNotNull(rolloutDao.get()?.decodeCurrentModelOrNull())
			require(rolloutRevision > current.revision)
			val projectionDao = database.sourceProjectionStateDao()
			val lane = requireNotNull(projectionDao.activeProductLane(binding.source.stableCode))
			require(lane.matches(binding, ProductProjectionStage.EVENT_SHADOW, current.revision))
			requireCaptureAdmissionFenced(binding.source.stableCode)
			check(lane.captureAdmissionCutoffOrdinal == null) {
				"Source product lane capture admission is already fenced"
			}
			val cutoffOrdinal = database.liveSourceProjectionActivationOrdinal() - 1L
			val contained = current.copy(
				revision = rolloutRevision,
				sourceOwners = current.sourceOwners + (binding.source to SourceOwner.CONTAINED),
				productProjectionStages = current.productProjectionStages +
					(binding.source to ProductProjectionStage.LEGACY_CANONICAL),
				captureModeMasks = current.captureModeMasks + (binding.source to 0L),
			)
			// These writes share one Room transaction: either ingress sees the old reachable lane and
			// contributes to this cutoff, or it sees the closed interval and rejects capture.
			rolloutDao.save(contained.toEntity(updatedAtMs))
			check(projectionDao.fenceProductLaneCaptureAdmission(
				sourceKind = binding.source.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				cutoffOrdinal = cutoffOrdinal,
				updatedAtMs = updatedAtMs,
			) == 1) { "Exact active source lane changed before capture admission was fenced" }
			SourceProductLaneRetirementFence(contained, lane.copy(
				captureAdmissionCutoffOrdinal = cutoffOrdinal,
				updatedAtMs = updatedAtMs,
			))
		}
	}

	/** Releases a contained lane only after its exact, persisted capture interval is drained. */
	suspend fun finishLaneRetirement(
		binding: ExecutableSourceLaneBinding,
		expectedCurrentOrdinal: Long,
		updatedAtMs: Long,
	): TrackingRolloutState {
		require(executableLaneCatalog.owns(binding))
		require(expectedCurrentOrdinal >= 0L)
		require(updatedAtMs >= 0L)
		return database.withTransaction {
			val rollout = requireNotNull(
				database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull(),
			)
			require(!rollout.isAcquisitionReachable(binding.source)) {
				"Source rollout must remain contained while its lane drains"
			}
			val projectionDao = database.sourceProjectionStateDao()
			val lane = requireNotNull(projectionDao.activeProductLane(binding.source.stableCode))
			require(lane.bindingGeneration == binding.bindingGeneration &&
				lane.projectionId == binding.projectionId &&
				lane.projectionVersion == binding.projectionVersion
			) { "A different source lane is active" }
			val cutoffOrdinal = requireNotNull(lane.captureAdmissionCutoffOrdinal) {
				"Source capture admission must be fenced before retirement"
			}
			require(lane.contiguousAdmissionOrdinal == expectedCurrentOrdinal)
			require(expectedCurrentOrdinal == cutoffOrdinal) {
				"Source product lane must drain through its durable capture cutoff before retirement"
			}
			check(projectionDao.retireFencedProductLane(
				sourceKind = binding.source.stableCode,
				bindingGeneration = binding.bindingGeneration,
				projectionId = binding.projectionId,
				projectionVersion = binding.projectionVersion,
				expectedCurrentOrdinal = expectedCurrentOrdinal,
				expectedCutoffOrdinal = cutoffOrdinal,
				disposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = updatedAtMs,
			) == 1) { "Exact fenced source lane changed before retirement" }
			rollout
		}
	}

	private suspend fun retireUnauthorizedActiveLanes(
		activeLanes: List<SourceProductProjectionLaneEntity>,
		repaired: TrackingRolloutState,
		updatedAtMs: Long,
	) {
		activeLanes.filter { lane ->
			!lane.isAuthorizedBy(repaired, activeLanes, executableLaneCatalog)
		}.forEach { lane ->
			val projectionDao = database.sourceProjectionStateDao()
			if (!lane.retentionRequired) {
				check(projectionDao.retireProductLane(
					sourceKind = lane.sourceKind,
					bindingGeneration = lane.bindingGeneration,
					projectionId = lane.projectionId,
					projectionVersion = lane.projectionVersion,
					expectedCurrentOrdinal = lane.contiguousAdmissionOrdinal,
					updatedAtMs = updatedAtMs,
				) == 1) { "Non-retaining conflicting lane changed during containment repair" }
				return@forEach
			}
			if (!isCaptureAdmissionFenced(lane.sourceKind)) return@forEach
			val cutoffOrdinal = lane.captureAdmissionCutoffOrdinal
				?: (database.liveSourceProjectionActivationOrdinal() - 1L).also { cutoff ->
					check(projectionDao.fenceProductLaneCaptureAdmission(
						sourceKind = lane.sourceKind,
						bindingGeneration = lane.bindingGeneration,
						projectionId = lane.projectionId,
						projectionVersion = lane.projectionVersion,
						cutoffOrdinal = cutoff,
						updatedAtMs = updatedAtMs,
					) == 1) { "Unauthorized source lane changed before admission fencing" }
				}
			if (!executableLaneCatalog.owns(lane)) {
				// Unknown writer semantics cannot prove a queryable completeness gap. Keep this lane as
				// a source-local retention pin; it cannot block sibling sources and a future binary may
				// supply the exact executable binding needed to drain it safely.
				return@forEach
			}
			if (lane.contiguousAdmissionOrdinal != cutoffOrdinal) {
				// Keep the cursor as a retention pin until the already-admitted interval drains. The
				// repaired rollout and persisted cutoff deny new capture admission immediately.
				return@forEach
			}
			check(projectionDao.retireFencedProductLane(
				sourceKind = lane.sourceKind,
				bindingGeneration = lane.bindingGeneration,
				projectionId = lane.projectionId,
				projectionVersion = lane.projectionVersion,
				expectedCurrentOrdinal = lane.contiguousAdmissionOrdinal,
				expectedCutoffOrdinal = cutoffOrdinal,
				disposition = SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN,
				terminalAtMs = updatedAtMs,
			) == 1) { "Unauthorized source lane changed during containment repair" }
		}
	}

	private suspend fun requireCaptureAdmissionFenced(sourceKind: Int) {
		require(isCaptureAdmissionFenced(sourceKind)) {
			"Source capture must cross its demand and callback-authorization retirement barrier first"
		}
	}

	private suspend fun isCaptureAdmissionFenced(sourceKind: Int): Boolean {
		val brokerDao = database.sourceBrokerDao()
		val capturePurposes = setOf(
			SourceBrokerPurpose.SESSION_CAPTURE,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		if (brokerDao.activeDemands(sourceKind).any { it.purpose in capturePurposes }) return false

		val nonterminalRegistrations = (
			brokerDao.currentPhysicalRegistrations(sourceKind) +
				brokerDao.pendingProviderRemovals(sourceKind)
		).distinctBy { it.registrationGeneration }
		return nonterminalRegistrations.all { registration ->
			val latestAuthorization = brokerDao.latestAuthorization(
				sourceKind,
				registration.registrationGeneration,
			)
			if (latestAuthorization.isEmpty() || latestAuthorization.any { authorization ->
				authorization.persistenceEligible && authorization.purpose in capturePurposes
			}) return@all false
			val captureRevision = brokerDao.maximumCaptureAuthorizationRevision(
				sourceKind,
				registration.registrationGeneration,
			)
			registration.captureCallbackBarrierAuthorizationRevision >= captureRevision
		}
	}
}

data class SourceProductLaneActivation(
	val rollout: TrackingRolloutState,
	val lane: SourceProductProjectionLaneEntity,
)

data class SourceProductLaneRetirementFence(
	val rollout: TrackingRolloutState,
	val lane: SourceProductProjectionLaneEntity,
) {
	val cutoffOrdinal: Long = requireNotNull(lane.captureAdmissionCutoffOrdinal)
}

private fun TrackingRolloutStateEntity.decodeCurrentModelOrNull(): TrackingRolloutState? =
	if (schemaVersion != TrackingRolloutState.CURRENT_SCHEMA_VERSION) {
		null
	} else {
		runCatching { toModel() }.getOrNull()
	}

private suspend fun TrackingRolloutState.withoutUnbackedProductLanes(
	database: AppDatabase,
	activeLanes: List<SourceProductProjectionLaneEntity>,
	executableLaneCatalog: ExecutableSourceLaneCatalog,
): TrackingRolloutState {
	val lanesBySource = activeLanes.groupBy(SourceProductProjectionLaneEntity::sourceKind)
	val unbacked = mutableSetOf<SourceKind>()
	for (source in SourceKind.entries) {
		if (!isAcquisitionReachable(source)) continue
		val stage = productProjectionStages.getValue(source)
		val lane = lanesBySource[source.stableCode]?.singleOrNull()
		val binding = lane?.let(executableLaneCatalog::bindingFor)
		val backed = if (lane == null || binding == null) {
			false
		} else {
			captureModeMasks.getValue(source) == lane.captureModeMask &&
				lane.matches(binding, stage, revision) &&
				database.sourceProjectionStateDao().isProductLaneReachable(
					sourceKind = source.stableCode,
					productStage = stage.name,
					rolloutRevision = revision,
				)
		}
		if (!backed) unbacked += source
	}
	if (unbacked.isEmpty()) return this
	return copy(
		sourceOwners = sourceOwners.mapValues { (source, owner) ->
			if (source in unbacked) SourceOwner.CONTAINED else owner
		},
		productProjectionStages = productProjectionStages.mapValues { (source, stage) ->
			if (source in unbacked) ProductProjectionStage.LEGACY_CANONICAL else stage
		},
		captureModeMasks = captureModeMasks.mapValues { (source, mask) ->
			if (source in unbacked) 0L else mask
		},
	)
}

private fun SourceProductProjectionLaneEntity.isAuthorizedBy(
	rollout: TrackingRolloutState,
	activeLanes: List<SourceProductProjectionLaneEntity>,
	executableLaneCatalog: ExecutableSourceLaneCatalog,
): Boolean {
	val source = SourceKind.entries.singleOrNull { it.stableCode == sourceKind } ?: return false
	if (activeLanes.count { it.sourceKind == sourceKind } != 1) return false
	if (!rollout.isAcquisitionReachable(source)) return false
	val binding = executableLaneCatalog.bindingFor(this) ?: return false
	return captureModeMask == rollout.captureModeMasks.getValue(source) &&
		matches(binding, rollout.productProjectionStages.getValue(source), rollout.revision)
}

private fun SourceProductProjectionLaneEntity.matches(
	binding: ExecutableSourceLaneBinding,
	stage: ProductProjectionStage,
	rolloutRevision: Long,
): Boolean = sourceKind == binding.source.stableCode &&
	bindingGeneration == binding.bindingGeneration &&
	projectionId == binding.projectionId &&
	projectionVersion == binding.projectionVersion &&
	captureModeMask == binding.captureModeMask &&
	productStage == stage.name &&
	stage in setOf(ProductProjectionStage.EVENT_SHADOW, ProductProjectionStage.EVENT_CANONICAL) &&
	activatedRolloutRevision > 0L &&
	activatedRolloutRevision <= rolloutRevision &&
	activationOrdinal > 0L &&
	contiguousAdmissionOrdinal >= activationOrdinal - 1L &&
	retentionRequired &&
	status == SourceProductProjectionLaneEntity.STATUS_ACTIVE

private fun TrackingRolloutState.toEntity(updatedAtMs: Long) = TrackingRolloutStateEntity(
	revision = revision,
	schemaVersion = schemaVersion,
	coordinatorMode = coordinatorMode.name,
	projectionMode = productProjectionStages.entries.sortedBy { it.key.stableCode }
		.joinToString(",") { (source, stage) ->
			"${source.stableCode}:${stage.name}:${captureModeMasks.getValue(source)}"
		},
	sourceOwners = sourceOwners.entries.sortedBy { it.key.stableCode }
		.joinToString(",") { (source, owner) -> "${source.stableCode}:${owner.name}" },
	semanticSettingsEnabled = semanticSettingsEnabled,
	batteryEstimateMode = batteryEstimateMode.name,
	updatedAtMs = updatedAtMs,
)

private fun TrackingRolloutStateEntity.toModel(): TrackingRolloutState {
	val owners = sourceOwners.split(',')
		.filter(String::isNotBlank)
		.associate { encoded ->
			val (sourceCode, ownerName) = encoded.split(':', limit = 2)
			val source = SourceKind.entries.single { it.stableCode == sourceCode.toInt() }
			source to SourceOwner.valueOf(ownerName)
		}
	return TrackingRolloutState(
		revision = revision,
		schemaVersion = schemaVersion,
		coordinatorMode = CoordinatorMode.valueOf(coordinatorMode),
		sourceOwners = owners,
		productProjectionStages = decodeProductProjectionStages(projectionMode),
		captureModeMasks = decodeCaptureModeMasks(projectionMode),
		semanticSettingsEnabled = semanticSettingsEnabled,
		batteryEstimateMode = BatteryEstimateMode.valueOf(batteryEstimateMode),
	)
}

/** Decode unreleased v28 global fixtures without allowing them to drive future all-source cutover. */
private fun decodeProductProjectionStages(encoded: String): Map<SourceKind, ProductProjectionStage> {
	if (':' !in encoded) {
		val stage = when (encoded) {
			"LEGACY_ONLY" -> ProductProjectionStage.LEGACY_CANONICAL
			"SHADOW_READ_ONLY", "EVENT_CANONICAL" -> ProductProjectionStage.EVENT_SHADOW
			else -> error("Unknown product projection rollout encoding")
		}
		return SourceKind.entries.associateWith { stage }
	}
	return encoded.split(',')
		.filter(String::isNotBlank)
		.associate { value ->
			val (sourceCode, stageName) = value.split(':', limit = 3)
			SourceKind.entries.single { it.stableCode == sourceCode.toInt() } to
				ProductProjectionStage.valueOf(stageName)
		}
}

private fun decodeCaptureModeMasks(encoded: String): Map<SourceKind, Long> {
	if (':' !in encoded) return SourceKind.entries.associateWith { 0L }
	val decoded = encoded.split(',').filter(String::isNotBlank).associate { value ->
		val parts = value.split(':', limit = 3)
		val source = SourceKind.entries.single { it.stableCode == parts[0].toInt() }
		source to (parts.getOrNull(2)?.toLong() ?: 0L)
	}
	return SourceKind.entries.associateWith { source -> decoded[source] ?: 0L }
}
