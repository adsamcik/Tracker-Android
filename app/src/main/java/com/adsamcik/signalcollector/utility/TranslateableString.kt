package com.adsamcik.signalcollector.utility

import android.content.Context
import com.google.firebase.crash.FirebaseCrash
import com.vimeo.stag.UseStag

@UseStag
class TranslateableString {
    //Stag
    internal var defaultString: String? = null
    internal var identifier: String? = null
    internal var identifierResolver: IIdentifierResolver? = null

    constructor() {
        defaultString = null
        identifier = null
        identifierResolver = null
    }

    constructor(identifier: String, defaultString: String?, identifierResolver: IIdentifierResolver?) {
        this.identifier = identifier
        this.defaultString = defaultString
        this.identifierResolver = identifierResolver
    }

    fun getString(context: Context): String {
        val identifier = identifier!!
        var id = identifierResolver?.resolve(identifier)
        if (id == 0) {
            id = getId(identifier, context)
            if (id == 0) {
                if (defaultString == null)
                    throw RuntimeException("Translation not found and default string is null for identifier " + identifier)
                else
                    FirebaseCrash.report(RuntimeException("Missing translation for " + identifier))

                return defaultString!!
            }
        }

        return context.getString(id!!)
    }

    private fun getId(identifier: String, context: Context): Int = context.resources.getIdentifier(identifier, "string", context.packageName)

    interface IIdentifierResolver {
        fun resolve(identifier: String): Int
    }
}