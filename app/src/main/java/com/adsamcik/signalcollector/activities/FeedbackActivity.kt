package com.adsamcik.signalcollector.activities

import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.*
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.jobs.FeedbackUploadJob
import com.adsamcik.signalcollector.signin.Signin
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.SnackMaker
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch



/**
 * FeedbackActivity that provides users with ability to send feedback
 */
class FeedbackActivity : DetailActivity() {
    private var currentType: FeedbackType? = null
    private var selected: View? = null

    private var mSelectedState: ColorStateList? = null
    private var mDefaultState: ColorStateList? = null

    /**
     * TextWatcher that watches text for changes to remove errors that are associated with them
     */
    internal class FeedbackTextWatcher private constructor(private val textLayout: com.google.android.material.textfield.TextInputLayout, private val minLength: Int = 0) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

        }

        override fun afterTextChanged(s: Editable) {
            if (s.length in minLength..textLayout.counterMaxLength) {
                textLayout.error = null
                textLayout.editText!!.removeTextChangedListener(this)
            }
        }

        companion object {
            fun setError(textLayout: com.google.android.material.textfield.TextInputLayout, errorText: String, minLength: Int = 0) {
                textLayout.error = errorText
                textLayout.editText!!.addTextChangedListener(FeedbackTextWatcher(textLayout, minLength))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.feedback_title)
        val activity = this
        launch {
            val user = Signin.getUserAsync(activity)
            if (user != null) {
                launch(UI) {
                    val parent = createLinearScrollableContentParent(true)
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

                        val summaryTextLayout = parent.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.feedback_summary_wrap)
                        val descriptionTextLayout = parent.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.feedback_description_wrap)

                        val summaryText = summaryTextLayout.editText!!

                        val sumText = summaryText.text
                        val textLength = sumText.length
                        summaryTextLayout.counterMaxLength = MAX_TEXT_LENGTH

                        if (textLength < MIN_TEXT_LENGTH) {
                            FeedbackTextWatcher.setError(summaryTextLayout, getString(R.string.feedback_error_short_summary), MIN_TEXT_LENGTH)
                        } else if (MAX_TEXT_LENGTH < textLength) {
                            FeedbackTextWatcher.setError(summaryTextLayout, getString(R.string.feedback_error_long_summary))
                        } else {
                            val summary = summaryText.text.toString().trim { it <= ' ' }.replace("\\s+".toRegex(), " ")
                            if (summary.length <= MIN_TEXT_LENGTH)
                                summaryTextLayout.error = getString(R.string.feedback_error_spaces_summary)
                            else {
                                val descriptionText = descriptionTextLayout.editText!!

                                if (descriptionText.text.length > descriptionTextLayout.counterMaxLength) {
                                    FeedbackTextWatcher.setError(descriptionTextLayout, getString(R.string.feedback_error_long_description))
                                    return@setOnClickListener
                                }

                                val description = descriptionText.text.toString().trim { it <= ' ' }.replace("\\s+".toRegex(), " ")


                                val pb = PersistableBundle(3)
                                pb.putString(FeedbackUploadJob.SUMMARY, summary)
                                pb.putString(FeedbackUploadJob.DESCRIPTION, description)
                                pb.putInt(FeedbackUploadJob.TYPE, currentType!!.ordinal)

                                val data = Data.Builder()
                                        .putString(FeedbackUploadJob.SUMMARY, summary)
                                        .putString(FeedbackUploadJob.DESCRIPTION, description)
                                        .putInt(FeedbackUploadJob.TYPE, currentType!!.ordinal)
                                        .build()

                                val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

                                val uploadWork = OneTimeWorkRequestBuilder<FeedbackUploadJob>()
                                        .setInputData(data)
                                        .addTag(FeedbackUploadJob.TAG)
                                        .setConstraints(constraints)
                                        .build()
                                WorkManager.getInstance().enqueue(uploadWork)

                                finish()
                            }
                        }
                    }
                }
            }
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
        var jobId = 6578
    }

    private enum class FeedbackType {
        Bug,
        Feature,
        Other
    }
}
