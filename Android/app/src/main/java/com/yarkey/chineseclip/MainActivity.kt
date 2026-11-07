package com.yarkey.chineseclip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.yarkey.chineseclip.ui.theme.MyApplicationTheme
import com.yarkey.gallery.presentation.GalleryRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                GalleryRoute(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
