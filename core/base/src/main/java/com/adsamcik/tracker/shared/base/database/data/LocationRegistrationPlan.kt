package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Exact resolved request made for this physical generation; desired-plan bytes remain immutable. */
@Entity(tableName = "location_registration_plan", primaryKeys = ["registration_generation",
    "configuration_revision", "authorization_revision"])
data class LocationRegistrationPlanEntity(
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "configuration_revision") val configurationRevision: Long,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint") val physicalConfigurationFingerprint: String,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "payload") val payload: ByteArray,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
) {
	init {
		require(registrationGeneration > 0 && configurationRevision >= 0 && authorizationRevision > 0 &&
            payloadVersion > 0)
		require(sourceInstanceId.isNotBlank() && physicalConfigurationFingerprint.isNotBlank())
		require(payload.isNotEmpty() && payload.size <= MAX_LOCATION_REGISTRATION_PLAN_PAYLOAD_BYTES)
		require(payloadChecksum.matches(Regex("[0-9a-f]{64}")))
		require(effectChecksum == LocationRegistrationPlanIntegrity.checksum(this))
	}
	/**
	 * Compares registration-plan values and payload bytes by content rather than byte-array
	 * reference.
	 */
	fun hasSameValue(other: LocationRegistrationPlanEntity): Boolean =
		effectChecksum == other.effectChecksum && payload.contentEquals(other.payload)
}

/**
 * Content-based Location plan comparison and digests preserving exact applied
 * registration/configuration metadata.
 */
object LocationRegistrationPlanIntegrity {
	/**
	 * Copies the Location plan payload and hashes its exact registration, configuration,
	 * authorization and payload metadata.
	 */
	fun create(registrationGeneration: Long, sourceInstanceId: String, configurationRevision: Long,
        authorizationRevision: Long,
		physicalConfigurationFingerprint: String, payloadVersion: Int, payloadChecksum: String,
		payload: ByteArray): LocationRegistrationPlanEntity = LocationRegistrationPlanEntity(
		registrationGeneration, sourceInstanceId, configurationRevision, authorizationRevision,
            physicalConfigurationFingerprint,
		payloadVersion, payloadChecksum, payload.copyOf(), checksum(registrationGeneration, sourceInstanceId,
			configurationRevision, authorizationRevision, physicalConfigurationFingerprint, payloadVersion,
                payloadChecksum, payload),
	)
	/**
	 * Computes the deterministic stored digest from the supplied identity, revision and value fields.
	 */
	fun checksum(value: LocationRegistrationPlanEntity): String = checksum(value.registrationGeneration,
		value.sourceInstanceId, value.configurationRevision, value.authorizationRevision,
            value.physicalConfigurationFingerprint,
		value.payloadVersion, value.payloadChecksum, value.payload)
	private fun checksum(generation: Long, instance: String, revision: Long, authorizationRevision: Long,
        fingerprint: String,
		version: Int, payloadChecksum: String, payload: ByteArray): String = AmbientWifiAuthorityIntegrity.digest(
		"location-registration-plan-v1", generation, instance, revision, authorizationRevision, fingerprint, version,
            payloadChecksum,
		java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
	)
}

private const val MAX_LOCATION_REGISTRATION_PLAN_PAYLOAD_BYTES = 65_536
