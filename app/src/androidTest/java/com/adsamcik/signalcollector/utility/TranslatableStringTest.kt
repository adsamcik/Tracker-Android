package com.adsamcik.signalcollector.utility


import androidx.test.InstrumentationRegistry
import com.adsamcik.signalcollector.R
import com.squareup.moshi.Moshi
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TranslatableStringTest {
    private val appContext = InstrumentationRegistry.getTargetContext()!!
    private val jsonAdapter = Moshi.Builder().build().adapter(TranslatableString::class.java)
    private val identifier = "activity_idle"
    private val identifierInt = R.string.activity_idle
    private val defaultString = "Default string"

    @Test
    fun loadFromJson() {
        val json = "{\"defaultString\":\"$defaultString\",\"identifier\":$identifier}"
        Assert.assertEquals(appContext.getString(identifierInt), jsonAdapter.fromJson(json)!!.getString(appContext))
    }

    @Test
    fun basicValueTest() {
        val target = TranslatableString(identifier, defaultString,
                object : TranslatableString.IIdentifierResolver {
                    override fun resolve(identifier: String): Int = identifierInt
                })

        Assert.assertEquals(defaultString, target.defaultString)
        Assert.assertEquals(identifier, target.identifier)
        Assert.assertEquals(appContext.getString(identifierInt), target.getString(appContext))
    }

    @Test
    fun exceptionTest() {
        val target = TranslatableString()

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
        val target = TranslatableString()
        target.identifier = identifier
        Assert.assertEquals(appContext.getString(identifierInt), target.getString(appContext))
    }
}
