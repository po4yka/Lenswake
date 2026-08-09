package dev.po4yka.lenswake.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LenswakeViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(LenswakeUiState())

    val state: StateFlow<LenswakeUiState> = mutableState.asStateFlow()
}
