package com.writeright.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.writeright.ai.board.BoardViewModel
import com.writeright.ai.ui.BoardScreen
import com.writeright.ai.ui.WriteRightTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WriteRightTheme {
                val vm: BoardViewModel = viewModel()
                BoardScreen(vm)
            }
        }
    }
}
