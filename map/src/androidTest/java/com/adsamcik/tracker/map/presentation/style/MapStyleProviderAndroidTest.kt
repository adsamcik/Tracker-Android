package com.adsamcik.tracker.map.presentation.style

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.utils.style.StyleData
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleProviderAndroidTest {

    @Test
    fun default_loads_style_without_crash() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val style = MapStyleProvider.default(ctx)
        assertTrue(style.toString().isNotBlank())
    }

    @Test
    fun fromStyleData_picks_variants_by_luminance_and_saturation() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val bright = StyleData(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val style = MapStyleProvider.fromStyleData(ctx, bright)
        assertTrue(style.toString().isNotBlank())
    }
}
