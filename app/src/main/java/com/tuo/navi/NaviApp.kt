package com.tuo.navi

import android.app.Application
import com.tuo.navi.ai.AiRouter
import com.tuo.navi.data.ProfileStore
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.voice.NaviVoice

class NaviApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ProfileStore.init(this)
        SettingsStore.init(this)
        com.tuo.navi.data.BackdropStore.init(this)
        NaviVoice.init(this)
        AiRouter.init(this)
    }
    companion object { lateinit var instance: NaviApp; private set }
}
