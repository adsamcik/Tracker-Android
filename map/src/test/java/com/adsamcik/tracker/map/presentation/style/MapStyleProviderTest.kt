package com.adsamcik.tracker.map.presentation.style

import com.adsamcik.tracker.shared.utils.style.StyleData
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Ignore
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

@Ignore("Moved to androidTest; uses Android resources and MapStyleOptions")
class MapStyleProviderTest {

    private fun mockContext(): android.content.Context {
        val resources = mock<android.content.res.Resources>()
        whenever(resources.openRawResource(any())).thenAnswer {
            ByteArrayInputStream("{}".toByteArray())
        }
        val ctx = mock<android.content.Context>()
        whenever(ctx.resources).thenReturn(resources)
        return ctx
    }

    @Test
    fun default_loads_style_without_crash() {
        val ctx = mockContext()
        val style = MapStyleProvider.default(ctx)
        assertTrue(style.toString().isNotBlank())
        verify(ctx.resources, times(1)).openRawResource(any())
    }

    @Test
    fun fromStyleData_picks_some_variant_and_reads_once() {
        val ctx = mockContext()
        val bright = StyleData(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val brightStyle = MapStyleProvider.fromStyleData(ctx, bright)
        assertTrue(brightStyle.toString().isNotBlank())
        verify(ctx.resources, times(1)).openRawResource(any())
    }
}
