package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainCompletenessMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainSchemaMarkerEntity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsCountDomainReceiptDaoTest {
	private lateinit var context: Application
	private lateinit var database: CountDomainTestDatabase
	private lateinit var dao: StepsCountDomainReceiptDao

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(DATABASE_NAME)
		database = openDatabase()
		dao = database.receipts()
	}

	@After
	fun tearDown() {
		database.close()
		context.deleteDatabase(DATABASE_NAME)
	}

	@Test
	fun `corrections retain their original domains and terminal retraction cannot resurrect`() = runTest {
		val revisionOne = receipt(1L, 'a')
		val revisionTwo = receipt(2L, 'a')
		dao.append(revisionOne, owner(revisionOne)) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.append(revisionTwo, owner(revisionTwo)) shouldBe StepsCountDomainAppendResult.INSERTED

		dao.receipt(revisionOne.receiptIdentity) shouldBe revisionOne
		dao.receipt(revisionTwo.receiptIdentity) shouldBe revisionTwo
		dao.latestOwner(OWNER_KIND, OWNER_IDENTITY) shouldBe owner(revisionTwo)
		val changedDomain = receipt(3L, 'b')
		dao.append(changedDomain, owner(changedDomain)) shouldBe
			StepsCountDomainAppendResult.IDENTITY_CONFLICT

		val retraction = owner(
			receipt = null,
			revision = 3L,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
		)
		dao.append(null, retraction) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.append(
			receipt(4L, 'a'),
			owner(receipt(4L, 'a')),
		) shouldBe StepsCountDomainAppendResult.TERMINAL_OWNER
		dao.latestOwner(OWNER_KIND, OWNER_IDENTITY) shouldBe retraction
	}

	@Test
	fun `exact replay conflict revision gap and bounded lookup stay distinct`() = runTest {
		val first = receipt(1L, 'a')
		dao.append(first, owner(first)) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.append(first, owner(first)) shouldBe StepsCountDomainAppendResult.EXACT_REPLAY
		dao.append(
			first,
			owner(first).copy(linkedAtMs = 99L),
		) shouldBe StepsCountDomainAppendResult.IDENTITY_CONFLICT

		val third = receipt(3L, 'a')
		dao.append(third, owner(third)) shouldBe StepsCountDomainAppendResult.REVISION_GAP

		val secondOwner = opaque('b')
		val second = receipt(1L, 'a', secondOwner)
		dao.append(second, owner(second)) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.owners(listOf(OWNER_IDENTITY, secondOwner), 1) shouldContainExactly
			listOf(owner(first))
	}

	@Test
	fun `receipt and owner survive database reopen`() = runTest {
		val receipt = receipt(1L, 'a')
		dao.append(receipt, owner(receipt)) shouldBe StepsCountDomainAppendResult.INSERTED
		database.close()

		database = openDatabase()
		dao = database.receipts()

		dao.receipt(receipt.receiptIdentity) shouldBe receipt
		dao.latestOwner(OWNER_KIND, OWNER_IDENTITY) shouldBe owner(receipt)
	}

	@Test
	fun `terminal unproven cannot upgrade and receipt effect mismatch inserts nothing`() = runTest {
		val unprovenOwnerIdentity = opaque('c')
		val unproven = StepsCountDomainOwnerRevisionEntity(
			ownerKind = OWNER_KIND,
			scopeIdentity = opaque('9'),
			ownerIdentity = unprovenOwnerIdentity,
			ownerRevision = 1L,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN,
			receiptIdentity = null,
			ownerEffectChecksum = "c".repeat(64),
			linkedAtMs = 1L,
		)
		dao.append(null, unproven) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.append(null, unproven) shouldBe StepsCountDomainAppendResult.EXACT_REPLAY
		val later = receipt(2L, 'a', unprovenOwnerIdentity)
		dao.append(later, owner(later)) shouldBe
			StepsCountDomainAppendResult.TERMINAL_OWNER

		val mismatchedOwnerIdentity = opaque('d')
		val receipt = receipt(1L, 'a', mismatchedOwnerIdentity)
		dao.append(
			receipt,
			owner(receipt).copy(ownerEffectChecksum = "f".repeat(64)),
		) shouldBe StepsCountDomainAppendResult.IDENTITY_CONFLICT
		dao.receipt(receipt.receiptIdentity) shouldBe null
	}

	@Test
	fun `Ambient unproven advances contiguously but never upgrades and retraction is terminal`() =
		runTest {
			val identity = opaque('7')
			fun unproven(revision: Long, effect: Char) =
				StepsCountDomainOwnerRevisionEntity(
					ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
					scopeIdentity = opaque('8'),
					ownerIdentity = identity,
					ownerRevision = revision,
					operation = StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN,
					receiptIdentity = null,
					ownerEffectChecksum = effect.toString().repeat(64),
					linkedAtMs = revision,
				)
			val first = unproven(1L, 'a')
			val second = unproven(2L, 'b')
			dao.append(null, first) shouldBe StepsCountDomainAppendResult.INSERTED
			dao.append(null, second) shouldBe StepsCountDomainAppendResult.INSERTED
			dao.append(null, second.copy(ownerEffectChecksum = "c".repeat(64))) shouldBe
				StepsCountDomainAppendResult.IDENTITY_CONFLICT

			val attemptedBinding = receipt(
				3L,
				'd',
				identity,
				StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
				opaque('8'),
			)
			dao.append(
				attemptedBinding,
				owner(attemptedBinding),
			) shouldBe StepsCountDomainAppendResult.TERMINAL_OWNER

			val retraction = unproven(3L, 'e').copy(
				operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
			)
			dao.append(null, retraction) shouldBe StepsCountDomainAppendResult.INSERTED
			dao.append(null, retraction) shouldBe StepsCountDomainAppendResult.EXACT_REPLAY
			dao.append(null, unproven(4L, 'f')) shouldBe
				StepsCountDomainAppendResult.TERMINAL_OWNER
		}

	private fun openDatabase(): CountDomainTestDatabase =
		Room.databaseBuilder(context, CountDomainTestDatabase::class.java, DATABASE_NAME)
			.allowMainThreadQueries()
			.build()

	private fun receipt(
		revision: Long,
		tokenDigit: Char,
		ownerIdentity: String = OWNER_IDENTITY,
		ownerKind: String = OWNER_KIND,
		scopeIdentity: String = opaque('9'),
	): StepsCountDomainReceiptEntity {
		val domainIdentity = StepsCountDomainReceiptIntegrity.counterDomainIdentity(
			StepsCounterDomainToken.opaque(opaque(tokenDigit)),
		)
		val effect = revision.toString().repeat(64).take(64)
		val coverageKind =
			if (ownerKind == StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT) {
				StepsCountDomainReceiptEntity.COVERAGE_AMBIENT_AGGREGATE
			} else {
				StepsCountDomainReceiptEntity.COVERAGE_COVERED
			}
		val identity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity,
			ownerKind,
			scopeIdentity,
			ownerIdentity,
			revision,
			1L,
			7L,
			revision,
			"a".repeat(64),
			coverageKind,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
			null,
		)
		return StepsCountDomainReceiptEntity(
			identity,
			domainIdentity,
			ownerKind,
			scopeIdentity,
			ownerIdentity,
			revision,
			1L,
			7L,
			revision,
			"a".repeat(64),
			coverageKind,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
			null,
		)
	}

	private fun owner(
		receipt: StepsCountDomainReceiptEntity?,
		revision: Long = requireNotNull(receipt).ownerRevision,
		operation: String = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
	) = StepsCountDomainOwnerRevisionEntity(
		ownerKind = receipt?.ownerKind ?: OWNER_KIND,
		scopeIdentity = receipt?.scopeIdentity ?: opaque('9'),
		ownerIdentity = receipt?.ownerIdentity ?: OWNER_IDENTITY,
		ownerRevision = revision,
		operation = operation,
		receiptIdentity = receipt?.receiptIdentity,
		ownerEffectChecksum = receipt?.effectChecksum ?: "e".repeat(64),
		linkedAtMs = revision,
	)

	private fun opaque(digit: Char) = "sha256:${digit.toString().repeat(64)}"

	private companion object {
		const val DATABASE_NAME = "steps-count-domain-receipt-test"
		const val OWNER_KIND = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT
		val OWNER_IDENTITY = "sha256:${"1".repeat(64)}"
	}
}

@Database(
	entities = [
		StepsCountDomainReceiptEntity::class,
		StepsCountDomainOwnerRevisionEntity::class,
		StepsCountDomainCompletenessMarkerEntity::class,
		StepsCountDomainSchemaMarkerEntity::class,
	],
	version = 1,
	exportSchema = false,
)
internal abstract class CountDomainTestDatabase : RoomDatabase() {
	abstract fun receipts(): StepsCountDomainReceiptDao
}
