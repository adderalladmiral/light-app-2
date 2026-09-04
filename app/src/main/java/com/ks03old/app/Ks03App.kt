package com.ks03old.app

import android.app.Application

/**
 * Holds one BleController for the whole app lifetime so the main screen and
 * the stress-test screen can share the same live connection instead of each
 * Activity opening its own.
 */
class Ks03App : Application() {
    lateinit var ble: BleController
        private set

    override fun onCreate() {
        super.onCreate()
        ble = BleController(applicationContext)
    }
}
