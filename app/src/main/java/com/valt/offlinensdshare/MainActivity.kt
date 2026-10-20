package com.valt.offlinensdshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.valt.offlinensdshare.ui.AppNavHost
import com.valt.offlinensdshare.ui.theme.OfflineNSDShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfflineNSDShareTheme {
                AppNavHost()
            }
        }
    }
}
