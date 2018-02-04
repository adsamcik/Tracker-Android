package com.adsamcik.signalcollector.utility


import android.support.test.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TranslateableStringTest {
    private val appContext = InstrumentationRegistry.getTargetContext()!!
    private val gson = Gson()
    private val identifier = "activity_idle"
    private val identifierInt = R.string.activity_idle
    private val defaultString = "Default string"

    @Test
    fun loadFromJson() {
        val json = "{\"defaultString\":\"$defaultString\",\"identifier\":$identifier}"
        Assert.assertEquals(appContext.getString(identifierInt), gson.fromJson(json, TranslateableString::class.java).getString(appContext))
    }

    @Test
    fun basicValueTest() {
        val target = TranslateableString(identifier, defaultString,
                object : TranslateableString.IIdentifierResolver {
                    override fun resolve(identifier: String): Int = identifierInt
                })

        Assert.assertEquals(defaultString, target.defaultString)
        Assert.assertEquals(identifier, target.identifier)
        Assert.assertEquals(appContext.getString(identifierInt), target.getString(appContext))
    }

    @Test
    fun exceptionTest() {
        val target = TranslateableString()

        try {
            target.getString(appContext)
            throw IllegalStateException("Get string did not throw exception")
        } catch (e: RuntimeException) {
            //everything works as expected
        }

        target.identifier = ""

        try {
            target.getString(appContext)
            throw IllegalStateException("Get string did not throw exception")
        } catch (e: RuntimeException) {
            //everything works as expected
        }

    }

    @Test
    fun dynamicResourceTest() {
        val target = TranslateableString()
        target.identifier = identifier
        Assert.assertEquals(appContext.getString(identifierInt), target.getString(appContext))
    }
}
