package com.adsamcik.tracker.activity.receiver

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidence
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.activity.api.ingress.ActivityTransitionEvidence
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationCleanupStoreTest
import com.adsamcik.tracker.stats.api.DetectedActivityType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ActivityRegistrationCleanupStoreTest.HostAtomicFile::class])
class ActivityCallbackRetryStoreTest {
	private lateinit var directory: File
	private lateinit var store: ActivityCallbackRetryStore
	private var nowWallTimeMs = 1_000L
	private var nowElapsedRealtimeNanos = 1_000L
	private var currentClockDomainId = "boot-7"

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		directory = File(application.cacheDir, "activity-callback-retry-store-test")
		directory.deleteRecursively()
		store = newStore()
	}

	@After
	fun tearDown() {
		directory.deleteRecursively()
	}

	@Test
	fun `exact callback round trips and duplicate retain is idempotent`() {
		val batch = callbackBatch()

		val firstId = store.retain(batch)
		val secondId = store.retain(batch)

		secondId shouldBe firstId
		store.pendingIds() shouldBe listOf(firstId)
		store.load(firstId) shouldBe batch
	}

	@Test
	fun `durable retry never serializes or restores a live Start command fence`() {
		val batch = callbackBatch().copy(liveStartCommandGeneration = 7L)
		val id = store.retain(batch)
		store.retain(batch) shouldBe id
		val stored = requireNotNull(store.load(id))
		stored shouldBe batch.copy(liveStartCommandGeneration = null)
		stored.liveStartCommandGeneration shouldBe null
		stored.copy(startContext = ActivityIngressStartContext.DURABLE_REPLAY)
			.liveStartCommandGeneration shouldBe null
	}

	@Test
	fun `independent callback files let poison be removed without touching good retry`() {
		val poisonId = store.retain(callbackBatch(receivedWallTimeMs = 1_000L))
		val good = callbackBatch(receivedWallTimeMs = 2_000L)
		val goodId = store.retain(good)
		val poisonFile = requireNotNull(directory.listFiles()).single { poisonId in it.name }
		poisonFile.writeBytes(byteArrayOf(1, 2, 3, 4))

		val error = shouldThrow<ActivityCallbackRetryStoreException> { store.load(poisonId) }
		error.corrupt shouldBe true
		store.remove(poisonId)

		store.pendingIds() shouldBe listOf(goodId)
		store.load(goodId) shouldBe good
	}

	@Test
	fun `purge removes valid corrupt and atomic sidecar files`() {
		val id = store.retain(callbackBatch())
		store.recordGap(callbackBatch(), ActivityCallbackGapCode.HANDOFF_BUDGET_EXHAUSTED)
		File(directory, "activity-callback-$id.bin.bak").writeBytes(byteArrayOf(1))
		File(directory, "activity-callback-$id.bin.new").writeBytes(byteArrayOf(2))
		File(directory, "unreadable-poison").writeBytes(byteArrayOf(3))

		store.purge()

		directory.listFiles().orEmpty().toList() shouldBe emptyList()
	}

	@Test
	fun `interrupted AtomicFile new sidecar never claims callback retry ownership`() {
		directory.mkdirs()
		val orphan = File(
			directory,
			"activity-callback-${"0".repeat(64)}.bin.new",
		).also { it.writeBytes(byteArrayOf(1, 2, 3)) }

		store.pendingIds() shouldBe emptyList()
		orphan.exists() shouldBe false
	}

	@Test
	fun `many interrupted writes are removed before callback capacity is consumed`() {
		directory.mkdirs()
		repeat(12) { index ->
			File(
				directory,
				"activity-callback-${index.toString(16).padStart(64, '0')}.bin.new",
			).writeBytes(ByteArray(512) { index.toByte() })
		}
		val boundedStore = newStore(maxPendingCallbacks = 2, maxPendingBytes = 1_024L)

		val firstId = boundedStore.retain(callbackBatch(receivedWallTimeMs = 1_000L))
		val secondId = boundedStore.retain(callbackBatch(receivedWallTimeMs = 2_000L))
		val error = shouldThrow<ActivityCallbackRetryStoreException> {
			boundedStore.retain(callbackBatch(receivedWallTimeMs = 3_000L))
		}

		error.code shouldBe ActivityCallbackGapCode.RETRY_COUNT_BUDGET_EXHAUSTED
		boundedStore.pendingIds() shouldBe listOf(firstId, secondId).sorted()
		val files = directory.listFiles().orEmpty().toList()
		files.count { it.name.endsWith(".bin") } shouldBe 2
		files.count { it.name.endsWith(".new") } shouldBe 0
		(files.sumOf { it.length() } <= 1_024L) shouldBe true
	}

	@Test
	fun `undeletable interrupted write fails closed before another file is opened`() {
		directory.mkdirs()
		val orphan = File(
			directory,
			"activity-callback-${"0".repeat(64)}.bin.new",
		).also { it.writeBytes(ByteArray(512)) }
		val boundedStore = newStore(deleteOrphanFile = { false })
		val initialFiles = directory.listFiles().orEmpty().map { it.name }.sorted()

		repeat(2) { attempt ->
			val error = shouldThrow<ActivityCallbackRetryStoreException> {
				boundedStore.retain(callbackBatch(receivedWallTimeMs = 2_000L + attempt))
			}
			error.code shouldBe ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE
		}

		orphan.exists() shouldBe true
		directory.listFiles().orEmpty().map { it.name }.sorted() shouldBe initialFiles
	}

	@Test
	fun `orphan cleanup preserves AtomicFile backup recovery`() {
		val batch = callbackBatch()
		val id = store.retain(batch)
		val base = File(directory, "activity-callback-$id.bin")
		val backup = File(directory, "activity-callback-$id.bin.bak")
		base.renameTo(backup) shouldBe true
		File(directory, "activity-callback-$id.bin.new").writeBytes(byteArrayOf(1, 2, 3))

		store.pendingIds() shouldBe listOf(id)
		store.load(id) shouldBe batch
		base.exists() shouldBe true
		backup.exists() shouldBe false
		directory.listFiles().orEmpty().none { it.name.endsWith(".new") } shouldBe true
	}

	@Test
	fun `oversized callback is rejected before claiming durable ownership`() {
		val oversized = callbackBatch().copy(
			recognitions = List(1_025) { index ->
				ActivityRecognitionEvidence(
					DetectedActivityType.WALKING,
					90,
					100L + index,
				)
			},
			transitions = emptyList(),
		)

		shouldThrow<IllegalArgumentException> { store.retain(oversized) }
		store.pendingIds() shouldBe emptyList()
	}

	@Test
	fun `capacity rejects a new callback without disturbing retained retry authority`() {
		val boundedStore = newStore(maxPendingCallbacks = 1)
		val retainedId = boundedStore.retain(callbackBatch(receivedWallTimeMs = 1_000L))

		val error = shouldThrow<ActivityCallbackRetryStoreException> {
			boundedStore.retain(callbackBatch(receivedWallTimeMs = 2_000L))
		}

		error.corrupt shouldBe false
		error.code shouldBe ActivityCallbackGapCode.RETRY_COUNT_BUDGET_EXHAUSTED
		boundedStore.pendingIds() shouldBe listOf(retainedId)
		boundedStore.load(retainedId) shouldBe callbackBatch(receivedWallTimeMs = 1_000L)
	}

	@Test
	fun `byte budget rejects new callback without disturbing retained retry authority`() {
		val boundedStore = newStore(maxPendingBytes = 1L)

		val error = shouldThrow<ActivityCallbackRetryStoreException> {
			boundedStore.retain(callbackBatch())
		}

		error.code shouldBe ActivityCallbackGapCode.RETRY_BYTE_BUDGET_EXHAUSTED
		boundedStore.pendingIds() shouldBe emptyList()
	}

	@Test
	fun `wall clock rollback cannot extend operational inbox freshness`() {
		val id = store.retain(callbackBatch(receivedWallTimeMs = 50_000L))
		nowWallTimeMs = 1L
		nowElapsedRealtimeNanos = 501L

		store.load(id) shouldBe callbackBatch(receivedWallTimeMs = 50_000L)
	}

	@Test
	fun `elapsed reset and boot change terminally gap instead of reviving retry`() {
		val id = store.retain(callbackBatch(receivedElapsedRealtimeNanos = 500L))
		nowElapsedRealtimeNanos = 499L

		val expired = shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }
		expired.code shouldBe ActivityCallbackGapCode.RETRY_RECORD_EXPIRED
		expired.originalMetadata shouldBe ActivityCallbackRetryMetadata.from(callbackBatch())

		nowElapsedRealtimeNanos = 501L
		currentClockDomainId = "boot-8"
		val oldBoot = shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }
		oldBoot.code shouldBe ActivityCallbackGapCode.RETRY_CLOCK_DOMAIN_CHANGED
		oldBoot.originalMetadata shouldBe expired.originalMetadata
	}

	@Test
	fun `damaged payload keeps original checked scope but damaged header stays unknown`() {
		val batch = callbackBatch()
		val id = store.retain(batch)
		val file = File(directory, "activity-callback-$id.bin")
		val original = file.readBytes()
		file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })

		val payloadFailure = shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }
		payloadFailure.code shouldBe ActivityCallbackGapCode.RETRY_RECORD_CORRUPT
		payloadFailure.originalMetadata shouldBe ActivityCallbackRetryMetadata.from(batch)
		store.recordGap(id, payloadFailure.code, originalMetadata = payloadFailure.originalMetadata) shouldBe true
		store.gapReceipts().single().originalMetadata shouldBe payloadFailure.originalMetadata

		file.writeBytes(original.copyOf().also { it[14] = (it[14].toInt() xor 1).toByte() })
		shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }.originalMetadata shouldBe null
	}

	@Test
	fun `checked retry scope keeps every distinct original observation including middle timestamps`() {
		val batch = callbackBatch().let { original -> original.copy(transitions = listOf(200L, 150L, 100L, 150L)
			.map { original.transitions.single().copy(providerElapsedRealtimeNanos = it) }) }
		val id = store.retain(batch)
		currentClockDomainId = "new-boot"
		val metadata = shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }.originalMetadata
		metadata?.observedElapsedRealtimeNanos shouldBe listOf(100L, 150L, 200L)
	}

	@Test
	fun `legacy complete V2 record retains its ID and original scope when it later expires`() {
		val batch = callbackBatch()
		val id = store.retain(batch)
		val file = File(directory, "activity-callback-$id.bin")
		val legacy = DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
			input.readInt()
			input.readInt() shouldBe 3
			ActivityCallbackRetryMetadataCodec.read(input, id)
			input.readBytes()
		}
		file.writeBytes(legacy)
		store.load(id) shouldBe batch
		nowElapsedRealtimeNanos += 7L * 60L * 60L * 1_000_000_000L
		val failure = shouldThrow<ActivityCallbackRetryStoreException> { store.load(id) }
		failure.code shouldBe ActivityCallbackGapCode.RETRY_RECORD_EXPIRED
		failure.originalMetadata shouldBe ActivityCallbackRetryMetadata.from(batch)
	}

	@Test
	fun `legacy gap receipt remains readable without invented original registration metadata`() {
		val id = "0".repeat(64)
		directory.mkdirs()
		val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
			output.writeInt(0x41434750)
			output.writeInt(1)
			output.writeUTF(id)
			output.writeInt(ActivityCallbackGapCode.RETRY_RECORD_EXPIRED.wireCode)
			output.writeLong(500L)
			output.writeLong(7L)
			output.writeLong(11L)
			output.writeLong(1_000L)
		} }.toByteArray()
		File(directory, "activity-gap-$id-8.bin").writeBytes(bytes)

		val receipt = store.gapReceipts().single()
		receipt.registrationGeneration shouldBe 7L
		receipt.originalMetadata shouldBe null
	}

	@Test
	fun `corrupt existing gap receipt is repaired before it can replace retry ownership`() {
		val batch = callbackBatch()
		val id = store.retain(batch)
		val code = ActivityCallbackGapCode.RETRY_RECORD_EXPIRED
		store.recordGap(id, code, batch) shouldBe true
		val receiptFile = File(directory, "activity-gap-$id-${code.wireCode}.bin")
		receiptFile.writeBytes(byteArrayOf(1, 2, 3))

		store.recordGap(id, code, batch) shouldBe true
		val repaired = store.gapReceipts().single()
		repaired.originalMetadata shouldBe ActivityCallbackRetryMetadata.from(batch)
		nowWallTimeMs += 1_000L
		store.recordGap(id, code, batch) shouldBe true
		store.gapReceipts().single() shouldBe repaired
	}

	@Test
	fun `six hour bound is elapsed operational inbox freshness not product retention`() {
		val boundedStore = newStore(maxRetryAgeMs = 10L)
		val id = boundedStore.retain(callbackBatch(receivedElapsedRealtimeNanos = 500L))
		nowWallTimeMs = 1L
		nowElapsedRealtimeNanos = 500L + 11_000_000L

		shouldThrow<ActivityCallbackRetryStoreException> { boundedStore.load(id) }.code shouldBe
			ActivityCallbackGapCode.RETRY_RECORD_EXPIRED
	}

	@Test
	fun `v2 record uses stable wire codes and unsupported versions are explicit`() {
		val id = store.retain(callbackBatch())
		val file = requireNotNull(directory.listFiles()).single { id in it.name }
		val encoded = file.readBytes()
		String(encoded, Charsets.ISO_8859_1).contains("WALKING") shouldBe false
		String(encoded, Charsets.ISO_8859_1).contains("LIVE_PROVIDER_CALLBACK") shouldBe false
		encoded[4] = 0
		encoded[5] = 0
		encoded[6] = 0
		encoded[7] = 99
		val unsupportedId = MessageDigest.getInstance("SHA-256")
			.digest(encoded)
			.joinToString("") { byte -> "%02x".format(byte) }
		file.delete()
		File(directory, "activity-callback-$unsupportedId.bin").writeBytes(encoded)

		shouldThrow<ActivityCallbackRetryStoreException> {
			store.load(unsupportedId)
		}.code shouldBe ActivityCallbackGapCode.RETRY_RECORD_UNSUPPORTED_VERSION
	}

	@Test
	fun `gap receipts are explicit bounded and purged with collected data`() {
		val boundedStore = newStore(maxGapReceipts = 1)
		val first = callbackBatch(receivedWallTimeMs = 1_000L)
		val second = callbackBatch(receivedWallTimeMs = 2_000L)

		boundedStore.recordGap(first, ActivityCallbackGapCode.RETRY_STORAGE_UNAVAILABLE) shouldBe true
		nowWallTimeMs += 1L
		boundedStore.recordGap(second, ActivityCallbackGapCode.RETRY_RECORD_EXPIRED) shouldBe true
		boundedStore.gapReceipts().map { it.code } shouldBe
			listOf(ActivityCallbackGapCode.RETRY_RECORD_EXPIRED)

		boundedStore.purge()
		boundedStore.gapReceipts() shouldBe emptyList()
	}

	private fun newStore(
		maxPendingCallbacks: Int = 64,
		maxPendingBytes: Long = 512L * 1_024L,
		maxRetryAgeMs: Long = 6L * 60L * 60L * 1_000L,
		maxGapReceipts: Int = 64,
		deleteOrphanFile: (File) -> Boolean = File::delete,
	) = ActivityCallbackRetryStore(
		directory = directory,
		nowWallTimeMs = { nowWallTimeMs },
		currentClockDomainId = { currentClockDomainId },
		nowElapsedRealtimeNanos = { nowElapsedRealtimeNanos },
		maxPendingCallbacks = maxPendingCallbacks,
		maxPendingBytes = maxPendingBytes,
		maxRetryAgeMs = maxRetryAgeMs,
		maxGapReceipts = maxGapReceipts,
		deleteOrphanFile = deleteOrphanFile,
	)

	private fun callbackBatch(
		receivedWallTimeMs: Long = 1_000L,
		receivedElapsedRealtimeNanos: Long = 500L,
	) = ActivityRecognitionEvidenceBatch(
		receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
		receivedWallTimeMs = receivedWallTimeMs,
		registrationIdentity = ActivityRegistrationIdentity(
			sourceInstanceId = "activity-instance",
			registrationGeneration = 7L,
			collectedDataEpoch = 11L,
			clockDomainId = "boot-7",
			physicalConfigurationFingerprint = "config-hash",
		),
		startContext = ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK,
		automaticRecognitionEligible = true,
		automaticTransitions = setOf(
			ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER),
		),
		recognitions = listOf(
			ActivityRecognitionEvidence(DetectedActivityType.WALKING, 87, 100L),
		),
		transitions = listOf(
			ActivityTransitionEvidence(
				DetectedActivityType.WALKING,
				ActivityTransitionType.ENTER,
				200L,
			),
		),
	)
}
