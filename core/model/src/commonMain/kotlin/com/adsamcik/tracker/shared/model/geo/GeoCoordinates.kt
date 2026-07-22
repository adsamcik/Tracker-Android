package com.adsamcik.tracker.shared.model.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.jvm.JvmInline

/**
 * Canonical Earth-coordinate primitives shared by import, spatial-index, and
 * geometry code. External coordinates are checked before they become E7;
 * cyclic longitude operations are deliberately exposed separately.
 */
object GeoCoordinates {
	const val E7_PER_DEGREE: Long = 10_000_000L
	const val MIN_LATITUDE_E7: Long = -900_000_000L
	const val MAX_LATITUDE_E7: Long = 900_000_000L
	const val MIN_LONGITUDE_E7: Long = -1_800_000_000L
	const val MAX_LONGITUDE_INPUT_E7: Long = 1_800_000_000L

	/**
	 * Quantizes a degree value only after the caller has established its finite
	 * Earth-range precondition. Kotlin's [roundToInt] is intentional: exact
	 * half-E7 ties round toward positive infinity.
	 */
	internal fun quantizeCheckedDegreesToE7(degrees: Double): Int {
		val scaled = degrees * E7_PER_DEGREE
		check(scaled.isFinite()) { "Checked degree value produced a non-finite E7 value" }
		check(scaled in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
			"Checked degree value produced an Int-overflowing E7 value"
		}
		return scaled.roundToInt()
	}
}

/** Latitude in canonical E7 form, constrained to the physical Earth range. */
@JvmInline
value class CheckedLatitudeE7 private constructor(val value: Int) {
	val degrees: Double get() = value.toDouble() / GeoCoordinates.E7_PER_DEGREE

	companion object {
		fun fromE7OrNull(value: Long): CheckedLatitudeE7? =
			if (value in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7) {
				CheckedLatitudeE7(value.toInt())
			} else {
				null
			}

		fun fromDegreesOrNull(degrees: Double): CheckedLatitudeE7? {
			if (!degrees.isFinite() || degrees !in -90.0..90.0) return null
			return fromE7OrNull(GeoCoordinates.quantizeCheckedDegreesToE7(degrees).toLong())
		}

		fun requireE7(value: Long): CheckedLatitudeE7 =
			requireNotNull(fromE7OrNull(value)) { "Latitude E7 outside [-90°, 90°]: $value" }

		fun requireDegrees(degrees: Double): CheckedLatitudeE7 =
			requireNotNull(fromDegreesOrNull(degrees)) { "Latitude outside finite [-90°, 90°]: $degrees" }
	}
}

/**
 * Longitude in canonical E7 form. Inputs accept both antimeridian spellings,
 * while values are always in [-180°, 180°).
 */
@JvmInline
value class CheckedLongitudeE7 private constructor(val value: Int) {
	val degrees: Double get() = value.toDouble() / GeoCoordinates.E7_PER_DEGREE

	companion object {
		fun fromE7OrNull(value: Long): CheckedLongitudeE7? {
			if (value !in GeoCoordinates.MIN_LONGITUDE_E7..GeoCoordinates.MAX_LONGITUDE_INPUT_E7) {
				return null
			}
			return CheckedLongitudeE7(CircularLongitude.normalizeE7(value).toInt())
		}

		fun fromDegreesOrNull(degrees: Double): CheckedLongitudeE7? {
			if (!degrees.isFinite() || degrees !in -180.0..180.0) return null
			return fromE7OrNull(GeoCoordinates.quantizeCheckedDegreesToE7(degrees).toLong())
		}

		fun requireE7(value: Long): CheckedLongitudeE7 =
			requireNotNull(fromE7OrNull(value)) { "Longitude E7 outside [-180°, 180°]: $value" }

		fun requireDegrees(degrees: Double): CheckedLongitudeE7 =
			requireNotNull(fromDegreesOrNull(degrees)) { "Longitude outside finite [-180°, 180°]: $degrees" }
	}
}

/**
 * An all-or-nothing checked coordinate pair. Exact poles have one spatial
 * identity, so their longitude is canonicalized to positive zero.
 */
@ConsistentCopyVisibility
data class CheckedCoordinateE7 private constructor(
	val latitude: CheckedLatitudeE7,
	val longitude: CheckedLongitudeE7,
) {
	val latitudeE7: Int get() = latitude.value
	val longitudeE7: Int get() = longitude.value
	val latitudeDegrees: Double get() = latitude.degrees
	val longitudeDegrees: Double get() = longitude.degrees

	companion object {
		fun fromE7OrNull(latitudeE7: Long, longitudeE7: Long): CheckedCoordinateE7? {
			val latitude = CheckedLatitudeE7.fromE7OrNull(latitudeE7) ?: return null
			val longitude = CheckedLongitudeE7.fromE7OrNull(longitudeE7) ?: return null
			return CheckedCoordinateE7(
				latitude = latitude,
				longitude = if (latitude.value == GeoCoordinates.MIN_LATITUDE_E7.toInt() ||
					latitude.value == GeoCoordinates.MAX_LATITUDE_E7.toInt()
				) {
					CheckedLongitudeE7.requireE7(0)
				} else {
					longitude
				},
			)
		}

		fun fromDegreesOrNull(latitudeDegrees: Double, longitudeDegrees: Double): CheckedCoordinateE7? {
			val latitude = CheckedLatitudeE7.fromDegreesOrNull(latitudeDegrees) ?: return null
			val longitude = CheckedLongitudeE7.fromDegreesOrNull(longitudeDegrees) ?: return null
			return CheckedCoordinateE7(
				latitude = latitude,
				longitude = if (latitude.value == GeoCoordinates.MIN_LATITUDE_E7.toInt() ||
					latitude.value == GeoCoordinates.MAX_LATITUDE_E7.toInt()
				) {
					CheckedLongitudeE7.requireE7(0)
				} else {
					longitude
				},
			)
		}

		fun requireE7(latitudeE7: Long, longitudeE7: Long): CheckedCoordinateE7 =
			requireNotNull(fromE7OrNull(latitudeE7, longitudeE7)) {
				"Coordinate outside finite Earth range: ($latitudeE7, $longitudeE7)"
			}

		fun requireDegrees(latitudeDegrees: Double, longitudeDegrees: Double): CheckedCoordinateE7 =
			requireNotNull(fromDegreesOrNull(latitudeDegrees, longitudeDegrees)) {
				"Coordinate outside finite Earth range: ($latitudeDegrees, $longitudeDegrees)"
			}
	}
}

/** Overflow-safe cyclic longitude arithmetic. */
object CircularLongitude {
	const val WORLD_E7: Long = 3_600_000_000L
	const val HALF_WORLD_E7: Long = WORLD_E7 / 2L
	private const val WORLD_DEGREES: Double = 360.0
	private const val HALF_WORLD_DEGREES: Double = 180.0

	/** Normalizes any finite degree longitude to [-180°, 180°). */
	fun normalizeDegrees(longitudeDegrees: Double): Double {
		require(longitudeDegrees.isFinite()) { "Longitude must be finite" }
		val remainder = longitudeDegrees % WORLD_DEGREES
		val normalized = when {
			remainder < -HALF_WORLD_DEGREES -> remainder + WORLD_DEGREES
			remainder >= HALF_WORLD_DEGREES -> remainder - WORLD_DEGREES
			else -> remainder
		}
		return if (normalized == 0.0) 0.0 else normalized
	}

	/**
	 * Normalizes any [Long] E7 longitude to [-180°, 180°) without first adding
	 * half a world, which would overflow for Long.MIN_VALUE/MAX_VALUE.
	 */
	fun normalizeE7(longitudeE7: Long): Long {
		val remainder = floorMod(longitudeE7, WORLD_E7)
		return if (remainder >= HALF_WORLD_E7) remainder - WORLD_E7 else remainder
	}

	/** Eastward delta from [fromDegrees] to [toDegrees], in [0°, 360°). */
	fun positiveDeltaDegrees(fromDegrees: Double, toDegrees: Double): Double {
		val from = normalizeDegrees(fromDegrees)
		val to = normalizeDegrees(toDegrees)
		val delta = to - from
		val positive = when {
			delta < 0.0 -> delta + WORLD_DEGREES
			delta >= WORLD_DEGREES -> delta - WORLD_DEGREES
			else -> delta
		}
		return if (positive == 0.0) 0.0 else positive
	}

	/** Eastward delta from [fromE7] to [toE7], in [0, 3_600_000_000). */
	fun positiveDeltaE7(fromE7: Long, toE7: Long): Long {
		val from = normalizeE7(fromE7)
		val to = normalizeE7(toE7)
		return floorMod(to - from, WORLD_E7)
	}

	/**
	 * Shortest signed delta from [fromDegrees] to [toDegrees], in [-180°, 180°).
	 * The exact 180° tie is intentionally westward (-180°).
	 */
	fun shortestDeltaDegrees(fromDegrees: Double, toDegrees: Double): Double {
		val positive = positiveDeltaDegrees(fromDegrees, toDegrees)
		return if (positive >= HALF_WORLD_DEGREES) positive - WORLD_DEGREES else positive
	}

	/**
	 * Shortest signed delta from [fromE7] to [toE7], in [-180°, 180°). The
	 * exact half-world tie is -180°.
	 */
	fun shortestDeltaE7(fromE7: Long, toE7: Long): Long {
		val positive = positiveDeltaE7(fromE7, toE7)
		return if (positive >= HALF_WORLD_E7) positive - WORLD_E7 else positive
	}

	/** Normalizes an already-computed cyclic E7 delta to [-180°, 180°). */
	fun normalizeSignedDeltaE7(deltaE7: Long): Long =
		if (floorMod(deltaE7, WORLD_E7) >= HALF_WORLD_E7) {
			floorMod(deltaE7, WORLD_E7) - WORLD_E7
		} else {
			floorMod(deltaE7, WORLD_E7)
		}

	/** Interpolates the short arc between two finite degree longitudes. */
	fun interpolateShortestDegrees(startDegrees: Double, endDegrees: Double, t: Double): Double {
		require(t.isFinite() && t in 0.0..1.0) { "Interpolation fraction must be finite and in [0, 1]" }
		return normalizeDegrees(normalizeDegrees(startDegrees) + shortestDeltaDegrees(startDegrees, endDegrees) * t)
	}

	/**
	 * Interpolates a short E7 longitude arc and quantizes it with the same
	 * checked E7 quantizer used by all degree boundaries.
	 */
	fun interpolateShortestE7(startE7: Long, endE7: Long, t: Double): Long {
		val degrees = interpolateShortestDegrees(
			startE7.toDouble() / GeoCoordinates.E7_PER_DEGREE,
			endE7.toDouble() / GeoCoordinates.E7_PER_DEGREE,
			t,
		)
		return CheckedLongitudeE7.requireDegrees(degrees).value.toLong()
	}

	private fun floorMod(value: Long, modulus: Long): Long {
		val remainder = value % modulus
		return if (remainder < 0L) remainder + modulus else remainder
	}
}

/** Inclusive conventional longitude range, used only at SQL/grid boundaries. */
data class OrdinaryLongitudeRangeE7(val minE7: Int, val maxE7: Int) {
	init {
		require(minE7 <= maxE7) { "Ordinary longitude range must be ordered" }
		require(minE7.toLong() in GeoCoordinates.MIN_LONGITUDE_E7..GeoCoordinates.MAX_LONGITUDE_INPUT_E7) {
			"Longitude range start outside Earth range"
		}
		require(maxE7.toLong() in GeoCoordinates.MIN_LONGITUDE_E7..GeoCoordinates.MAX_LONGITUDE_INPUT_E7) {
			"Longitude range end outside Earth range"
		}
	}
}

/**
 * A connected longitude interval described by a canonical start and an
 * eastward span. Point and Full are separate states so neither is inferred
 * from an ambiguous ordered min/max pair.
 */
sealed class CircularLongitudeInterval {
	abstract val startE7: Long
	abstract val eastwardSpanE7: Long

	@ConsistentCopyVisibility
	data class Point internal constructor(override val startE7: Long) : CircularLongitudeInterval() {
		override val eastwardSpanE7: Long = 0L
	}

	@ConsistentCopyVisibility
	data class Arc internal constructor(
		override val startE7: Long,
		override val eastwardSpanE7: Long,
	) : CircularLongitudeInterval() {
		init {
			require(eastwardSpanE7 in 1L until CircularLongitude.WORLD_E7) {
				"Arc span must be in 1 until one world"
			}
		}
	}

	data object Full : CircularLongitudeInterval() {
		override val startE7: Long = GeoCoordinates.MIN_LONGITUDE_E7
		override val eastwardSpanE7: Long = CircularLongitude.WORLD_E7
	}

	val endE7: Long?
		get() = if (this === Full) null else CircularLongitude.normalizeE7(startE7 + eastwardSpanE7)

	fun contains(longitudeE7: Long): Boolean = when (this) {
		Full -> true
		is Point -> CircularLongitude.normalizeE7(longitudeE7) == startE7
		is Arc -> CircularLongitude.positiveDeltaE7(startE7, longitudeE7) <= eastwardSpanE7
	}

	fun intersects(other: CircularLongitudeInterval): Boolean = when {
		this === Full || other === Full -> true
		else -> contains(other.startE7) || other.contains(startE7)
	}

	/** Expands both directed ends by an E7 margin, becoming [Full] when needed. */
	fun expand(marginE7: Long): CircularLongitudeInterval {
		require(marginE7 >= 0L) { "Longitude expansion must be non-negative" }
		if (this === Full) return Full
		val remaining = CircularLongitude.WORLD_E7 - eastwardSpanE7
		if (marginE7 >= (remaining + 1L) / 2L) return Full
		val expandedStart = CircularLongitude.normalizeE7(startE7 - marginE7)
		val expandedSpan = eastwardSpanE7 + marginE7 + marginE7
		return fromStartAndSpan(expandedStart, expandedSpan)
	}

	/** Splits a circular interval into one or two ordinary inclusive ranges. */
	fun toOrdinaryRangesE7(): List<OrdinaryLongitudeRangeE7> = when (this) {
		Full -> listOf(
			OrdinaryLongitudeRangeE7(
				GeoCoordinates.MIN_LONGITUDE_E7.toInt(),
				GeoCoordinates.MAX_LONGITUDE_INPUT_E7.toInt() - 1,
			),
		)
		is Point -> listOf(OrdinaryLongitudeRangeE7(startE7.toInt(), startE7.toInt()))
		is Arc -> {
			val end = checkNotNull(endE7)
			if (startE7 <= end) {
				listOf(OrdinaryLongitudeRangeE7(startE7.toInt(), end.toInt()))
			} else {
				listOf(
					OrdinaryLongitudeRangeE7(startE7.toInt(), GeoCoordinates.MAX_LONGITUDE_INPUT_E7.toInt() - 1),
					OrdinaryLongitudeRangeE7(GeoCoordinates.MIN_LONGITUDE_E7.toInt(), end.toInt()),
				)
			}
		}
	}

	companion object {
		fun point(longitudeE7: Long): CircularLongitudeInterval =
			Point(CircularLongitude.normalizeE7(longitudeE7))

		fun full(): CircularLongitudeInterval = Full

		/** Builds the directed interval stored as an explicit `(start, end)` pair. */
		fun fromDirectedEndpoints(startE7: Long, endE7: Long): CircularLongitudeInterval =
			fromStartAndSpan(
				startE7 = CircularLongitude.normalizeE7(startE7),
				eastwardSpanE7 = CircularLongitude.positiveDeltaE7(startE7, endE7),
			)

		fun fromStartAndSpan(startE7: Long, eastwardSpanE7: Long): CircularLongitudeInterval {
			require(eastwardSpanE7 in 0L..CircularLongitude.WORLD_E7) {
				"Longitude interval span must be within one world"
			}
			val canonicalStart = CircularLongitude.normalizeE7(startE7)
			return when (eastwardSpanE7) {
				0L -> Point(canonicalStart)
				CircularLongitude.WORLD_E7 -> Full
				else -> Arc(canonicalStart, eastwardSpanE7)
			}
		}

		/**
		 * Covers every shortest-edge segment in a polyline, not merely its vertex
		 * set. A path that travels -170° -> 0° -> 170° therefore spans 340°.
		 */
		fun fromShortestEdgePolyline(longitudesE7: IntArray): CircularLongitudeInterval {
			require(longitudesE7.isNotEmpty()) { "A polyline needs at least one longitude" }
			var previous = CircularLongitude.normalizeE7(longitudesE7.first().toLong())
			var unwrapped = previous
			var min = unwrapped
			var max = unwrapped
			for (index in 1 until longitudesE7.size) {
				val current = CircularLongitude.normalizeE7(longitudesE7[index].toLong())
				unwrapped += CircularLongitude.shortestDeltaE7(previous, current)
				if (unwrapped < min) min = unwrapped
				if (unwrapped > max) max = unwrapped
				previous = current
			}
			val span = max - min
			return if (span >= CircularLongitude.WORLD_E7) Full else fromStartAndSpan(min, span)
		}
	}
}

/** Conservative finite-radius latitude/longitude coverage for a checked coordinate. */
data class ConservativeRadiusBounds(
	val minLatitudeE7: Int,
	val maxLatitudeE7: Int,
	val longitude: CircularLongitudeInterval,
) {
	init {
		require(minLatitudeE7 <= maxLatitudeE7) { "Latitude bounds must be ordered" }
		require(minLatitudeE7.toLong() in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7)
		require(maxLatitudeE7.toLong() in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7)
	}

	companion object {
		private const val RADIANS_TO_DEGREES: Double = 180.0 / PI
		// WGS-84 minimum meridional radius, reached at the equator.
		private const val WGS84_MIN_MERIDIONAL_RADIUS_METRES: Double = 6_335_439.3272928195

		/**
		 * Returns a conservative angular cap for a non-negative finite metre radius.
		 * Longitude becomes [CircularLongitudeInterval.Full] whenever the cap
		 * reaches a pole, rather than relying on a cosine floor.
		 */
		fun around(center: CheckedCoordinateE7, radiusMetres: Double): ConservativeRadiusBounds {
			require(radiusMetres.isFinite() && radiusMetres >= 0.0) {
				"Radius must be finite and non-negative"
			}
			val latitudeDeltaDegrees = radiusMetres / WGS84_MIN_MERIDIONAL_RADIUS_METRES * RADIANS_TO_DEGREES
			if (!latitudeDeltaDegrees.isFinite() || latitudeDeltaDegrees >= 180.0) {
				return ConservativeRadiusBounds(
					minLatitudeE7 = GeoCoordinates.MIN_LATITUDE_E7.toInt(),
					maxLatitudeE7 = GeoCoordinates.MAX_LATITUDE_E7.toInt(),
					longitude = CircularLongitudeInterval.Full,
				)
			}

			val latitudeDeltaE7 = ceil(latitudeDeltaDegrees * GeoCoordinates.E7_PER_DEGREE).toLong()
			val minLatitude = (center.latitudeE7.toLong() - latitudeDeltaE7)
				.coerceAtLeast(GeoCoordinates.MIN_LATITUDE_E7)
			val maxLatitude = (center.latitudeE7.toLong() + latitudeDeltaE7)
				.coerceAtMost(GeoCoordinates.MAX_LATITUDE_E7)
			if (minLatitude == GeoCoordinates.MIN_LATITUDE_E7 || maxLatitude == GeoCoordinates.MAX_LATITUDE_E7) {
				return ConservativeRadiusBounds(
					minLatitudeE7 = minLatitude.toInt(),
					maxLatitudeE7 = maxLatitude.toInt(),
					longitude = CircularLongitudeInterval.Full,
				)
			}

			// The same global minimum radius bounds the spherical central-angle
			// construction conservatively without a latitude-specific cosine floor.
			val centralAngle = radiusMetres / WGS84_MIN_MERIDIONAL_RADIUS_METRES
			val cosine = cos(center.latitudeDegrees * PI / 180.0)
			val longitude = if (
				!centralAngle.isFinite() || centralAngle >= PI / 2.0 ||
				sin(centralAngle) >= cosine
			) {
				CircularLongitudeInterval.Full
			} else {
				val longitudeDeltaDegrees = asin(sin(centralAngle) / cosine) * RADIANS_TO_DEGREES
				val longitudeDeltaE7 = ceil(longitudeDeltaDegrees * GeoCoordinates.E7_PER_DEGREE).toLong()
				CircularLongitudeInterval.point(center.longitudeE7.toLong()).expand(longitudeDeltaE7)
			}
			return ConservativeRadiusBounds(
				minLatitudeE7 = minLatitude.toInt(),
				maxLatitudeE7 = maxLatitude.toInt(),
				longitude = longitude,
			)
		}
	}
}
