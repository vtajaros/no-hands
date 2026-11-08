package com.delata.nohands

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.delata.nohands.camera.CameraManager
import com.delata.nohands.ui.HomeScreen

class MainActivity : ComponentActivity() {

    private val cameraManager = CameraManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeScreen(cameraManager = cameraManager)
        }
    }
}
