package com.adsamcik.signalcollector.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.file.DataStore
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
import com.google.gson.Gson
import kotlinx.android.synthetic.main.activity_user.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.Response
import java.io.IOException
import java.text.DateFormat
import java.util.*

class UserActivity : DetailActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parent = createContentParent(true)
        layoutInflater.inflate(R.layout.activity_user, parent)
        setTitle(R.string.settings_account)
        Signin.onStateChangeCallback = { status, user -> onUserStateChange(status, user) }
    }

    override fun onResume() {
        super.onResume()
        val context = this
        launch {
            onUserStateChange(Signin.status, Signin.getUserAsync(context))
        }
    }

    private fun onUserStateChange(status: Signin.SigninStatus, user: User?) {
        launch(UI) {
            when (status) {
                Signin.SigninStatus.SIGNED -> {
                    signed_in_menu.visibility = View.VISIBLE
                    signed_in_menu.findViewById<Button>(R.id.sign_out_button).setOnClickListener { _ -> Signin.signOut(this@UserActivity) }
                    sign_in_button.visibility = View.GONE
                    resolveUserMenuOnLogin(user!!)
                }
                Signin.SigninStatus.SIGNED_NO_DATA -> {
                    signed_in_menu.visibility = View.VISIBLE
                    signed_in_menu.findViewById<Button>(R.id.sign_out_button).setOnClickListener { _ -> Signin.signOut(this@UserActivity) }
                    sign_in_button.visibility = View.GONE
                }
                Signin.SigninStatus.SIGNIN_FAILED, Signin.SigninStatus.SILENT_SIGNIN_FAILED, Signin.SigninStatus.NOT_SIGNED -> {
                    sign_in_button.visibility = View.VISIBLE
                    signed_in_menu.visibility = View.GONE
                    sign_in_button.setOnClickListener { _ ->
                        async {
                            val usr = Signin.signIn(this@UserActivity, false)
                            if (usr != null) {
                                if (!usr.isServerDataAvailable) {
                                    onUserStateChange(Signin.SigninStatus.SIGNED_NO_DATA, user)
                                    usr.addServerDataCallback({ value ->
                                        onUserStateChange(Signin.SigninStatus.SIGNED, value)
                                    })
                                }
                            } else
                                SnackMaker(root).showSnackbar(R.string.error_failed_signin)
                        }
                    }
                    signed_in_menu.findViewById<View>(R.id.signed_in_server_menu).visibility = View.GONE
                }
                Signin.SigninStatus.SIGNIN_IN_PROGRESS -> {
                    sign_in_button.visibility = View.GONE
                    signed_in_menu.visibility = View.GONE
                }
            }
        }
    }

    private fun resolveUserMenuOnLogin(u: User) {
        if (!u.isServerDataAvailable) {
            SnackMaker(root).showSnackbar(R.string.error_connection_failed)
            return
        }

        async {
            val user = Signin.getUserAsync(this@UserActivity)
            if (user != null) {
                val priceRequestState = NetworkLoader.requestSignedAsync(Network.URL_USER_PRICES, user.token, Constants.DAY_IN_MINUTES, this@UserActivity, Preferences.PREF_USER_PRICES, Prices::class.java)
                if (priceRequestState.first.success) {
                    val prices = if (useMock) Prices.mock() else priceRequestState.second!!
                    launch(UI) {

                        val userInfoLayout = signed_in_menu.getChildAt(0) as LinearLayout
                        userInfoLayout.visibility = View.VISIBLE

                        val wPointsTextView = userInfoLayout.getChildAt(0) as TextView
                        wPointsTextView.text = String.format(getString(R.string.user_have_wireless_points), Assist.formatNumber(u.wirelessPoints))

                        val mapAccessLayout = userInfoLayout.getChildAt(1) as LinearLayout
                        val mapAccessSwitch = mapAccessLayout.getChildAt(0) as Switch
                        val mapAccessTimeTextView = mapAccessLayout.getChildAt(1) as TextView

                        mapAccessSwitch.text = getString(R.string.user_renew_map)
                        mapAccessSwitch.isChecked = u.networkPreferences!!.renewMap
                        mapAccessSwitch.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                            compoundButton.isEnabled = false
                            val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", java.lang.Boolean.toString(b)).build()
                            Network.client(this@UserActivity, u.token).newCall(Network.requestPOST(Network.URL_USER_UPDATE_MAP_PREFERENCE, body)).enqueue(
                                    onChangeMapNetworkPreference(compoundButton, b, user, prices.PRICE_30DAY_MAP.toLong(), textview_map_access_time)
                            )
                        }

                        if (u.networkInfo!!.mapAccessUntil > System.currentTimeMillis())
                            mapAccessTimeTextView.text = String.format(getString(R.string.user_access_date), dateFormat.format(Date(u.networkInfo!!.mapAccessUntil)))
                        else
                            mapAccessTimeTextView.visibility = View.GONE
                        (mapAccessLayout.getChildAt(2) as TextView).text = String.format(getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_MAP))

                        val userMapAccessLayout = userInfoLayout.getChildAt(2) as LinearLayout
                        val userMapAccessSwitch = userMapAccessLayout.getChildAt(0) as Switch
                        val personalMapAccessTimeTextView = userMapAccessLayout.getChildAt(1) as TextView

                        userMapAccessSwitch.text = getString(R.string.user_renew_personal_map)
                        userMapAccessSwitch.isChecked = u.networkPreferences!!.renewPersonalMap
                        userMapAccessSwitch.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                            compoundButton.isEnabled = false
                            val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("value", java.lang.Boolean.toString(b)).build()
                            Network.client(this@UserActivity, u.token).newCall(Network.requestPOST(Network.URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE, body)).enqueue(
                                    onChangeMapNetworkPreference(compoundButton, b, user, prices.PRICE_30DAY_PERSONAL_MAP.toLong(), textview_personal_map_access_time)
                            )
                        }

                        if (u.networkInfo!!.personalMapAccessUntil > System.currentTimeMillis())
                            personalMapAccessTimeTextView.text = String.format(getString(R.string.user_access_date), dateFormat.format(Date()))
                        else
                            personalMapAccessTimeTextView.visibility = View.GONE
                        (userMapAccessLayout.getChildAt(2) as TextView).text = String.format(getString(R.string.user_cost_per_month), Assist.formatNumber(prices.PRICE_30DAY_PERSONAL_MAP))

                        signed_in_menu.findViewById<View>(R.id.signed_in_server_menu).visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())

    private fun onChangeMapNetworkPreference(compoundButton: CompoundButton, desiredState: Boolean, user: User, price: Long, timeTextView: TextView): Callback {
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
                    val networkInfo = user.networkInfo!!
                    user.networkPreferences!!.renewMap = desiredState
                    if (desiredState) {
                        val rBody = response.body()
                        if (rBody != null) {
                            val temp = networkInfo.mapAccessUntil
                            networkInfo.mapAccessUntil = java.lang.Long.parseLong(rBody.string())
                            if (temp != networkInfo.mapAccessUntil) {
                                user.addWirelessPoints(-price)
                                launch(UI) {
                                    wireless_points.text = getString(R.string.user_have_wireless_points, Assist.formatNumber(user.wirelessPoints))
                                    timeTextView.text = String.format(getString(R.string.user_access_date), dateFormat.format(Date(networkInfo.mapAccessUntil)))
                                    timeTextView.visibility = View.VISIBLE
                                }
                            }

                        } else
                            Crashlytics.logException(Throwable("Body is null"))
                    }
                    DataStore.saveString(this@UserActivity, Preferences.PREF_USER_DATA, Gson().toJson(user), false)
                } else {
                    launch(UI) { compoundButton.isChecked = !desiredState }
                    SnackMaker(this@UserActivity).showSnackbar(R.string.user_not_enough_wp)
                }
                launch(UI) { compoundButton.isEnabled = true }
                response.close()
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == Signin.RC_SIGN_IN) {
            Signin.onSignResult(this, resultCode, data)
        }
    }
}