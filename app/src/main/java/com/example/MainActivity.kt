package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.FindItAppRootWired
import com.example.ui.HillshadeViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private var frameProfiling: java.io.Closeable? = null
  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    frameProfiling = com.example.data.TerrainFrameProfiler.attach(window,applicationContext)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(darkTheme = true) {
        val vm: HillshadeViewModel = viewModel()
        FindItAppRootWired(viewModel = vm)
      }
    }
  }
  override fun onDestroy() {
    frameProfiling?.close()
    frameProfiling=null
    super.onDestroy()
  }
}
