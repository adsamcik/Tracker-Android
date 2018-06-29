package com.adsamcik.signalcollector.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.file.CacheStore
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.network.NetworkLoader
import com.adsamcik.signalcollector.network.Prices
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.signin.User
import com.adsamcik.signalcollector.test.useMock
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.SnackMaker
import com.crashlytics.android.Crashlytics
import com.squareup.moshi.Moshi
import kotlinx.android.synthetic.main.activity_user.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.Response
import java.io.IOException
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KMutableProperty0



/**
 * User Activity is activity that contains Signin and Server settings
 */
class UserActivity : DetailActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parent = createLinearContentParent(true)
        layoutInflater.inflate(R.layout.activity_user, parent)
        setTitle(R.string.settings_account_title)

        Signin.onStateChangeCallback = { status, user -> onUserStateChange(status, user) }
        Signin.signIn(this@UserActivity, null, true)
    }

    /**
     * Called when users state changes
     */
    private fun onUserStateChange(status: Signin.SigninStatus, user: User?) {
        if (status == Signin.SigninStatus.SIGNED)
            user!!

        launch(UI) {
            when (status) {
                Signin.SigninStatus.SIGNED -> {
                    progressbar_user.visibility = View.GONE
                    layout_signed_in.visibility = View.VISIBLE
                    layout_signed_in.findViewById<Button>(R.id.button_sign_out).setOnClickListener { _ -> Signin.signOut(this@UserActivity) }
                    button_sign_in.visibility = View.GONE
                    resolveUserMenuOnLogin(user!!)
                }
                Signin.SigninStatus.SIGNED_NO_DATA -> {
                    progressbar_user.visibility = View.VISIBLE
                    layout_signed_in.visibility = View.VISIBLE
                    layout_signed_in.findViewById<Button>(R.id.button_sign_out).setOnClickListener { _ -> Signin.signOut(this@UserActivity) }
                    button_sign_in.visibility = View.GONE
                }
                Signin.SigninStatus.SIGNIN_FAILED, Signin.SigninStatus.SILENT_SIGNIN_FAILED, Signin.SigninStatus.NOT_SIGNED -> {
                    progressbar_user.visibility = View.GONE
                    button_sign_in.visibility = View.VISIBLE
                    layout_signed_in.visibility = View.GONE
                    button_sign_in.setOnClickListener { _ ->
                        launch {
                            val usr = Signin.signIn(this@UserActivity, false)
                            if (usr != null) {
                                if (!usr.isServerDataAvailable) {
                                    onUserStateChange(Signin.SigninStatus.SIGNED_NO_DATA, user)
                                    usr.addServerDataCallback { value ->
                                        onUserStateChange(Signin.status, value)
                                    }
                                } else
                                    onUserStateChange(Signin.SigninStatus.SIGNED, user)
                            } else
                                SnackMaker(root).showSnackbar(R.string.error_failed_signin)
                        }
                    }
                    layout_signed_in.findViewById<View>(R.id.layout_server_settings).visibility = View.GONE
                }
                Signin.SigninStatus.SIGNIN_IN_PROGRESS -> {
                    progressbar_user.visibility = View.VISIBLE
                    button_sign_in.visibility = View.GONE
                    layout_signed_in.visibility = View.GONE
                }
            }
        }
    }

    private fun resolveUserMenuOnLogin(u: User) {
        if (!u.isServerDataAvailable) {
            SnackMaker(root).showSnackbar(R.string.error_connection_failed)
            return
        }

        launch {
            val user = Signin.getUserAsync(this@UserActivity)
            if (user != null) {
                if (useMock) {
                    launch(UI) {
                        initUserMenu(user, Prices.mock())
                    }
                } else {
                    val priceRequestState = NetworkLoader.requestSignedAsync(Network.URL_USER_PRICES, user.token, Constants.DAY_IN_MINUTES, this@UserActivity, Preferences.PREF_USER_PRICES, Prices::class.java)
                    if (priceRequestState.first.success) {
                        val prices = priceRequestState.second!!
                        launch(UI) {
                            initUserMenu(user, prices)
                        }
                    }
                }
            }
        }
    }

    /**
     * Initializes user menu with all the callbacks it needs
     */
    private fun initUserMenu(user: User, prices: Prices) {
        layout_server_settings.visibility = View.VISIBLE

        textview_wireless_points.text = String.format(getString(R.string.user_have_wireless_points), Assist.formatNumber(user.wirelessPoints))

        switch_renew_map.text = getString(R.string.user_renew_map)
        switch_renew_map.isChecked = user.networkPreferences!!.renewMap
        switch_renew_map.setOnClickListener {
            switch_renew_map.isEnabled = false
            val isChecked = switch_renew_map.isChecked

            if (useMock) {
                launch {
                    delay(1, TimeUnit.SECONDS)
                    launch(UI) { switch_renew_map.isEnabled = true }
                }
            } else {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", isChecked.toString()).build()
                val request = Network.requestPOST(this, Network.URL_USER_UPDATE_MAP_PREFERENCE, body).build()
                Network.clientAuth(this).newCall(request).enqueue(
                        onChangeMapNetworkPreference(switch_renew_map,
                                isChecked,
                                user,
                                user.networkPreferences!!::renewMap,
                                user.networkInfo!!::mapAccessUntil,
                                prices.PRICE_30DAY_MAP.toLong(),
                                textview_map_access_time)
                )
            }
        }

        if (user.networkInfo!!.mapAccessUntil > System.currentTimeMillis())
            textview_map_access_time.text = String.format(getString(R.string.user_access_date), dateFormat.format(Date(user.networkInfo!!.mapAccessUntil)))
        else
            textview_map_access_time.visibility = View.GONE
        textview_map_cost.text = String.format(getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_MAP))

        switch_renew_personal_map.text = getString(R.string.user_renew_personal_map)
        switch_renew_personal_map.isChecked = user.networkPreferences!!.renewPersonalMap
        switch_renew_personal_map.setOnClickListener {
            switch_renew_personal_map.isEnabled = false

            val isChecked = switch_renew_personal_map.isChecked

            if (useMock) {
                launch {
                    delay(1, TimeUnit.SECONDS)
                    launch(UI) { switch_renew_personal_map.isEnabled = true }
                }
            } else {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", isChecked.toString()).build()
                val request = Network.requestPOST(this, Network.URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE, body).build()
                Network.clientAuth(this).newCall(request).enqueue(
                        //this could be done better but due to time constraint there is not enough time to properly rewrite it to kotlin
                        onChangeMapNetworkPreference(switch_renew_personal_map,
                                isChecked,
                                user,
                                user.networkPreferences!!::renewPersonalMap,
                                user.networkInfo!!::personalMapAccessUntil,
                                prices.PRICE_30DAY_PERSONAL_MAP.toLong(),
                                textview_personal_map_access_time)
                )
            }
        }

        if (user.networkInfo!!.personalMapAccessUntil > System.currentTimeMillis())
            textview_personal_map_access_time.text = String.format(getString(R.string.user_access_date), dateFormat.format(Date(user.networkInfo!!.personalMapAccessUntil)))
        else
            textview_personal_map_access_time.visibility = View.GONE
        textview_personal_map_cost.text = String.format(getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_PERSONAL_MAP))

        layout_signed_in.findViewById<View>(R.id.layout_server_settings).visibility = View.VISIBLE
    }

    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())

    private fun onChangeMapNetworkPreference(compoundButton: CompoundButton,
                                             desiredState: Boolean,
                                             user: User,
                                             state: KMutableProperty0<Boolean>,
                                             accessTime: KMutableProperty0<Long>,
                                             price: Long,
                                             timeTextView: TextView): Callback {
        return object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                launch(UI) {
                    compoundButton.isEnabled = true
                    compoundButton.isChecked = !desiredState
                }
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    state.set(desiredState)
                    if (desiredState) {
                        val rBody = response.body()
                        if (rBody != null) {
                            val temp = accessTime.get()
                            accessTime.set(rBody.string().toLong())
                            if (temp != accessTime.get()) {
                                user.addWirelessPoints(-price)
                                launch(UI) {
                                    textview_wireless_points.text = getString(R.string.user_have_wireless_points, Assist.formatNumber(user.wirelessPoints))
                                    timeTextView.text = String.format(getString(R.string.user_access_date), dateFormat.format(Date(accessTime.get())))
                                    timeTextView.visibility = View.VISIBLE
                                }
                            }

                        } else
                            Crashlytics.logException(Throwable("Body is null"))
                    }
                    val moshi = Moshi.Builder().build()
                    val jsonAdapter = moshi.adapter(User::class.java)
                    CacheStore.saveString(this@UserActivity, Preferences.PREF_USER_DATA, jsonAdapter.toJson(user), false)
                } else {
                    launch(UI) { compoundButton.isChecked = !desiredState }
                    if (response.code() == 403)
                        SnackMaker(root).showSnackbar(R.string.user_not_enough_wp)
                }
                launch(UI) { compoundButton.isEnabled = true }
                response.close()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == Signin.RC_SIGN_IN) {
            Signin.onSignResult(this, resultCode, data!!)
        }
    }
}