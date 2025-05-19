package com.museblossom.callguardai.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    private val _isServicePermission = MutableLiveData<Boolean>()
    val isServicePermission: LiveData<Boolean> get() = _isServicePermission

    fun setBoolean(value: Boolean) {
        _isServicePermission.value = value
    }
}