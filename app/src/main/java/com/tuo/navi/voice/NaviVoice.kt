package com.tuo.navi.voice

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import com.tuo.navi.data.SettingsStore
import java.util.Locale

/**
 * Voce del Navi (TTS di sistema, offline). Regole:
 *  - mai durante una sveglia che suona o una chiamata;
 *  - mai con Non disturbare attivo o suoneria in silenzioso/vibrazione;
 *  - a schermo spento solo se l'utente lo ha permesso.
 */
object NaviVoice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ITALIAN
                // solo voci che funzionano senza rete (privacy)
                runCatching {
                    tts?.voices?.filter { it.locale.language == "it" && !it.isNetworkConnectionRequired }
                        ?.maxByOrNull { it.quality }?.let { tts?.voice = it }
                }
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                ready = true
            }
        }
    }

    fun canSpeak(): Boolean {
        if (!SettingsStore.voiceEnabled || !ready) return false
        val am = app.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE) return false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (alarmRinging()) return false
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val pm = app.getSystemService(PowerManager::class.java)
        if (!pm.isInteractive && !SettingsStore.speakScreenOff) return false
        return true
    }

    fun alarmRinging(): Boolean = runCatching {
        val am = app.getSystemService(AudioManager::class.java)
        am.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
    }.getOrDefault(false)

    fun say(text: String, force: Boolean = false) {
        if (!force && !canSpeak()) return
        if (force && (alarmRinging() || !ready)) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "navi-${System.nanoTime()}")
    }

    val isReady get() = ready

    /** Registra una frase con la voce del Navi in un file (per l'audio della segreteria). Tutto offline. */
    fun synthToFile(text: String, out: java.io.File, done: (Boolean) -> Unit) {
        val t = tts
        if (t == null || !ready) { done(false); return }
        val id = "file-${System.nanoTime()}"
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { if (u == id) done(out.exists() && out.length() > 0) }
            @Deprecated("") override fun onError(u: String?) { if (u == id) done(false) }
        })
        if (t.synthesizeToFile(text, android.os.Bundle(), out, id) != TextToSpeech.SUCCESS) done(false)
    }

    fun stop() { tts?.stop() }
}
