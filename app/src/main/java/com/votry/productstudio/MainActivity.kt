package com.votry.productstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.votry.productstudio.ml.GhaziRuntime
import com.votry.productstudio.ui.GhaziEntryApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GhaziRuntime.init(applicationContext)
        setContent { GhaziEntryApp() }
    }
}
