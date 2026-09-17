package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
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
		val revisionOne = receipt(1L, "instance-a")
		val revisionTwo = receipt(2L, "instance-a")
		dao.append(revisionOne, owner(revisionOne)) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.append(revisionTwo, owner(revisionTwo)) shouldBe StepsCountDomainAppendResult.INSERTED

		dao.receipt(revisionOne.receiptIdentity) shouldBe revisionOne
		dao.receipt(revisionTwo.receiptIdentity) shouldBe revisionTwo
		dao.latestOwner(OWNER_KIND, OWNER_IDENTITY) shouldBe owner(revisionTwo)
		val changedDomain = receipt(3L, "instance-b")
		dao.append(changedDomain, owner(changedDomain)) shouldBe
			StepsCountDomainAppendResult.IDENTITY_CONFLICT

		val retraction = owner(
			receipt = null,
			revision = 3L,
			operation = StepsCountDomainOwnerRevisionEntity.OPERATION_RETRACT,
		)
		dao.append(null, retraction) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.append(
			receipt(4L, "instance-a"),
			owner(receipt(4L, "instance-a")),
		) shouldBe StepsCountDomainAppendResult.TERMINALLY_RETRACTED
		dao.latestOwner(OWNER_KIND, OWNER_IDENTITY) shouldBe retraction
	}

	@Test
	fun `exact replay conflict revision gap and bounded lookup stay distinct`() = runTest {
		val first = receipt(1L, "instance-a")
		dao.append(first, owner(first)) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.append(first, owner(first)) shouldBe StepsCountDomainAppendResult.EXACT_REPLAY
		dao.append(
			first,
			owner(first).copy(linkedAtMs = 99L),
		) shouldBe StepsCountDomainAppendResult.IDENTITY_CONFLICT

		val third = receipt(3L, "instance-a")
		dao.append(third, owner(third)) shouldBe StepsCountDomainAppendResult.REVISION_GAP

		val secondOwner = opaque('b')
		val second = receipt(1L, "instance-a", secondOwner)
		dao.append(second, owner(second)) shouldBe StepsCountDomainAppendResult.INSERTED
		dao.owners(listOf(OWNER_IDENTITY, secondOwner), 1) shouldContainExactly
			listOf(owner(first))
	}

	@Test
	fun `receipt and owner survive database reopen`() = runTest {
		val receipt = receipt(1L, "instance-a")
		dao.append(receipt, owner(receipt)) shouldBe StepsCountDomainAppendResult.INSERTED
		database.close()

		database = openDatabase()
		dao = database.receipts()

		dao.receipt(receipt.receiptIdentity) shouldBe receipt
		dao.latestOwner(OWNER_KIND, OWNER_IDENTITY) shouldBe owner(receipt)
	}

	private fun openDatabase(): CountDomainTestDatabase =
		Room.databaseBuilder(context, CountDomainTestDatabase::class.java, DATABASE_NAME)
			.allowMainThreadQueries()
			.build()

	private fun receipt(
		revision: Long,
		sourceInstance: String,
		ownerIdentity: String = OWNER_IDENTITY,
	): StepsCountDomainReceiptEntity {
		val providerIdentity =
			StepsCountDomainReceiptIntegrity.nativeProviderDomainIdentity("provider")
		val sourceIdentity =
			StepsCountDomainReceiptIntegrity.nativeSourceInstanceIdentity(sourceInstance)
		val domainIdentity =
			StepsCountDomainReceiptIntegrity.nativeDomainIdentity("provider", sourceInstance)
		val effect = revision.toString().repeat(64).take(64)
		val scopeIdentity = opaque('9')
		val identity = StepsCountDomainReceiptIntegrity.receiptIdentity(
			domainIdentity,
			providerIdentity,
			sourceIdentity,
			OWNER_KIND,
			scopeIdentity,
			ownerIdentity,
			revision,
			1L,
			7L,
			revision,
			"a".repeat(64),
			StepsCountDomainReceiptEntity.COVERAGE_COVERED,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
		)
		return StepsCountDomainReceiptEntity(
			identity,
			domainIdentity,
			providerIdentity,
			sourceIdentity,
			OWNER_KIND,
			scopeIdentity,
			ownerIdentity,
			revision,
			1L,
			7L,
			revision,
			"a".repeat(64),
			StepsCountDomainReceiptEntity.COVERAGE_COVERED,
			1,
			StepsCountDomainReceiptEntity.CURRENT_COUNT_DOMAIN_VERSION,
			effect,
		)
	}

	private fun owner(
		receipt: StepsCountDomainReceiptEntity?,
		revision: Long = requireNotNull(receipt).ownerRevision,
		operation: String = StepsCountDomainOwnerRevisionEntity.OPERATION_BIND,
	) = StepsCountDomainOwnerRevisionEntity(
		ownerKind = OWNER_KIND,
		scopeIdentity = opaque('9'),
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
	],
	version = 1,
	exportSchema = false,
)
internal abstract class CountDomainTestDatabase : RoomDatabase() {
	abstract fun receipts(): StepsCountDomainReceiptDao
}
