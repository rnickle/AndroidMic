package com.example.androidmic.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import com.example.androidmic.ui.utils.rememberWindowInfo
import com.example.androidmic.ui.home.HomeScreen
import com.example.androidmic.ui.theme.AndroidMicTheme


class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        val mainViewModel = ViewModelProvider(this, SavedStateViewModelFactory(application, this))[MainViewModel::class.java]

        setContent {
            AndroidMicTheme {
                // get windowInfo for rotation change
                val windowInfo = rememberWindowInfo()

                HomeScreen(mainViewModel, windowInfo)
            }
        }
    }

}