package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackingProductQueryContractTest {
	@Test
	fun `trusted capability reconstruction has stable equality and rejects foreign issuer`() {
		val firstIssuer = TrackingProductTrustedCapabilities.issuer("wifi-producer")
		val reconstructedIssuer = TrackingProductTrustedCapabilities.issuer("wifi-producer")
		val foreignIssuer = TrackingProductTrustedCapabilities.issuer("cell-producer")
		val request = TrackingProductQueryRequest(wall(100L, 200L), 10)
		val snapshot = TrackingProductReadSnapshot(3L, 7L)

		val firstIdentity = identity("product")
		val reconstructedIdentity = identity("product")
		val firstChecksum = checksum("content")
		val reconstructedChecksum = checksum("content")
		val firstSelection =
			TrackingProductTrustedCapabilities.selection(firstIssuer, "opaque-selection")
		val reconstructedSelection =
			TrackingProductTrustedCapabilities.selection(
				reconstructedIssuer,
				"opaque-selection",
			)
		val firstContinuation = TrackingProductTrustedCapabilities.continuation(
			firstIssuer,
			"opaque-page",
			request.identity,
			snapshot,
		)
		val reconstructedContinuation = TrackingProductTrustedCapabilities.continuation(
			reconstructedIssuer,
			"opaque-page",
			request.identity,
			snapshot,
		)

		assertEquals(firstIdentity, reconstructedIdentity)
		assertEquals(firstIdentity.hashCode(), reconstructedIdentity.hashCode())
		assertEquals(firstChecksum, reconstructedChecksum)
		assertEquals(firstChecksum.hashCode(), reconstructedChecksum.hashCode())
		assertEquals(firstSelection, reconstructedSelection)
		assertEquals(firstSelection.hashCode(), reconstructedSelection.hashCode())
		assertEquals(firstContinuation, reconstructedContinuation)
		assertEquals(firstContinuation.hashCode(), reconstructedContinuation.hashCode())
		assertTrue(firstContinuation.isIssuedBy(reconstructedIssuer))
		assertFalse(firstContinuation.isIssuedBy(foreignIssuer))

		val authority = selectionAuthority(
			issuer = firstIssuer,
			productIdentity = firstIdentity,
			selection = firstSelection,
			readSnapshot = snapshot,
			scope = authorityScope(100L, 200L),
		)
		val reconstructedAuthority = selectionAuthority(
			issuer = reconstructedIssuer,
			productIdentity = reconstructedIdentity,
			selection = reconstructedSelection,
			readSnapshot = snapshot,
			scope = authorityScope(100L, 200L),
		)
		assertTrue(authority.isIssuedBy(reconstructedIssuer))
		assertFalse(authority.isIssuedBy(foreignIssuer))
		assertEquals(authority, reconstructedAuthority)
		assertEquals(authority.hashCode(), reconstructedAuthority.hashCode())
		val action = actionAuthority(
			firstIssuer,
			authority,
			setOf(TrackingProductAction.EXPORT, TrackingProductAction.DELETE),
		)
		val reconstructedAction = actionAuthority(
			reconstructedIssuer,
			reconstructedAuthority,
			setOf(TrackingProductAction.DELETE, TrackingProductAction.EXPORT),
		)
		assertEquals(action, reconstructedAction)
		assertEquals(action.hashCode(), reconstructedAction.hashCode())
		assertTrue(action.isIssuedBy(reconstructedIssuer))
		assertFalse(action.isIssuedBy(foreignIssuer))
		assertEquals(
			TrackingProductActionRejectionReason.FOREIGN_CAPABILITY,
			action.producerRejection(foreignIssuer),
		)
		assertNull(action.producerRejection(reconstructedIssuer))
	}

	@Test
	fun `continuation remains bound to exact scope limit and evidence snapshot`() {
		val issuer = TrackingProductTrustedCapabilities.issuer("history-producer")
		val first = TrackingProductQueryRequest(wall(100L, 200L), 10)
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val continuation = TrackingProductTrustedCapabilities.continuation(
			issuer,
			"opaque-page",
			first.identity,
			snapshot,
		)

		assertEquals(
			continuation,
			TrackingProductQueryRequest(first.scope, first.limit, continuation).continuation,
		)
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryRequest(first.scope, 9, continuation)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryRequest(wall(200L, 300L), first.limit, continuation)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryResult.Page(
				scope = first.scope,
				limit = first.limit,
				requestIdentity = first.identity,
				readSnapshot = TrackingProductReadSnapshot(3L, 8L),
				entries = emptyList(),
				next = continuation,
			)
		}
	}

	@Test
	fun `page rejects over-limit and out-of-scope products`() {
		val request = TrackingProductQueryRequest(wall(100L, 200L), 1)
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val inside = productSnapshot(
			identity = identity("inside"),
			scope = authorityScope(150L, 175L),
			readSnapshot = snapshot,
		)
		val outside = productSnapshot(
			identity = identity("outside"),
			scope = authorityScope(250L, 275L),
			readSnapshot = snapshot,
		)

		assertEquals(
			listOf(inside),
			TrackingProductQueryResult.Page(
				request.scope,
				request.limit,
				request.identity,
				snapshot,
				listOf(inside),
				null,
			).entries,
		)
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryResult.Page(
				request.scope,
				request.limit,
				request.identity,
				snapshot,
				listOf(inside, inside.copyWith(identity("second"))),
				null,
			)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryResult.Page(
				request.scope,
				request.limit,
				request.identity,
				snapshot,
				listOf(outside),
				null,
			)
		}
	}

	@Test
	fun `structural page requires authenticated membership`() {
		val day = TrackingProductStructuralDay(10L, "Europe/Prague")
		val request = TrackingProductQueryRequest(
			TrackingProductQueryScope.StructuralDays(listOf(day)),
			10,
		)
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val unverifiable = productSnapshot(
			identity = identity("unverifiable"),
			scope = TrackingProductAuthorityScope(null, setOf(day)),
			readSnapshot = snapshot,
			structuralVerification = TrackingProductStructuralVerification.UNVERIFIABLE,
		)
		val exact = productSnapshot(
			identity = identity("exact"),
			scope = TrackingProductAuthorityScope(null, setOf(day)),
			readSnapshot = snapshot,
			structuralVerification = TrackingProductStructuralVerification.EXACT,
		)

		assertFailsWith<IllegalArgumentException> {
			TrackingProductQueryResult.Page(
				request.scope,
				request.limit,
				request.identity,
				snapshot,
				listOf(unverifiable),
				null,
			)
		}
		assertEquals(
			listOf(exact),
			TrackingProductQueryResult.Page(
				request.scope,
				request.limit,
				request.identity,
				snapshot,
				listOf(exact),
				null,
			).entries,
		)
	}

	@Test
	fun `selection remains bound to exact product identity scope and snapshot`() {
		val issuer = TrackingProductTrustedCapabilities.issuer("activity-producer")
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val scope = authorityScope(100L, 200L)
		val action = actionAuthority(
			issuer,
			selectionAuthority(
				issuer = issuer,
				productIdentity = identity("selected"),
				selection = TrackingProductTrustedCapabilities.selection(
					issuer,
					"activity-selection",
				),
				readSnapshot = snapshot,
				scope = scope,
			),
		)
		val sources = noEvidenceSources().toMutableList().also {
			it[HistorySource.ACTIVITY.ordinal] = TrackingProductSourceSnapshot(
				source = HistorySource.ACTIVITY,
				verificationState = TrackingProductVerificationState.UNVERIFIABLE,
				origin = action.selection.origin,
				actionAuthority = action,
			)
		}

		assertFailsWith<IllegalArgumentException> {
			TrackingProductSnapshot(
				identity = identity("different"),
				authorityScope = scope,
				structuralVerification =
					TrackingProductStructuralVerification.UNVERIFIABLE,
				readSnapshot = snapshot,
				sources = sources,
			)
		}
		assertFailsWith<IllegalArgumentException> {
			TrackingProductSnapshot(
				identity = action.selection.productIdentity,
				authorityScope = authorityScope(100L, 201L),
				structuralVerification =
					TrackingProductStructuralVerification.UNVERIFIABLE,
				readSnapshot = snapshot,
				sources = sources,
			)
		}
	}

	@Test
	fun `known imported origin and action survive unverifiable value shell`() {
		val issuer = TrackingProductTrustedCapabilities.issuer("pressure-producer")
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val productIdentity = identity("pressure")
		val origin = TrackingProductTrustedCapabilities.importedOrigin(identity("archive"))
		val selection = TrackingProductTrustedCapabilities.selection(issuer, "pressure-import")
		val authority = TrackingProductTrustedCapabilities.selectionAuthority(
			issuer = issuer,
			source = HistorySource.PRESSURE,
			productIdentity = productIdentity,
			origin = origin,
			authorityScope = authorityScope(100L, 200L),
			selection = selection,
			producerRevision = 4L,
			contentChecksum = checksum("pressure"),
			readSnapshot = snapshot,
		)
		val action = actionAuthority(
			issuer,
			authority,
			setOf(TrackingProductAction.EXPORT, TrackingProductAction.DELETE),
		)
		val shell = TrackingProductSourceSnapshot(
			source = HistorySource.PRESSURE,
			verificationState = TrackingProductVerificationState.UNVERIFIABLE,
			origin = origin,
			actionAuthority = action,
		)

		assertEquals(TrackingProductOriginKind.IMPORTED, shell.origin?.kind)
		assertEquals(
			setOf(TrackingProductAction.EXPORT, TrackingProductAction.DELETE),
			shell.actionAuthority?.actions,
		)
	}

	@Test
	fun `collection inputs and exposed views cannot mutate snapshots`() {
		val dayInput = mutableListOf(
			TrackingProductStructuralDay(10L, "UTC"),
			TrackingProductStructuralDay(11L, "UTC"),
		)
		val dayScope = TrackingProductQueryScope.StructuralDays(dayInput)
		dayInput += TrackingProductStructuralDay(12L, "UTC")
		assertEquals(2, dayScope.days.size)
		(dayScope.days as MutableList<*>).clear()
		assertEquals(2, dayScope.days.size)

		val issuer = TrackingProductTrustedCapabilities.issuer("wifi-producer")
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val productIdentity = identity("wifi")
		val scopeDays = mutableSetOf(
			TrackingProductStructuralDay(10L, "UTC"),
			TrackingProductStructuralDay(11L, "UTC"),
		)
		val scope = TrackingProductAuthorityScope(wall(100L, 200L), scopeDays)
		scopeDays.clear()
		assertEquals(2, scope.structuralDays.size)
		(scope.structuralDays as MutableSet<*>).clear()
		assertEquals(2, scope.structuralDays.size)

		val selection = selectionAuthority(
			issuer = issuer,
			productIdentity = productIdentity,
			selection = TrackingProductTrustedCapabilities.selection(issuer, "wifi-selection"),
			readSnapshot = snapshot,
			scope = scope,
		)
		val actionInput = mutableSetOf(
			TrackingProductAction.DETAIL,
			TrackingProductAction.DELETE,
		)
		val action = actionAuthority(issuer, selection, actionInput)
		actionInput.clear()
		assertEquals(2, action.actions.size)
		(action.actions as MutableSet<*>).clear()
		assertEquals(2, action.actions.size)

		val sourceInput = noEvidenceSources().toMutableList()
		val product = TrackingProductSnapshot(
			productIdentity,
			scope,
			TrackingProductStructuralVerification.EXACT,
			snapshot,
			sourceInput,
		)
		sourceInput.clear()
		assertEquals(HistorySource.entries.size, product.sources.size)
		(product.sources as MutableList<*>).clear()
		assertEquals(HistorySource.entries.size, product.sources.size)

		val secondProduct = product.copyWith(identity("wifi-second"))
		val pageInput = mutableListOf(product, secondProduct)
		val request = TrackingProductQueryRequest(wall(100L, 200L), 10)
		val page = TrackingProductQueryResult.Page(
			request.scope,
			request.limit,
			request.identity,
			snapshot,
			pageInput,
			null,
		)
		pageInput.clear()
		assertEquals(2, page.entries.size)
		(page.entries as MutableList<*>).clear()
		assertEquals(2, page.entries.size)
	}

	@Test
	fun `selection stale boundaries remain distinct and ordered`() {
		val issuer = TrackingProductTrustedCapabilities.issuer("activity-producer")
		val snapshot = TrackingProductReadSnapshot(3L, 7L)
		val selection = selectionAuthority(
			issuer = issuer,
			productIdentity = identity("activity"),
			selection = TrackingProductTrustedCapabilities.selection(issuer, "activity"),
			readSnapshot = snapshot,
			scope = authorityScope(100L, 200L),
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

	private fun productSnapshot(
		identity: TrackingProductIdentity,
		scope: TrackingProductAuthorityScope,
		readSnapshot: TrackingProductReadSnapshot,
		structuralVerification: TrackingProductStructuralVerification =
			TrackingProductStructuralVerification.UNVERIFIABLE,
	): TrackingProductSnapshot =
		TrackingProductSnapshot(
			identity,
			scope,
			structuralVerification,
			readSnapshot,
			noEvidenceSources(),
		)

	private fun TrackingProductSnapshot.copyWith(
		identity: TrackingProductIdentity,
	): TrackingProductSnapshot =
		TrackingProductSnapshot(
			identity,
			authorityScope,
			structuralVerification,
			readSnapshot,
			sources,
		)

	private fun noEvidenceSources(): List<TrackingProductSourceSnapshot> =
		HistorySource.entries.map { source ->
			TrackingProductSourceSnapshot(
				source = source,
				verificationState = TrackingProductVerificationState.NO_EVIDENCE,
				origin = null,
			)
		}

	private fun selectionAuthority(
		issuer: TrackingProductCapabilityIssuer,
		productIdentity: TrackingProductIdentity,
		selection: TrackingProductSelection,
		readSnapshot: TrackingProductReadSnapshot,
		scope: TrackingProductAuthorityScope,
	): TrackingProductSelectionAuthority =
		TrackingProductTrustedCapabilities.selectionAuthority(
			issuer = issuer,
			source = HistorySource.ACTIVITY,
			productIdentity = productIdentity,
			origin = TrackingProductTrustedCapabilities.importedOrigin(identity("imported")),
			authorityScope = scope,
			selection = selection,
			producerRevision = 11L,
			contentChecksum = checksum("content"),
			readSnapshot = readSnapshot,
		)

	private fun actionAuthority(
		issuer: TrackingProductCapabilityIssuer,
		selection: TrackingProductSelectionAuthority,
		actions: Set<TrackingProductAction> = setOf(TrackingProductAction.DETAIL),
	): TrackingProductActionAuthority =
		TrackingProductTrustedCapabilities.actionAuthority(issuer, selection, actions)

	private fun authorityScope(
		from: Long,
		to: Long,
	): TrackingProductAuthorityScope =
		TrackingProductAuthorityScope(wall(from, to), emptySet())

	private fun wall(from: Long, to: Long) =
		TrackingProductQueryScope.WallRange(EpochMs(from), EpochMs(to))

	private fun identity(seed: String): TrackingProductIdentity =
		TrackingProductTrustedCapabilities.identity("sha256:" + opaqueDigest(seed))

	private fun checksum(seed: String): TrackingProductChecksum =
		TrackingProductTrustedCapabilities.checksum("sha256:" + opaqueDigest(seed))

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
