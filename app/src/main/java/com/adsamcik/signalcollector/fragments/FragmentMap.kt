package com.adsamcik.signalcollector.fragments


import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.drawable.AnimatedVectorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.support.annotation.DrawableRes
import android.support.constraint.ConstraintLayout
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.adsamcik.draggable.*
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.MapLayer
import com.adsamcik.signalcollector.extensions.transaction
import com.adsamcik.signalcollector.extensions.transactionStateLoss
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.network.NetworkLoader
import com.adsamcik.signalcollector.network.SignalsTileProvider
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.uitools.*
import com.adsamcik.signalcollector.utility.*
import com.adsamcik.signalcollector.utility.Assist.navbarSize
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MINUTES
import com.crashlytics.android.Crashlytics
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import kotlinx.android.synthetic.main.fragment_map.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FragmentMap : Fragment(), GoogleMap.OnCameraIdleListener, OnMapReadyCallback, IOnDemandView {
    private var locationListener: UpdateLocationListener? = null
    private var type: String? = null
    private var map: GoogleMap? = null
    private var mapFragment: SupportMapFragment? = null

    private var tileProvider: SignalsTileProvider? = null
    private var locationManager: LocationManager? = null
    private var activeOverlay: TileOverlay? = null

    private var fragmentView: View? = null

    private var userRadius: Circle? = null
    private var userCenter: Marker? = null

    private var fActivity: FragmentActivity? = null

    private var mapLayerFilterRule = MapFilterRule()

    private var hasPermissions = false

    private var keyboardManager: KeyboardManager? = null
    private var searchOriginalMargin = 0
    private var keyboardInitialized = AtomicBoolean(false)

    private var colorManager: ColorManager? = null

    private var mapLayers: ArrayList<MapLayer>? = null

    private var fragmentMapMenu: AtomicReference<FragmentMapMenu?> = AtomicReference(null)

    override fun onPermissionResponse(requestCode: Int, success: Boolean) {
        if (requestCode == PERMISSION_LOCATION_CODE && success && fActivity != null) {
            val newFrag = FragmentMap()
            fActivity!!.supportFragmentManager.transactionStateLoss {
                replace(R.id.container, newFrag)
            }
            newFrag.onEnter(fActivity!!)
        }
    }

    /**
     * Check if permission to access fine location is granted
     * If not and is android 6 or newer, than it prompts you to enable it
     *
     * @return i
     * s permission available atm
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
        }

        if (keyboardManager != null) {
            val keyboardManager = keyboardManager!!
            keyboardManager.closeKeyboard()
            keyboardManager.removeKeyboardListener(keyboardListener)
            keyboardInitialized.set(false)
        }
    }

    override fun onEnter(activity: Activity) {
        this.fActivity = activity as FragmentActivity

        if (mapFragment == null && view != null) {
            val mapFragment = SupportMapFragment.newInstance()
            mapFragment.getMapAsync(this)
            fragmentManager!!.transaction {
                replace(R.id.container_map, mapFragment)
            }
            this.mapFragment = mapFragment
        } else
            loadMapLayers()

        Tips.showTips(activity, Tips.MAP_TIPS)
    }

    override fun onStart() {
        super.onStart()
        if (map_ui_parent == null)
            return
        initializeLocationListener(context!!)
        initializeUserElements()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapsInitializer.initialize(context)
        retainInstance = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val activity = activity!!
        hasPermissions = checkLocationPermission(activity, true)
        if (Assist.checkPlayServices(activity) && container != null && hasPermissions) {
            fragmentView = if (view != null)
                view
            else
                inflater.inflate(R.layout.fragment_map, container, false)
        } else {
            fragmentView = inflater.inflate(R.layout.layout_error, container, false)
            (fragmentView!!.findViewById<View>(R.id.activity_error_text) as TextView).setText(if (hasPermissions) R.string.error_play_services_not_available else R.string.error_missing_permission)
            return fragmentView
        }

        colorManager = ColorSupervisor.createColorManager(activity)

        return fragmentView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fragmentView = null
        mapFragment = null
        if (colorManager != null)
            ColorSupervisor.recycleColorManager(colorManager!!)
    }

    /**
     * Changes overlay of the map
     *
     * @param type exact case-sensitive name of the overlay
     */
    private fun changeMapOverlay(type: String) {
        if (map != null) {
            if (type != this.type || activeOverlay == null) {
                if (type == getString(R.string.map_personal))
                    tileProvider!!.setTypePersonal()
                else
                    tileProvider!!.setType(type)

                this.type = type

                val tileOverlayOptions = TileOverlayOptions().tileProvider(tileProvider)

                launch(UI) {
                    activeOverlay?.remove()
                    activeOverlay = map!!.addTileOverlay(tileOverlayOptions)
                }
            }
        } else
            this.type = type
    }

    /**
     * Keyboard listener
     * Is object variable so it can be unsubscribed when map is closed
     */
    private val keyboardListener: KeyboardListener = { opened, keyboardHeight ->
        val activity = activity
        //map_menu_button is null in some rare cases. I am not entirely sure when it happens, but it seems to be quite rare so checking for null is probably OK atm
        if (activity != null && map_menu_button != null) {
            val (position, navbarHeight) = navbarSize(activity)
            //check payloads
            when (opened) {
                true -> {
                    if (position == Assist.NavBarPosition.BOTTOM) {
                        val top = searchOriginalMargin +
                                keyboardHeight +
                                map_menu_button.height +
                                edittext_map_search.paddingBottom +
                                edittext_map_search.paddingTop + edittext_map_search.height

                        map_ui_parent.setBottomMargin(searchOriginalMargin + keyboardHeight)
                        map?.setPadding(map_ui_parent.paddingLeft, 0, 0, top)
                    }
                }
                false -> {
                    if (position == Assist.NavBarPosition.BOTTOM) {
                        map_ui_parent.setBottomMargin(searchOriginalMargin + navbarHeight.y + 32.dpAsPx)
                        map?.setPadding(0, 0, 0, navbarHeight.y)
                    } else {
                        map_ui_parent.setBottomMargin(searchOriginalMargin + 32.dpAsPx)
                        map?.setPadding(0, 0, 0, 0)
                    }
                }
            }

            //Update map_menu_button position after UI has been redrawn
            map_menu_button.post {
                map_menu_button.moveToState(map_menu_button.state, false)
            }
        }
    }


    /**
     * Initializes keyboard detection so margins are properly set when keyboard is open
     * Cannot be 100% reliable because Android does not provide any keyboard api whatsoever
     * Relies on detecting bigger layout size changes.
     */
    private fun initializeKeyboardDetection() {
        if (keyboardInitialized.get())
            keyboardManager!!.onDisplaySizeChanged()
        else {
            if (keyboardManager == null) {
                searchOriginalMargin = (map_ui_parent.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
                keyboardManager = KeyboardManager(fragmentView!!.rootView)
            }

            keyboardManager!!.addKeyboardListener(keyboardListener)
            keyboardInitialized.set(true)
        }
    }

    /**
     * Initializes location listener which takes care of drawing users location, following it and more.
     */
    private fun initializeLocationListener(context: Context) {
        if (locationListener == null) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            locationListener = UpdateLocationListener(sensorManager)
        }
    }

    /**
     * Initializes UI elements and colors
     */
    private fun initializeUserElements() {
        initializeKeyboardDetection()
        edittext_map_search.setOnEditorActionListener { v, _, _ ->
            search(v.text.toString())
            true
        }

        button_map_search.setOnClickListener {
            search(edittext_map_search.text.toString())
        }

        map_menu_button.visibility = View.INVISIBLE

        locationListener!!.setButton(button_map_my_location, context!!)

        colorManager!!.watchElement(ColorView(map_menu_button, 2, false, false))
        colorManager!!.watchElement(ColorView(layout_map_controls, 3, true, false))
    }

    /**
     * Uses search field and Geocoder to find given location
     * Does not rely on Google Maps search API because this way it does not have to deal with API call restrictions
     */
    private fun search(searchText: String) {
        val geocoder = Geocoder(context)
        try {
            val addresses = geocoder.getFromLocationName(searchText, 1)
            if (addresses?.isNotEmpty() == true) {
                if (map != null && locationListener != null) {
                    val address = addresses[0]
                    locationListener!!.stopUsingUserPosition(true)
                    locationListener!!.animateToPositionZoom(LatLng(address.latitude, address.longitude), 13f)
                }
            }

        } catch (e: IOException) {
            Crashlytics.logException(e)
            SnackMaker(fragmentView!!).showSnackbar(R.string.error_general)
        }
    }

    /**
     * Sets menu drawable to given drawable and starts its animation
     */
    private fun animateMenuDrawable(@DrawableRes drawableRes: Int) {
        val context = context
        if (context != null) {
            val drawable = context.getDrawable(drawableRes) as AnimatedVectorDrawable
            map_menu_button.setImageDrawable(drawable)
            drawable.start()
        }
    }

    /**
     * Called when map is ready and initializes everything that needs to be initialized after maps loading
     */
    override fun onMapReady(map: GoogleMap) {
        this.map = map
        userRadius = null
        userCenter = null
        val context = context ?: return

        colorManager!!.addListener { luminance, _ ->
            if (luminance >= -32)
                map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style))
            else
                map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark))
        }

        //does not work well with bearing. Known bug in Google maps api since 2014.
        //Unfortunately had to be implemented anyway under new UI because Google requires Google logo to be visible at all times.
        //val padding = navbarHeight(c)
        //map.setPadding(0, 0, 0, padding)
        tileProvider = SignalsTileProvider(context, MAX_ZOOM)

        initializeLocationListener(context)

        map.setOnCameraIdleListener(this)

        map.setMaxZoomPreference(MAX_ZOOM.toFloat())
        if (checkLocationPermission(context, false)) {
            locationListener!!.followMyPosition = true

            val locationManager = locationManager
                    ?: context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val l = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (l != null) {
                val cp = CameraPosition.builder().target(LatLng(l.latitude, l.longitude)).zoom(16f).build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(cp))
                locationListener!!.setUserPosition(cp.target)
                drawUserPosition(cp.target, l.accuracy)
            }

            this.locationManager = locationManager
        }

        if (type != null)
            changeMapOverlay(type!!)


        val uiSettings = map.uiSettings
        uiSettings.isMapToolbarEnabled = false
        uiSettings.isIndoorLevelPickerEnabled = false
        uiSettings.isCompassEnabled = false

        locationListener!!.registerMap(map)

        if (locationManager == null)
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager!!.requestLocationUpdates(1, 5f, Criteria(), locationListener, Looper.myLooper())

        initializeKeyboardDetection()

        loadMapLayers()
    }

    /**
     * Function that mocks or actually loads map layers from the server based on [useMock]
     */
    private fun loadMapLayers() {
        val activity = activity!!
        if (useMock) {
            launch {
                delay(1, TimeUnit.SECONDS)
                val mockArray = MapLayer.mockArray()
                val mockArrayList = ArrayList<MapLayer>(mockArray.size)
                mockArrayList.addAll(mockArray)
                mapLayers = mockArrayList
                initializeMenuButton()
            }
        } else {
            launch {
                val user = Signin.getUserAsync(activity)
                user?.addServerDataCallback {
                    val networkInfo = it.networkInfo!!
                    if (networkInfo.hasMapAccess() || networkInfo.hasPersonalMapAccess()) {
                        val list = ArrayList<MapLayer>()
                        if (networkInfo.hasPersonalMapAccess())
                            list.add(MapLayer(fActivity!!.getString(R.string.map_personal), MapLayer.MAX_LATITUDE, MapLayer.MAX_LONGITUDE, MapLayer.MIN_LATITUDE, MapLayer.MIN_LONGITUDE))

                        if (networkInfo.hasMapAccess()) {
                            runBlocking {
                                val mapListRequest = NetworkLoader.requestSignedAsync(Network.URL_MAPS_AVAILABLE, user.token, DAY_IN_MINUTES, activity, Preferences.PREF_AVAILABLE_MAPS, Array<MapLayer>::class.java)
                                if (mapListRequest.first.dataAvailable && mapListRequest.second!!.isNotEmpty()) {
                                    val layerArray = mapListRequest.second!!
                                    var savedOverlay = Preferences.getPref(activity).getString(Preferences.PREF_DEFAULT_MAP_OVERLAY, layerArray[0].name)
                                    if (!MapLayer.contains(layerArray, savedOverlay)) {
                                        savedOverlay = layerArray[0].name
                                        Preferences.getPref(activity).edit().putString(Preferences.PREF_DEFAULT_MAP_OVERLAY, savedOverlay).apply()
                                    }

                                    val defaultOverlay = savedOverlay

                                    changeMapOverlay(defaultOverlay)
                                    list.addAll(layerArray)
                                }
                            }
                        }

                        mapLayers = list
                        initializeMenuButton()
                    }

                }
            }
        }
    }

    /**
     * Initialized draggable menu button
     */
    private fun initializeMenuButton() {
        //uses post to make sure heights and widths are available
        map_menu_parent.post {
            val activity = activity!!
            val payload = DraggablePayload(activity, FragmentMapMenu::class.java, map_menu_parent, map_menu_button)
            payload.initialTranslation = Point(0, map_menu_parent.height)
            payload.stickToTarget = true
            payload.anchor = DragTargetAnchor.LeftTop
            payload.offsets = Offset(0, map_menu_button.height)
            payload.width = map_menu_parent.width
            payload.height = map_menu_parent.height
            payload.onInitialized = {
                fragmentMapMenu.set(it)
                colorManager!!.watchRecycler(ColorView(it.view!!, 2))
                val layers = mapLayers
                if (layers != null && layers.isNotEmpty()) {
                    val adapter = it.adapter
                    adapter.clear()
                    adapter.addAll(layers)
                    it.onClickListener = { mapLayer ->
                        changeMapOverlay(mapLayer.name)
                        map_menu_button.moveToState(DraggableImageButton.State.INITIAL, true)
                    }
                    it.filter(mapLayerFilterRule)
                }
            }
            payload.onBeforeDestroyed = {
                fragmentMapMenu.set(null)
                colorManager!!.stopWatchingRecycler(R.id.list)
            }

            map_menu_button.onEnterStateListener = { _, state, _, hasStateChanged ->
                if (hasStateChanged) {
                    if (state == DraggableImageButton.State.TARGET)
                        animateMenuDrawable(R.drawable.up_to_down)
                    else if (state == DraggableImageButton.State.INITIAL)
                        animateMenuDrawable(R.drawable.down_to_up)
                }
            }
            //payload.initialTranslation = Point(map_menu_parent.x.toInt(), map_menu_parent.y.toInt() + map_menu_parent.height)
            //payload.setOffsetsDp(Offset(0, 24))
            map_menu_button.addPayload(payload)
            if (mapLayers?.isNotEmpty() == true) {
                map_menu_button.visibility = View.VISIBLE
                colorManager!!.notififyChangeOn(map_menu_button)
            }
        }

        map_menu_button.extendTouchAreaBy(0, 12.dpAsPx, 0, 0)
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
            mapLayerFilterRule.updateBounds(bounds.northeast.latitude, bounds.northeast.longitude, bounds.southwest.latitude, bounds.southwest.longitude)
            fragmentMapMenu.get()?.filter(mapLayerFilterRule)

        }
    }

    private inner class UpdateLocationListener(private val sensorManager: SensorManager) : LocationListener, SensorEventListener {
        var followMyPosition = false
        internal var useGyroscope = false

        private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        private var lastUserPos: LatLng? = null
        private var targetPosition: LatLng? = null
        private var targetTilt: Float = 0f
        private var targetBearing: Float = 0f
        private var targetZoom: Float = 0f

        private var button: ImageButton? = null

        private val cameraChangeListener: GoogleMap.OnCameraMoveStartedListener = GoogleMap.OnCameraMoveStartedListener { i ->
            if (followMyPosition && i == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE)
                stopUsingUserPosition(true)
        }

        internal var prevRotation: Float = 0.toFloat()

        internal var orientation = FloatArray(3)
        internal var rMat = FloatArray(9)

        fun setUserPosition(latlng: LatLng) {
            this.lastUserPos = latlng

            if (followMyPosition && map != null) {
                moveTo(latlng)
            }
        }

        fun registerMap(map: GoogleMap) {
            map.setOnCameraMoveStartedListener(cameraChangeListener)
            val cameraPosition = map.cameraPosition
            targetPosition = cameraPosition.target ?: LatLng(0.0, 0.0)
            targetTilt = cameraPosition.tilt
            targetBearing = cameraPosition.bearing
            targetZoom = cameraPosition.zoom
        }

        fun setButton(button: ImageButton, context: Context) {
            this.button = button
            button.setOnClickListener {
                if (checkLocationPermission(context, true))
                    onMyPositionButtonClick()
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
                this.followMyPosition = false
                if (useGyroscope) {
                    stopUsingGyroscope(returnToDefault)
                }
                button!!.setImageResource(R.drawable.ic_gps_not_fixed_black_24dp)
            }
        }

        override fun onLocationChanged(location: Location) {
            val latlng = LatLng(location.latitude, location.longitude)
            drawUserPosition(latlng, location.accuracy)
            setUserPosition(latlng)
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

        fun onMyPositionButtonClick() {
            val button = button!!
            if (followMyPosition) {
                when {
                    useGyroscope -> {
                        button.setImageResource(R.drawable.ic_gps_fixed_black_24dp)
                        stopUsingGyroscope(true)
                    }
                    rotationVector != null -> {
                        useGyroscope = true
                        sensorManager.registerListener(this, rotationVector,
                                SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI)
                        animateToTilt(45f)
                        button.setImageResource(R.drawable.ic_compass)
                    }
                }
            } else {
                button.setImageResource(R.drawable.ic_gps_fixed_black_24dp)
                this.followMyPosition = true
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
        private const val MAX_ZOOM = 17
        private const val PERMISSION_LOCATION_CODE = 200

        private const val DURATION_STANDARD = 1000
        private const val DURATION_SHORT = 200

        private const val TAG = "SignalsMap"
    }

}
