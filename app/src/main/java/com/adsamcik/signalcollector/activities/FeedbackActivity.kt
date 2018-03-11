package com.adsamcik.signalcollector.activities

import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.support.design.widget.TextInputLayout
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.network.Network
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.SnackMaker
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import android.graphics.Paint.UNDERLINE_TEXT_FLAG



class FeedbackActivity : DetailActivity() {
    private var currentType: FeedbackType? = null
    private var selected: View? = null

    private var mSelectedState: ColorStateList? = null
    private var mDefaultState: ColorStateList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.feedback_title)
        val activity = this
        launch {
            val user = Signin.getUserAsync(activity)
            if (user != null) {
                launch(UI) {
                    val parent = createScrollableContentParent(true)
                    val groupRoot = layoutInflater.inflate(R.layout.layout_feedback, parent) as ViewGroup

                    val feedbackLayout = groupRoot.findViewById<LinearLayout>(R.id.feedback_type_layout)

                    val csl = Assist.getSelectionStateLists(resources, theme)
                    mSelectedState = csl[1]
                    mDefaultState = csl[0]

                    for (i in 0 until feedbackLayout.childCount)
                        feedbackLayout.getChildAt(i).setOnClickListener { v -> updateType(v, FeedbackType.values()[feedbackLayout.indexOfChild(v)]) }

                    groupRoot.findViewById<View>(R.id.feedback_cancel_button).setOnClickListener { _ -> finish() }
                    groupRoot.findViewById<View>(R.id.feedback_send_button).setOnClickListener { _ ->
                        if (currentType == null) {
                            SnackMaker(parent).showSnackbar(R.string.feedback_error_type)
                            Assist.hideSoftKeyboard(activity, parent)
                            return@setOnClickListener
                        }

                        val summaryTextLayout = parent.findViewById<TextInputLayout>(R.id.feedback_summary_wrap)
                        val summaryText = summaryTextLayout.editText!!

                        val sumText = summaryText.text
                        val textLength = sumText.length
                        summaryTextLayout.counterMaxLength = MAX_TEXT_LENGTH

                        val textWatcher = object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

                            }

                            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

                            }

                            override fun afterTextChanged(s: Editable) {
                                if (s.length in MIN_TEXT_LENGTH..MAX_TEXT_LENGTH) {
                                    summaryTextLayout.error = null
                                    summaryText.removeTextChangedListener(this)
                                }
                            }
                        }

                        if (textLength < MIN_TEXT_LENGTH) {
                            summaryTextLayout.error = getString(R.string.feedback_error_short_summary)
                            summaryText.addTextChangedListener(textWatcher)
                        } else if (MAX_TEXT_LENGTH < textLength) {
                            summaryTextLayout.error = getString(R.string.feedback_error_long_summary)
                            summaryText.addTextChangedListener(textWatcher)
                        } else {
                            val result = summaryText.text.toString().trim { it <= ' ' }.replace("\\s+".toRegex(), " ")
                            if (result.length <= MIN_TEXT_LENGTH)
                                summaryTextLayout.error = getString(R.string.feedback_error_spaces_summary)
                            else {
                                val builder = Network.generateAuthBody(user.token).addFormDataPart("summary", result).addFormDataPart("type", Integer.toString(currentType!!.ordinal))

                                val descriptionTextLayout = parent.findViewById<TextInputLayout>(R.id.feedback_description_wrap)
                                val descriptionText = descriptionTextLayout.editText!!

                                val description = descriptionText.text.toString().trim { it <= ' ' }
                                builder.addFormDataPart("description", if (description.isNotEmpty()) description else "")

                                Network.client(activity, null).newCall(Network.requestPOST(Network.URL_FEEDBACK, builder.build())).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {
                                        SnackMaker(groupRoot).showSnackbar(R.string.error_connection_failed)
                                    }

                                    @Throws(IOException::class)
                                    override fun onResponse(call: Call, response: Response) {
                                        if (response.isSuccessful)
                                            finish()
                                        else
                                            SnackMaker(groupRoot).showSnackbar(R.string.error_general)
                                    }
                                })

                            }
                        }
                    }
                }
            } else
                finish()

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Signin.removeOnSignedListeners()
    }

    private fun updateType(v: View, select: FeedbackType) {
        currentType = select

        if (selected != null) {
            val selectedTextView = selected as TextView
            selectedTextView.setTextColor(mDefaultState)
            selectedTextView.paintFlags = selectedTextView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }

        val newTextView = v as TextView
        newTextView.setTextColor(mSelectedState)
        newTextView.paintFlags = newTextView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        selected = v
    }

    companion object {
        const val MIN_TEXT_LENGTH = 8
        const val MAX_TEXT_LENGTH = 140
    }

    private enum class FeedbackType {
        Bug,
        Feature,
        Other
    }
}
