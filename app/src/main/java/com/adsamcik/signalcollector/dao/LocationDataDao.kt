package com.adsamcik.signalcollector.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy.REPLACE
import androidx.room.Query
import com.adsamcik.signalcollector.data.TimeLocation

@Dao
interface LocationDataDao {

    @Query("SELECT * from location_data")
    fun getAll(): List<WeatherData>

    @Insert(onConflict = REPLACE)
    fun insert(weatherData: TimeLocation)

    @Query("DELETE from location_data")
    fun deleteAll()
}