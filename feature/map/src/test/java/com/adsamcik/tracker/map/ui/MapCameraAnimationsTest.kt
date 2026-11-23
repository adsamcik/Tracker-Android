package com.adsamcik.tracker.map.ui

import com.adsamcik.tracker.map.presentation.udf.CameraModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapCameraAnimationsTest {
    @Test
    fun `compass updates cannot cancel the first location center and zoom`() = runTest {
        var camera = CameraModel()
        val rotations = mutableListOf<Float>()
        val animations = MapCameraAnimations(backgroundScope, { true }) { bearing ->
            rotations += bearing
            camera = camera.copy(bearing = bearing)
        }

        animations.move {
            delay(500)
            camera = camera.copy(lat = 50.0, lng = 14.0, zoom = 16f)
        }
        // The store can emit a bearing immediately after centering, before its animation starts.
        repeat(100) { animations.rotate(it.toFloat()) }
        runCurrent()
        rotations shouldBe emptyList()
        advanceTimeBy(500)
        runCurrent()

        camera shouldBe CameraModel(lat = 50.0, lng = 14.0, zoom = 16f, bearing = 99f)
        rotations shouldBe listOf(99f)
    }

    @Test
    fun `a new positioning action replaces the previous animation`() = runTest {
        val completedMoves = mutableListOf<String>()
        val animations = MapCameraAnimations(backgroundScope, { false }) { }
        animations.move {
            delay(500)
            completedMoves += "old"
        }
        runCurrent()
        animations.move {
            delay(100)
            completedMoves += "new"
        }
        advanceTimeBy(500)
        runCurrent()

        completedMoves shouldBe listOf("new")
    }

    @Test
    fun `a newer fix preserves the requested zoom until centering finishes`() = runTest {
        var camera = CameraModel()
        val animations = MapCameraAnimations(backgroundScope, { false }) { }
        animations.center(16.0) { zoom ->
            delay(500)
            camera = camera.copy(lat = 50.0, lng = 14.0, zoom = requireNotNull(zoom).toFloat())
        }
        runCurrent()
        advanceTimeBy(100)
        animations.center(null) { zoom ->
            delay(500)
            camera = camera.copy(lat = 50.1, lng = 14.1, zoom = (zoom ?: camera.zoom.toDouble()).toFloat())
        }
        advanceTimeBy(500)
        runCurrent()
        camera shouldBe CameraModel(lat = 50.1, lng = 14.1, zoom = 16f)

        camera = camera.copy(zoom = 12f)
        animations.center(null) { zoom ->
            camera = camera.copy(lat = 50.2, zoom = (zoom ?: camera.zoom.toDouble()).toFloat())
        }
        runCurrent()
        camera.zoom shouldBe 12f
    }

    @Test
    fun `a delayed native rotation callback cannot block a new location center`() = runTest {
        val nativeFinished = CompletableDeferred<Unit>()
        var camera = CameraModel()
        val animations = MapCameraAnimations(backgroundScope, { true }) {
            withContext(NonCancellable) { nativeFinished.await() }
        }
        try {
            animations.rotate(90f)
            runCurrent()
            backgroundScope.launch {
                animations.center(16.0) { zoom ->
                    camera = camera.copy(lat = 50.0, lng = 14.0, zoom = requireNotNull(zoom).toFloat())
                }
            }
            runCurrent()

            camera shouldBe CameraModel(lat = 50.0, lng = 14.0, zoom = 16f)
            nativeFinished.isCompleted shouldBe false
        } finally {
            nativeFinished.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun `an old native callback cannot clear the replacement position or requested zoom`() = runTest {
        val firstFinished = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val rotations = mutableListOf<Float>()
        val animations = MapCameraAnimations(backgroundScope, { true }) { rotations += it }
        var secondZoom: Double? = null
        var finalZoom: Double? = null
        try {
            animations.center(16.0) { withContext(NonCancellable) { firstFinished.await() } }
            runCurrent()
            backgroundScope.launch {
                animations.center(null) { zoom ->
                    secondZoom = zoom
                    withContext(NonCancellable) { secondFinished.await() }
                }
            }
            runCurrent()
            secondZoom shouldBe 16.0

            firstFinished.complete(Unit)
            runCurrent()
            animations.rotate(75f)
            runCurrent()
            rotations shouldBe emptyList()

            backgroundScope.launch { animations.center(null) { finalZoom = it } }
            runCurrent()
            finalZoom shouldBe 16.0
            rotations shouldBe listOf(75f)
            secondFinished.isCompleted shouldBe false
        } finally {
            firstFinished.complete(Unit)
            secondFinished.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun `ending follow while centering does not apply a queued compass rotation`() = runTest {
        var following = true
        val rotations = mutableListOf<Float>()
        val animations = MapCameraAnimations(backgroundScope, { following }) { rotations += it }
        animations.move { delay(500) }
        runCurrent()
        animations.rotate(90f)
        following = false
        advanceTimeBy(500)
        runCurrent()

        rotations shouldBe emptyList()
    }
}
