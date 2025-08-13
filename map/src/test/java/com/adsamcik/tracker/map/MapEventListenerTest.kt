package com.adsamcik.tracker.map

import com.google.android.gms.maps.GoogleMap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MapEventListenerTest {
    @Test
    fun `add and remove last OnMapClickListener wires GoogleMap correctly`() {
        val googleMap = mock<GoogleMap>()
        val listener = MapEventListener(googleMap)
        val clickCollector = mutableListOf<com.google.android.gms.maps.model.LatLng>()

        // Capture installed click listener on GoogleMap
        val captor = argumentCaptor<GoogleMap.OnMapClickListener>()

        // Add first click listener -> should set GoogleMap's listener
        val l1 = GoogleMap.OnMapClickListener { latLng -> clickCollector.add(latLng) }
        listener += l1
        verify(googleMap).setOnMapClickListener(captor.capture())

        // Simulate a click
        val latLng = com.google.android.gms.maps.model.LatLng(1.0, 2.0)
        captor.firstValue.onMapClick(latLng)
        assertEquals(listOf(latLng), clickCollector)

        // Remove last -> should unset GoogleMap listener
        listener -= l1
        verify(googleMap).setOnMapClickListener(null)
    }

    @Test
    fun `camera move canceled listener unsets correct GoogleMap listener`() {
        val googleMap = mock<GoogleMap>()
        val listener = MapEventListener(googleMap)
        val l = GoogleMap.OnCameraMoveCanceledListener { }

        listener += l
        // When first added, underlying map listener should be set
        verify(googleMap).setOnCameraMoveCanceledListener(org.mockito.kotlin.any())

        // Remove last -> should unset canceled (not started) listener
        listener -= l
        verify(googleMap).setOnCameraMoveCanceledListener(null)
    }

    @Test
    fun `removing last map click listener does not affect other listeners`() {
        val googleMap = mock<GoogleMap>()
        val listener = MapEventListener(googleMap)

        val moveListener = GoogleMap.OnCameraMoveListener { }
        listener += moveListener
        // Underlying camera move listener should be set once
        verify(googleMap).setOnCameraMoveListener(org.mockito.kotlin.any())

        val clickListener = GoogleMap.OnMapClickListener { }
        listener += clickListener
        verify(googleMap).setOnMapClickListener(org.mockito.kotlin.any())

        listener -= clickListener
        // Click listener removed -> map click set to null
        verify(googleMap).setOnMapClickListener(null)
        // Ensure we did NOT clear camera move listener
        org.mockito.Mockito.verify(googleMap, org.mockito.Mockito.never()).setOnCameraMoveListener(null)
    }
}
