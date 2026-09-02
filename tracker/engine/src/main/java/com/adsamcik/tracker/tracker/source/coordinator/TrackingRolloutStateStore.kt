package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
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
	@Inject constructor() : this(
		setOf(STEPS_SESSION_FACTS_V1, STEPS_SESSION_FACTS_V2, PRESSURE_SESSION_FACTS),
	)

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

	fun bindingFor(
		source: SourceKind,
		bindingGeneration: Long,
		projectionId: String?,
		projectionVersion: Int?,
	): ExecutableSourceLaneBinding? = bindingsByGeneration[source to bindingGeneration]
		?.takeIf { binding ->
			binding.projectionId == projectionId && binding.projectionVersion == projectionVersion
		}

	/** Resolves one exact executable binding for a source mask, failing closed on ambiguity. */
	fun bindingForCaptureModeMask(
		source: SourceKind,
		captureModeMask: Long,
	): ExecutableSourceLaneBinding? = bindingsByGeneration.values.singleOrNull { binding ->
		binding.source == source && binding.captureModeMask == captureModeMask
	}

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
		/**
		 * The binary can execute this lane, but that fact alone authorizes neither acquisition nor
		 * canonical publication. The durable lane, rollout, and destination owner must still agree.
		 */
		val STEPS_SESSION_FACTS_V1 = ExecutableSourceLaneBinding(
			source = SourceKind.STEPS,
			bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION,
			captureModes = setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
		)

		/**
		 * Generation 2 is a new immutable contract: it preserves manual capture and additionally
		 * permits automatic session capture. Generation 1 remains executable for retained history.
		 */
		val STEPS_SESSION_FACTS_V2 = STEPS_SESSION_FACTS_V1.copy(
			bindingGeneration = SourceDestinationOwnerEntity.STEPS_FACT_AUTOMATIC_BINDING_GENERATION,
			captureModes = setOf(
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
				CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
			),
		)

		/** Compatibility name for the immutable generation-1 contract. */
		val STEPS_SESSION_FACTS = STEPS_SESSION_FACTS_V1

		/** Prospective binding used only when no durable Steps lane supplies an exact generation. */
		val PREFERRED_STEPS_SESSION_FACTS = STEPS_SESSION_FACTS_V2

		/** Dormant source-local Pressure writer contract; executable does not imply activation. */
		val PRESSURE_SESSION_FACTS = ExecutableSourceLaneBinding(
			source = SourceKind.PRESSURE,
			bindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			projectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			projectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			captureModes = setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE),
		)

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
		// contain every acquisition owner until an explicit source-local canonical cutover is persisted.
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
	 * Atomically installs the first concrete, product-inert shadow [binding]. The lane starts after
	 * the durable WAL high-water and its initialized cursor is a mandatory retention pin, but the
	 * rollout remains contained: legacy product ownership, no provider ownership, and no capture
	 * modes. Canonical cutover intentionally has no API here; it needs source-specific shadow
	 * evidence, production-reader readiness, and legacy-writer fencing first. The binding's
	 * projection ID/version names the writer semantics; its source-local binding generation names
	 * the capture modes a later canonical cutover may authorize.
	 */
	suspend fun installInertShadowLane(
		binding: ExecutableSourceLaneBinding,
		rolloutRevision: Long,
		updatedAtMs: Long,
	): SourceProductLaneActivation = installShadowLane(
		binding = binding,
		rolloutRevision = rolloutRevision,
		updatedAtMs = updatedAtMs,
		rearm = false,
	)

	suspend fun rearmInertShadowLane(
		binding: ExecutableSourceLaneBinding,
		rolloutRevision: Long,
		updatedAtMs: Long,
	): SourceProductLaneActivation = installShadowLane(
		binding = binding,
		rolloutRevision = rolloutRevision,
		updatedAtMs = updatedAtMs,
		rearm = true,
	)

	private suspend fun installShadowLane(
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
			database.requireSourceCaptureAdmissionFenced(binding.source.stableCode)

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
				sourceOwners = current.sourceOwners + (binding.source to SourceOwner.CONTAINED),
				productProjectionStages = current.productProjectionStages +
					(binding.source to ProductProjectionStage.LEGACY_CANONICAL),
				captureModeMasks = current.captureModeMasks + (binding.source to 0L),
			)
			check(
				lane.isAuthorizedBy(
					database = database,
					rollout = rollout,
					activeLanes = projectionDao.allActiveProductLanes(),
					executableLaneCatalog = executableLaneCatalog,
				),
			) { "Installed shadow lane is not an exact product-inert executable binding" }
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
			database.requireSourceCaptureAdmissionFenced(binding.source.stableCode)
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
			require(!lane.isRetainedCanonicalStepsRollbackLane(
					database,
					rollout,
					executableLaneCatalog,
				)
			) {
				"Candidate-owned canonical Steps retirement must use its dedicated owner transition"
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
			!lane.isAuthorizedBy(database, repaired, activeLanes, executableLaneCatalog)
		}.forEach { lane ->
			val projectionDao = database.sourceProjectionStateDao()
			if (lane.isRetainedCanonicalStepsRollbackLane(
					database,
					repaired,
					executableLaneCatalog,
				)
			) {
				// A contained canonical Steps lane may be between rollback phases. Keep its WAL pin
				// until the dedicated coordinator atomically retires it and advances the permanent
				// destination owner. Generic repair must never perform half of that transition.
				return@forEach
			}
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
			if (!database.isSourceCaptureAdmissionFenced(lane.sourceKind)) return@forEach
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

}

internal suspend fun AppDatabase.requireSourceCaptureAdmissionFenced(sourceKind: Int) {
	require(isSourceCaptureAdmissionFenced(sourceKind)) {
		"Source capture must cross its demand and callback-authorization retirement barrier first"
	}
}

internal suspend fun AppDatabase.isSourceCaptureAdmissionFenced(sourceKind: Int): Boolean {
	val brokerDao = sourceBrokerDao()
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

private suspend fun SourceProductProjectionLaneEntity.isRetainedCanonicalStepsRollbackLane(
	database: AppDatabase,
	rollout: TrackingRolloutState,
	executableLaneCatalog: ExecutableSourceLaneCatalog,
): Boolean {
	if (!isFencedCanonicalStepsLane() || !rollout.hasContainedStepsRollbackShape()) return false
	val binding = executableLaneCatalog.bindingFor(this) ?: return false
	return matches(binding, ProductProjectionStage.EVENT_CANONICAL, rollout.revision) &&
		database.sourceDestinationOwnerDao().get(
		SourceDestinationOwnerEntity.SOURCE_STEPS,
		SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
	)?.owner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
}

private fun SourceProductProjectionLaneEntity.isFencedCanonicalStepsLane(): Boolean =
	sourceKind == SourceKind.STEPS.stableCode &&
		productStage == SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL &&
		captureAdmissionCutoffOrdinal != null

private fun TrackingRolloutState.hasContainedStepsRollbackShape(): Boolean =
	sourceOwners.getValue(SourceKind.STEPS) == SourceOwner.CONTAINED &&
		productProjectionStages.getValue(SourceKind.STEPS) == ProductProjectionStage.LEGACY_CANONICAL &&
		captureModeMasks.getValue(SourceKind.STEPS) == 0L

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

internal fun TrackingRolloutStateEntity.decodeCurrentModelOrNull(): TrackingRolloutState? =
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
				) && database.hasExactCanonicalDestinationOwner(binding, stage)
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

private suspend fun SourceProductProjectionLaneEntity.isAuthorizedBy(
	database: AppDatabase,
	rollout: TrackingRolloutState,
	activeLanes: List<SourceProductProjectionLaneEntity>,
	executableLaneCatalog: ExecutableSourceLaneCatalog,
): Boolean {
	val source = SourceKind.entries.singleOrNull { it.stableCode == sourceKind } ?: return false
	if (activeLanes.count { it.sourceKind == sourceKind } != 1) return false
	val binding = executableLaneCatalog.bindingFor(this) ?: return false
	return when (productStage) {
		SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW ->
			rollout.authorizesInertShadow(source) &&
				captureAdmissionCutoffOrdinal == null &&
				matches(binding, ProductProjectionStage.EVENT_SHADOW, rollout.revision)

		SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ->
			isCanonicalCaptureAuthorizedBy(database, rollout, executableLaneCatalog)

		else -> false
	}
}

private fun TrackingRolloutState.authorizesInertShadow(source: SourceKind): Boolean =
	sourceOwners.getValue(source) == SourceOwner.CONTAINED &&
		productProjectionStages.getValue(source) == ProductProjectionStage.LEGACY_CANONICAL &&
		captureModeMasks.getValue(source) == 0L

private suspend fun AppDatabase.hasExactCanonicalDestinationOwner(
	binding: ExecutableSourceLaneBinding,
	stage: ProductProjectionStage,
): Boolean {
	if (stage != ProductProjectionStage.EVENT_CANONICAL) return true
	return when (binding.source) {
		SourceKind.STEPS -> {
			if (binding.projectionId != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_ID ||
				binding.projectionVersion != SourceDestinationOwnerEntity.STEPS_FACT_PROJECTION_VERSION
			) return true
			val owner = sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_STEPS,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
			) ?: return false
			owner.owner == SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS &&
				owner.ownerGeneration >= SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		}
		SourceKind.PRESSURE -> {
			if (binding != ExecutableSourceLaneCatalog.PRESSURE_SESSION_FACTS) return false
			val owner = sourceDestinationOwnerDao().get(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
			) ?: return false
			owner.owner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS &&
				owner.ownerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		}
		else -> true
	}
}

/**
 * Transaction-local defense for durable capture admission. An executable lane alone is not
 * authority: an inert or repaired shadow remains a retention/validation lane and must never admit
 * new captured observations, even when an old provider authorization is still queryable.
 */
internal fun SourceProductProjectionLaneEntity.isCanonicalCaptureAuthorizedBy(
	rollout: TrackingRolloutState,
	executableLaneCatalog: ExecutableSourceLaneCatalog,
): Boolean {
	val source = SourceKind.entries.singleOrNull { it.stableCode == sourceKind } ?: return false
	val binding = executableLaneCatalog.bindingFor(this) ?: return false
	return rollout.isAcquisitionReachable(source) &&
		captureModeMask == rollout.captureModeMasks.getValue(source) &&
		matches(binding, ProductProjectionStage.EVENT_CANONICAL, rollout.revision)
}

internal suspend fun SourceProductProjectionLaneEntity.isCanonicalCaptureAuthorizedBy(
	database: AppDatabase,
	rollout: TrackingRolloutState,
	executableLaneCatalog: ExecutableSourceLaneCatalog,
): Boolean {
	val binding = executableLaneCatalog.bindingFor(this) ?: return false
	return isCanonicalCaptureAuthorizedBy(rollout, executableLaneCatalog) &&
		database.hasExactCanonicalDestinationOwner(
			binding,
			ProductProjectionStage.EVENT_CANONICAL,
		)
}

internal fun SourceProductProjectionLaneEntity.matches(
	binding: ExecutableSourceLaneBinding,
	stage: ProductProjectionStage,
	rolloutRevision: Long,
): Boolean {
	if (!matchesSourceBinding(binding)) return false
	if (!matchesWriterBinding(binding)) return false
	if (!matchesRolloutStage(stage, rolloutRevision)) return false
	return hasActiveContiguousCursor()
}

private fun SourceProductProjectionLaneEntity.matchesSourceBinding(
	binding: ExecutableSourceLaneBinding,
): Boolean = sourceKind == binding.source.stableCode &&
	bindingGeneration == binding.bindingGeneration

private fun SourceProductProjectionLaneEntity.matchesWriterBinding(
	binding: ExecutableSourceLaneBinding,
): Boolean = projectionId == binding.projectionId &&
	projectionVersion == binding.projectionVersion &&
	captureModeMask == binding.captureModeMask

private fun SourceProductProjectionLaneEntity.matchesRolloutStage(
	stage: ProductProjectionStage,
	rolloutRevision: Long,
): Boolean = productStage == stage.name &&
	stage in setOf(ProductProjectionStage.EVENT_SHADOW, ProductProjectionStage.EVENT_CANONICAL) &&
	activatedRolloutRevision > 0L &&
	activatedRolloutRevision <= rolloutRevision

private fun SourceProductProjectionLaneEntity.hasActiveContiguousCursor(): Boolean =
	activationOrdinal > 0L &&
		contiguousAdmissionOrdinal >= activationOrdinal - 1L &&
		retentionRequired &&
		status == SourceProductProjectionLaneEntity.STATUS_ACTIVE

internal fun TrackingRolloutState.toEntity(updatedAtMs: Long) = TrackingRolloutStateEntity(
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
