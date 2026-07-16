package com.qiao.dougrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qiao.dougrid.ui.DouGridApp
import com.qiao.dougrid.ui.theme.DouGridTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: DouGridViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DouGridTheme(mode = state.settings.themeMode) {
                DouGridApp(viewModel = viewModel, state = state)
            }
        }
    }
}
