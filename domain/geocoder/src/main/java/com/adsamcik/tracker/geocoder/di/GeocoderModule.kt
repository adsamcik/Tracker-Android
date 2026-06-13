package com.adsamcik.tracker.geocoder.di

import com.adsamcik.tracker.geocoder.DefaultReverseGeocoder
import com.adsamcik.tracker.geocoder.ReverseGeocoder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the offline geocoder implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GeocoderModule {

    @Binds
    abstract fun bindReverseGeocoder(impl: DefaultReverseGeocoder): ReverseGeocoder
}
