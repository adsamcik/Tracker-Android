package com.adsamcik.signalcollector

import android.support.annotation.CheckResult
import android.support.test.espresso.*
import android.view.View
import org.hamcrest.Matcher
import org.hamcrest.Matchers

@CheckResult
fun ViewInteraction.exists(): Boolean {
    try {
        perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return Matchers.any(View::class.java)
            }

            override fun getDescription(): String {
                return "check for existence"
            }

            override fun perform(uiController: UiController, view: View) {
                // no op, if this is run, then the execution will continue after .perform(...)
            }
        })
        return true
    } catch (ex: AmbiguousViewMatcherException) {
        // if there's any interaction later with the same matcher, that'll fail anyway
        return true // we found more than one
    } catch (ex: NoMatchingViewException) {
        return false
    } catch (ex: NoMatchingRootException) {
        // optional depending on what you think "exists" means
        return false
    }
}