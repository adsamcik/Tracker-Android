package com.adsamcik.tracker.shared.preferences.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.onboarding.DefaultOnboardingRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DefaultOnboardingRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: DefaultOnboardingRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use a unique file name or cleanup data store if possible, 
        // but for simple robolectric run, context isolation usually suffices per test method 
        // if not reusing the same app instance or if we mock the file setup.
        // However, DataStore is tricky with singletons. 
        // Given the constraints, we'll instantiate it directly.
        repository = DefaultOnboardingRepository(context, testDispatcher)
    }

    @Test
    fun `isCompleted returns false by default`() = testScope.runTest {
        repository.ensureInitialized()
        val completed = repository.isCompleted.first()
        assertFalse("Should be incomplete by default", completed)
    }

    @Test
    fun `markCompleted updates state`() = testScope.runTest {
        repository.ensureInitialized()
        repository.markCompleted()
        val completed = repository.isCompleted.first()
        assertTrue("Should be completed after marking", completed)
    }

    @Test
    fun `ensureMigrated migrates from legacy prefs`() = testScope.runTest {
        // Setup legacy prefs
        val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("completed", true)
            .putLong("completed_time", 123456789L)
            .commit()

        // Re-init repository to trigger migration check or just observe flow
        // Note: In the real app, dataStore singleton might persist, 
        // so we rely on the fact that we just created the repo in setUp 
        // and haven't collected from it yet in this test method.
        
        repository.ensureInitialized()
        val completed = repository.isCompleted.first()
        assertTrue("Should migrate legacy completion", completed)
        
        val state = repository.state.first()
        assertTrue("State should reflect migrated completion", state.completed)
    }
}
