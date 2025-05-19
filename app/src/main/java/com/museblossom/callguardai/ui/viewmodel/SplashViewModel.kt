package com.museblossom.callguardai.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.museblossom.callguardai.repository.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SplashViewModel(application: Application) : AndroidViewModel(application) {
    private val repo      = DownloadRepository(application)
    private val _progress = MutableStateFlow(0.0)      // Double
    val progress: StateFlow<Double> = _progress        // 외부 공개

    fun ensureGgmlFile() {
        if (!repo.isFileExists()) {
            Log.d("다운확인 ","파일있음")
            viewModelScope.launch {
                try {
                    Log.d("다운확인 ","다운 시작")
                    repo.downloadFile(_progress)
                } catch (e: Exception) {
                    Log.d("다운확인 ","다운 실패 ; ${e.message}")
                    _progress.value = -1.0
                }
            }
        } else {
            _progress.value = 100.0
        }
    }
}