package com.adsamcik.signalcollector.fragments

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.extensions.startActivity
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.utility.Preferences
import kotlin.coroutines.experimental.Continuation


class FragmentPrivacyDialog : androidx.fragment.app.DialogFragment() {
    private var cont: Continuation<Boolean>? = null

    fun setContinuation(continuation: Continuation<Boolean>) {
        cont = continuation
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = this@FragmentPrivacyDialog.context!!
        val resources = context.resources
        val textBuilder = StringBuilder()
        val arguments = arguments

        textBuilder.append(resources.getString(R.string.privacy_policy_agreement_description))

        val additionalText = arguments?.getInt(BUNDLE_ADDITIONAL_TEXT)
        if (additionalText != null)
            textBuilder.append(resources.getString(additionalText)).append('\n')

        val dialog = AlertDialog.Builder(context, R.style.Theme_AppCompat_Light_Dialog)
                .setMessage(textBuilder.toString())
                .setTitle(R.string.privacy_policy_agreement_title)
                .setPositiveButton(R.string.agree, null)
                .setNegativeButton(R.string.disagree, null)
                .setNeutralButton(R.string.privacy_policy_agreement_link, null)
                .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { _ ->
            Preferences.getPref(context).edit {
                putBoolean(getString(R.string.settings_privacy_policy_agreement_key), true)

                if (arguments?.getBoolean(BUNDLE_SET_AUTOUP_IF_TRUE, false) == true)
                    putInt(getString(R.string.settings_uploading_network_key), 1)
            }

            cont?.resume(true)
            dismiss()
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { _ ->
            Preferences.getPref(context).edit {
                remove(getString(R.string.settings_privacy_policy_agreement_key))

                if (arguments?.getBoolean(BUNDLE_SET_AUTOUP_IF_TRUE, false) == true)
                    remove(getString(R.string.settings_uploading_network_key))
            }

            cont?.resume(false)
            dismiss()
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { _ ->
            showPrivacyPolicy(context)
        }

        isCancelable = false


        return dialog
    }

    companion object {
        const val BUNDLE_ADDITIONAL_TEXT = "ADDINTEXT"
        const val BUNDLE_SET_AUTOUP_IF_TRUE = "AUTOUP"

        fun newInstance(init: (Bundle.() -> Unit)? = null): FragmentPrivacyDialog {
            val dialog = FragmentPrivacyDialog()

            if (init != null)
                dialog.arguments = Bundle().apply(init)

            return dialog
        }

        fun showPrivacyPolicy(context: Context) {
            context.startActivity(Intent.ACTION_VIEW, Uri.parse(Network.URL_PRIVACY_POLICY))
        }
    }
}