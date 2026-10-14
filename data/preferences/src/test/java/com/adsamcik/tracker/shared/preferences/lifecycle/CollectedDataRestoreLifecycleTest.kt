package com.adsamcik.tracker.shared.preferences.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectedDataRestoreLifecycleTest {
    @Test
    fun `confirmed restore reserves a newer epoch once and imports the backed up retention floor`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        resetCollectedDataLifecycleForTests(context)
        try {
            val store = DefaultCollectedDataLifecycleStore(context)
            store.beginFullDeletion(10_000L)
            val restored = store.beginDataRestore("confirmed-backup", 50L, 1_000L)
            restored shouldBe CollectedDataLifecycleSnapshot(50L, 1_000L)
            restored.accepts(1L, 20_000L) shouldBe false
            restored.accepts(50L, 20_000L) shouldBe true
            store.beginDataRestore("confirmed-backup", 50L, 1_000L) shouldBe restored
            shouldThrow<IllegalStateException> { store.beginDataRestore("another-backup", 49L, null) }
            store.snapshot() shouldBe restored
        } finally { resetCollectedDataLifecycleForTests(context) }
    }
}
