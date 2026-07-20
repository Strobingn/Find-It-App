package com.strobingn.findit

import android.app.Application
import com.strobingn.findit.data.HuntRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FindItApp : Application() {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  lateinit var repository: HuntRepository
    private set

  override fun onCreate() {
    super.onCreate()
    repository = HuntRepository.get(this)
    appScope.launch { repository.load() }
  }
}
