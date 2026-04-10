package com.vaults.app

import android.app.Application

class VaultsApp : Application() {
    lateinit var db: VaultsDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = VaultsDatabase.getInstance(this)
    }

    companion object {
        lateinit var instance: VaultsApp
            private set
    }
}