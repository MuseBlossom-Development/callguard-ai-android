package com.museblossom.callguardai.ui.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.museblossom.callguardai.ui.main.MainScreenViewModel
import com.whispercppdemo.ui.main.MainScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme

class TestActivity : AppCompatActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhisperCppDemoTheme {
                MainScreen(viewModel)
            }
        }
    }
}