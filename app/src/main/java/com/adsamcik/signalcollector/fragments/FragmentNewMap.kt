package com.adsamcik.signalcollector.fragments


import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.support.constraint.ConstraintLayout
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.adsamcik.draggable.IOnDemandView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.MapLayer
import com.adsamcik.signalcollector.network.SignalsTileProvider
import com.adsamcik.signalcollector.uitools.KeyboardManager
import com.adsamcik.signalcollector.uitools.leftMargin
import com.adsamcik.signalcollector.uitools.navbarHeight
import com.adsamcik.signalcollector.uitools.setBottomMargin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.FabMenu
import com.adsamcik.signalcollector.utility.MapFilterRule
import com.adsamcik.signalcollector.utility.SnackMaker
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import java.io.IOException

class FragmentNewMap : Fragment(), GoogleMap.OnCameraIdleListener, OnMapReadyCallback, IOnDemandView {
    private var locationListener: UpdateLocationListener? = null
    private var type: String? = null
    private var map: GoogleMap? = null
    private var tileProvider: SignalsTileProvider? = null
    private var locationManager: LocationManager? = null
    private var activeOverlay: TileOverlay? = null

    private var searchText: EditText? = null

    private var fragmentView: View? = null

    private var userRadius: Circle? = null
    private var userCenter: Marker? = null

    private var fActivity: FragmentActivity? = null
    private var menu: FabMenu<MapLayer, MapFilterRule>? = null

    //fab.isShown() is not fast enough (it probably changes on the next ui update)
    private var fabOneVisible: Boolean = false
    private var showFabTwo: Boolean = false

    private var mapLayerFilterRule: MapFilterRule? = null

    private var hasPermissions = false

    private var keyboardManager: KeyboardManager? = null

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {
        if (requestCode == PERMISSION_LOCATION_CODE && success && fActivity != null) {
            val fragmentTransaction = fActivity!!.supportFragmentManager.beginTransaction()
            val newFrag = FragmentNewMap()
            fragmentTransaction.replace(R.id.container, newFrag, getString(R.string.menu_map))
            newFrag.onEnter(fActivity!!)
            fragmentTransaction.commitAllowingStateLoss()
        }
    }

    /**
     * Check if permission to access fine location is granted
     * If not and is android 6 or newer, than it prompts you to enable it
     *
     * @return is permission available atm
     */
    private fun checkLocationPermission(context: Context?, request: Boolean): Boolean {
        if (context == null)
            return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            return true
        else if (request && Build.VERSION.SDK_INT >= 23)
            activity!!.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_LOCATION_CODE)
        return false
    }

    override fun onLeave(activity: Activity) {
        if (hasPermissions) {
            if (locationManager != null)
                locationManager!!.removeUpdates(locationListener)
            locationListener!!.cleanup()

            menu?.hideAndDestroy()
        }

        keyboardManager?.closeKeyboard()
    }

    override fun onEnter(activity: Activity) {
        this.fActivity = activity as FragmentActivity
        initializeLocationListener(activity)
        initializeKeyboardDetection()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (fActivity == null)
            fActivity = activity

        val activity = fActivity!!
        hasPermissions = checkLocationPermission(context, true)
        if (Assist.checkPlayServices(activity) && container != null && hasPermissions) {
            fragmentView = inflater.inflate(R.layout.fragment_new_map, container, false)
        } else {
            fragmentView = inflater.inflate(R.layout.layout_error, container, false)
            (fragmentView!!.findViewById<View>(R.id.activity_error_text) as TextView).setText(if (hasPermissions) R.string.error_play_services_not_available else R.string.error_missing_permission)
            return fragmentView
        }


        val mapFragment = SupportMapFragment.newInstance()
        val fragmentTransaction = fragmentManager!!.beginTransaction()
        fragmentTransaction.add(R.id.container_map, mapFragment)
        fragmentTransaction.commit()
        mapFragment.getMapAsync(this)

        mapLayerFilterRule = MapFilterRule()

        val searchText = fragmentView!!.findViewById<EditText>(R.id.map_search)!!

        searchText.setOnEditorActionListener { v, _, _ ->
            val geocoder = Geocoder(context)
            try {
                val addresses = geocoder.getFromLocationName(v.text.toString(), 1)
                if (addresses != null && addresses.size > 0) {
                    if (map != null && locationListener != null) {
                        val address = addresses[0]
                        locationListener!!.stopUsingUserPosition(true)
                        locationListener!!.animateToPositionZoom(LatLng(address.latitude, address.longitude), 13f)
                    }
                }

            } catch (e: IOException) {
                SnackMaker(fragmentView!!).showSnackbar(R.string.error_general)
            }

            true
        }

        this.searchText = searchText

        return fragmentView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentView = null
        menu = null
    }

    private fun initializeKeyboardDetection() {
        val searchText = searchText!!
        val originalMargin = (searchText.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
        val navbarHeight = navbarHeight(activity!!)

        val keyboardManager = KeyboardManager(fragmentView!!.rootView)

        keyboardManager.addKeyboardListener { opened, keyboardHeight ->
            Log.d("TAG", "State is " + (if (opened) "OPEN" else "CLOSED") + " with margin " + (if (opened) originalMargin else (originalMargin + navbarHeight)))

            when (opened) {
                true -> {
                    searchText.setBottomMargin(originalMargin + keyboardHeight)
                    map!!.setPadding(searchText.leftMargin, 0, 0, originalMargin + keyboardHeight + searchText.height)
                }
                false -> {
                    searchText.setBottomMargin(originalMargin + navbarHeight + Assist.dpToPx(context!!, 32))
                    map!!.setPadding(0, 0, 0, navbarHeight)
                }
            }
        }

        this.keyboardManager = keyboardManager
    }

    /**
     * Changes overlay of the map
     *
     * @param type exact case-sensitive name of the overlay
     */
    private fun changeMapOverlay(type: String) {
        if (map != null) {
            if (type != this.type || activeOverlay == null) {
                if (activeOverlay != null)
                    activeOverlay!!.remove()

                if (type == getString(R.string.map_personal))
                    tileProvider!!.setTypePersonal()
                else
                    tileProvider!!.setType(type)

                this.type = type
                fActivity!!.runOnUiThread { activeOverlay = map!!.addTileOverlay(TileOverlayOptions().tileProvider(tileProvider)) }
            }
        } else
            this.type = type
    }

    private fun initializeLocationListener(context: Context) {
        if (locationListener == null) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            locationListener = UpdateLocationListener(sensorManager)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        this.map = map
        userRadius = null
        userCenter = null
        val c = context ?: return
        map.setMapStyle(MapStyleOptions.loadRawResourceStyle(c, R.raw.map_style))

        //does not work well with bearing. Known bug in Google maps api since 2014.
        //val padding = navbarHeight(c)
        //map.setPadding(0, 0, 0, padding)
        tileProvider = SignalsTileProvider(c, MAX_ZOOM)

        initializeLocationListener(c)

        map.setOnCameraIdleListener(this)

        map.setMaxZoomPreference(MAX_ZOOM.toFloat())
        if (checkLocationPermission(c, false)) {
            locationListener!!.setFollowMyPosition(true, c)
            if (locationManager == null)
                locationManager = context!!.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            assert(locationManager != null)
            val l = locationManager!!.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (l != null) {
                val cp = CameraPosition.builder().target(LatLng(l.latitude, l.longitude)).zoom(16f).build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(cp))
                locationListener!!.targetPosition = cp.target
                drawUserPosition(cp.target, l.accuracy)
            }
        }

        if (type != null)
            changeMapOverlay(type!!)


        val uiSettings = map.uiSettings
        uiSettings.isMapToolbarEnabled = false
        uiSettings.isIndoorLevelPickerEnabled = false
        uiSettings.isCompassEnabled = false

        locationListener!!.registerMap(map)

        if (locationManager == null)
            locationManager = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager!!.requestLocationUpdates(1, 5f, Criteria(), locationListener, Looper.myLooper())
    }

    /**
     * Draws user accuracy radius and location
     * Is automatically initialized if no circle exists
     *
     * @param latlng   Latitude and longitude
     * @param accuracy Accuracy
     */
    private fun drawUserPosition(latlng: LatLng, accuracy: Float) {
        if (map == null)
            return
        if (userRadius == null) {
            val c = context
            userRadius = map!!.addCircle(CircleOptions()
                    .fillColor(ContextCompat.getColor(c!!, R.color.color_user_accuracy))
                    .center(latlng)
                    .radius(accuracy.toDouble())
                    .zIndex(100f)
                    .strokeWidth(0f))

            userCenter = map!!.addMarker(MarkerOptions()
                    .flat(true)
                    .position(latlng)
                    .icon(BitmapDescriptorFactory.fromResource(R.mipmap.ic_user_location))
                    .anchor(0.5f, 0.5f)
            )
        } else {
            userRadius!!.center = latlng
            userRadius!!.radius = accuracy.toDouble()
            userCenter!!.position = latlng
        }
    }

    override fun onCameraIdle() {
        if (map != null) {
            val bounds = map!!.projection.visibleRegion.latLngBounds
            mapLayerFilterRule!!.updateBounds(bounds.northeast.latitude, bounds.northeast.longitude, bounds.southwest.latitude, bounds.southwest.longitude)
        }
    }

    private inner class UpdateLocationListener(private val sensorManager: SensorManager) : LocationListener, SensorEventListener {
        private var followMyPosition = false
        internal var useGyroscope = false

        private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        private var lastUserPos: LatLng? = null
        var targetPosition: LatLng? = null
        private var targetTilt: Float = 0f
        private var targetBearing: Float = 0f
        private var targetZoom: Float = 0f

        private var fab: FloatingActionButton? = null

        private val cameraChangeListener: GoogleMap.OnCameraMoveStartedListener = GoogleMap.OnCameraMoveStartedListener { i ->
            if (followMyPosition && i == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
                stopUsingUserPosition(true)
        }

        private val DURATION_STANDARD = 1000
        private val DURATION_SHORT = 200

        internal var prevRotation: Float = 0.toFloat()

        internal var orientation = FloatArray(3)
        internal var rMat = FloatArray(9)

        fun registerMap(map: GoogleMap) {
            map.setOnCameraMoveStartedListener(cameraChangeListener)
            val cameraPosition = map.cameraPosition
            targetPosition = cameraPosition.target ?: LatLng(0.0, 0.0)
            targetTilt = cameraPosition.tilt
            targetBearing = cameraPosition.bearing
            targetZoom = cameraPosition.zoom
        }

        fun setFAB(fab: FloatingActionButton, context: Context) {
            this.fab = fab
            setFollowMyPosition(followMyPosition, context)
        }

        fun setFollowMyPosition(value: Boolean, context: Context) {
            this.followMyPosition = value
            if (fab != null && getContext() != null) {
                if (followMyPosition)
                    fab!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_accent))
                else
                    fab!!.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
            }
        }

        private fun stopUsingGyroscope(returnToDefault: Boolean) {
            useGyroscope = false
            sensorManager.unregisterListener(this, rotationVector)
            targetBearing = 0f
            targetTilt = 0f
            if (returnToDefault)
                animateTo(targetPosition, targetZoom, 0f, 0f, DURATION_SHORT)
        }

        fun stopUsingUserPosition(returnToDefault: Boolean) {
            if (followMyPosition) {
                setFollowMyPosition(false, context!!)
                if (useGyroscope) {
                    stopUsingGyroscope(returnToDefault)
                    fab!!.setImageResource(R.drawable.ic_gps_fixed_black_24dp)
                }
            }
        }

        override fun onLocationChanged(location: Location) {
            lastUserPos = LatLng(location.latitude, location.longitude)
            drawUserPosition(lastUserPos!!, location.accuracy)
            if (followMyPosition && map != null)
                moveTo(lastUserPos!!)
        }

        fun animateToPositionZoom(position: LatLng, zoom: Float) {
            targetPosition = position
            targetZoom = zoom
            animateTo(position, zoom, targetTilt, targetBearing, DURATION_STANDARD)
        }

        fun animateToBearing(bearing: Float) {
            animateTo(targetPosition, targetZoom, targetTilt, bearing, DURATION_SHORT)
            targetBearing = bearing
        }

        fun animateToTilt(tilt: Float) {
            targetTilt = tilt
            animateTo(targetPosition, targetZoom, tilt, targetBearing, DURATION_SHORT)
        }

        private fun animateTo(position: LatLng?, zoom: Float, tilt: Float, bearing: Float, duration: Int) {
            val builder = CameraPosition.Builder(map!!.cameraPosition).target(position).zoom(zoom).tilt(tilt).bearing(bearing)
            map!!.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), duration, null)
        }

        fun onMyPositionFabClick() {
            if (followMyPosition) {
                if (useGyroscope) {
                    fab!!.setImageResource(R.drawable.ic_gps_fixed_black_24dp)
                    stopUsingGyroscope(true)
                } else if (rotationVector != null) {
                    useGyroscope = true
                    sensorManager.registerListener(this, rotationVector,
                            SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
                    animateToTilt(45f)
                    fab!!.setImageResource(R.drawable.ic_compass)
                }
            } else {
                setFollowMyPosition(true, context!!)
            }

            if (lastUserPos != null)
                moveTo(lastUserPos!!)
        }

        private fun moveTo(latlng: LatLng) {
            val zoom = map!!.cameraPosition.zoom
            animateToPositionZoom(latlng, if (zoom < 16) 16f else if (zoom > 17) 17f else zoom)
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {

        }

        override fun onProviderEnabled(provider: String) {

        }

        override fun onProviderDisabled(provider: String) {

        }

        fun cleanup() {
            if (map != null)
                map!!.setOnMyLocationButtonClickListener(null)
        }

        private fun updateRotation(rotation: Int) {
            if (map != null && targetPosition != null && prevRotation != rotation.toFloat()) {
                animateToBearing(rotation.toFloat())
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                // calculate th rotation matrix
                SensorManager.getRotationMatrixFromVector(rMat, event.values)
                // getPref the azimuth value (orientation[0]) in degree
                updateRotation((Math.toDegrees(SensorManager.getOrientation(rMat, orientation)[0].toDouble()) + 360).toInt() % 360)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

        }
    }

    companion object {
        private val MAX_ZOOM = 17
        private val PERMISSION_LOCATION_CODE = 200

        private val TAG = "SignalsMap"
    }

}
