package com.example

import android.app.Application
import com.example.data.SiceDatabase
import com.example.data.SiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class SiceApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var database: SiceDatabase
        private set
    lateinit var repository: SiceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Pre-create WebView cache directories to prevent Chromium opendir Errors in logcat on startup
        try {
            val cacheDir = this.cacheDir
            if (cacheDir != null) {
                val jsDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/js")
                if (!jsDir.exists()) {
                    jsDir.mkdirs()
                }
                val wasmDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache/wasm")
                if (!wasmDir.exists()) {
                    wasmDir.mkdirs()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        database = SiceDatabase.getDatabase(this, applicationScope)
        repository = SiceRepository(database.siceDao())
    }

    fun resetDatabaseAndRepository() {
        SiceDatabase.closeAndResetDatabase()
        database = SiceDatabase.getDatabase(this, applicationScope)
        repository = SiceRepository(database.siceDao())
    }

    override fun getAttributionTag(): String? {
        return "sice_attribution"
    }
}
