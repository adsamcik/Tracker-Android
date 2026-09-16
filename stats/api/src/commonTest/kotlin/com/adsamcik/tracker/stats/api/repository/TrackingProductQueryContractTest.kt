package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TrackingProductQueryContractTest {
	@Test
	fun `continuation remains bound to exact scope and evidence snapshot`() {
		val scope = TrackingProductQueryScope.WallRange(EpochMs(100L), EpochMs(200L))
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val continuation = TestContinuation(scope, snapshot)

		assertEquals(
			continuation,
			TrackingProductQueryRequest(scope, 10, continuation).continuation,
		)
		assertEquals(
			continuation,
			TrackingProductQueryResult.Page(scope, snapshot, emptyList(), continuation).next,
		)
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryRequest(
				TrackingProductQueryScope.WallRange(EpochMs(200L), EpochMs(300L)),
				10,
				continuation,
			)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryResult.Page(
				scope,
				TrackingProductReadSnapshot(3L, 8L),
				emptyList(),
				continuation,
			)
		}
	}

	@Test
	fun `product snapshot requires one isolated state for every source`() {
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val exact = productSnapshot(snapshot, noEvidenceSources())

		assertEquals(HistorySource.entries, exact.sources.map { it.source })
		assertFailsWith<IllegalArgumentException> {
			productSnapshot(snapshot, noEvidenceSources().dropLast(1))
		}
		assertFailsWith<IllegalArgumentException> {
			productSnapshot(
				snapshot,
				noEvidenceSources().toMutableList().also {
					it[HistorySource.CELL.ordinal] = it[HistorySource.WIFI.ordinal]
				},
			)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductSourceSnapshot(
				source = HistorySource.CELL,
				origin = sessionOrigin(),
				state = TrackingProductSourceState.QUALIFIED,
				actionAuthority = actionAuthority(
					selection = sessionSelection(HistorySource.WIFI, snapshot),
				),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			productSnapshot(
				snapshot,
				noEvidenceSources().toMutableList().also { sources ->
					sources[HistorySource.WIFI.ordinal] = TrackingProductSourceSnapshot(
						source = HistorySource.WIFI,
						origin = sessionOrigin(),
						state = TrackingProductSourceState.QUALIFIED,
						actionAuthority = actionAuthority(
							sessionSelection(
								HistorySource.WIFI,
								TrackingProductReadSnapshot(3L, 8L),
							),
						),
					)
				},
			)
		}
	}

	@Test
	fun `states retain provenance without inventing missing or unverifiable origin`() {
		val deleted = TrackingProductSourceSnapshot(
			source = HistorySource.STEPS,
			origin = TrackingProductOrigin.Imported(identity("imported")),
			state = TrackingProductSourceState.DELETED,
		)

		assertEquals(TrackingProductSourceState.DELETED, deleted.state)
		assertNull(deleted.actionAuthority)
		assertFailsWith<IllegalArgumentException> {
			TrackingProductSourceSnapshot(
				source = HistorySource.STEPS,
				origin = null,
				state = TrackingProductSourceState.DELETED,
			)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductSourceSnapshot(
				source = HistorySource.LOCATION,
				origin = sessionOrigin(),
				state = TrackingProductSourceState.UNVERIFIABLE,
			)
		}
	}

	@Test
	fun `selection stale boundaries remain distinct and ordered`() {
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val selection = TrackingProductSelectionAuthority(
			source = HistorySource.ACTIVITY,
			origin = TrackingProductOrigin.Imported(identity("imported")),
			selection = TestSelection("imported"),
			producerRevision = 11L,
			contentChecksum = checksum("content"),
			readSnapshot = snapshot,
		)

		assertEquals(
			TrackingProductSelectionStaleness.COLLECTED_DATA_EPOCH_CHANGED,
			selection.stalenessAgainst(
				TrackingProductReadSnapshot(4L, 7L),
				11L,
				checksum("content"),
			),
		)
		assertEquals(
			TrackingProductSelectionStaleness.SOURCE_EVIDENCE_REVISION_CHANGED,
			selection.stalenessAgainst(
				TrackingProductReadSnapshot(3L, 8L),
				11L,
				checksum("content"),
			),
		)
		assertEquals(
			TrackingProductSelectionStaleness.PRODUCER_REVISION_CHANGED,
			selection.stalenessAgainst(snapshot, 12L, checksum("content")),
		)
		assertEquals(
			TrackingProductSelectionStaleness.CONTENT_CHECKSUM_CHANGED,
			selection.stalenessAgainst(snapshot, 11L, checksum("changed")),
		)
		assertNull(selection.stalenessAgainst(snapshot, 11L, checksum("content")))
	}

	@Test
	fun `structural scope preserves producer stored-zone authority`() {
		val first = TrackingProductStructuralDay(10L, "Europe/Prague")
		val second = TrackingProductStructuralDay(11L, "UTC")
		val scope = TrackingProductQueryScope.StructuralDays(listOf(first, second))

		assertEquals(listOf("Europe/Prague", "UTC"), scope.days.map { it.storedZoneId })
		assertFailsWith<IllegalArgumentException> {
			TrackingProductStructuralDay(10L, "")
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductStructuralDay(-1L, "UTC")
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryScope.StructuralDays(listOf(second, first))
		}
	}

	private fun productSnapshot(
		readSnapshot: TrackingProductReadSnapshot,
		sources: List<TrackingProductSourceSnapshot>,
	) = TrackingProductSnapshot(
		identity = identity("product"),
		fromInclusive = EpochMs(100L),
		toExclusive = EpochMs(200L),
		structuralDays = emptySet(),
		structuralAuthority = TrackingProductStructuralAuthority.UNVERIFIABLE,
		readSnapshot = readSnapshot,
		sources = sources,
	)

	private fun noEvidenceSources(): List<TrackingProductSourceSnapshot> =
		HistorySource.entries.map { source ->
			TrackingProductSourceSnapshot(
				source = source,
				origin = null,
				state = TrackingProductSourceState.NO_EVIDENCE,
			)
		}

	private fun actionAuthority(
		selection: TrackingProductSelectionAuthority,
	) = TrackingProductActionAuthority(selection, setOf(TrackingProductAction.DETAIL))

	private data class TestContinuation(
		override val scope: TrackingProductQueryScope,
		override val readSnapshot: TrackingProductReadSnapshot,
	) : TrackingProductContinuation

	private fun sessionSelection(
		source: HistorySource,
		readSnapshot: TrackingProductReadSnapshot,
	) = TrackingProductSelectionAuthority(
		source = source,
		origin = sessionOrigin(),
		selection = TestSelection("session"),
		producerRevision = 11L,
		contentChecksum = checksum("session-content"),
		readSnapshot = readSnapshot,
	)

	private data class TestSelection(val value: String) : TrackingProductSelection

	private fun sessionOrigin() = TrackingProductOrigin.Session(identity("session"), 5L)

	private fun identity(seed: String) =
		TrackingProductIdentity("sha256:" + opaqueDigest(seed))

	private fun checksum(seed: String) =
		TrackingProductChecksum("sha256:" + opaqueDigest(seed))

	private fun opaqueDigest(seed: String): String {
		val hexadecimal = "0123456789abcdef"
		val encoded = seed.flatMap { character ->
			listOf(
				hexadecimal[(character.code ushr 4) and 0xf],
				hexadecimal[character.code and 0xf],
			)
		}.joinToString(separator = "")
		return encoded.padEnd(64, '0').take(64)
	}
}
