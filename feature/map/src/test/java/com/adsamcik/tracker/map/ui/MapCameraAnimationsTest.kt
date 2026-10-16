package com.adsamcik.tracker.map.ui

import com.adsamcik.tracker.map.presentation.udf.CameraModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
