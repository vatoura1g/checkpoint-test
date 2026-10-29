package com.votry.productstudio.ml

import android.content.Context

object GhaziRuntime {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun contextOrNull(): Context? = appContext
}
