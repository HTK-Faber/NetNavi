package com.tuo.navi.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.tuo.navi.data.SettingsStore
import com.tuo.navi.home.HomeNavi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * La "segreteria" del Navi per i numeri molesti: risponde, fa sentire il tuo audio, ascolta qualche secondo
 * per il riassunto e riaggancia. Nessuna registrazione: l'audio viene solo analizzato in memoria e scartato,
 * si salva soltanto il riassunto.
 *
 * Limite di Android: un'app non può immettere audio direttamente nella linea. Il tuo audio viene riprodotto
 * dal vivavoce e arriva all'altra persona tramite il microfono, quindi si sente un po' come in vivavoce.
 */
object CallResponder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var busy = false; private set
    private var preview: MediaPlayer? = null

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun canAnswer(ctx: Context) = has(ctx, Manifest.permission.ANSWER_PHONE_CALLS) && CallGuard.hasGreeting()

    /** Rifiuta la chiamata che sta squillando (usato quando il filtro di sistema non l'ha già fermata). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun reject(ctx: Context): Boolean = runCatching {
        has(ctx, Manifest.permission.ANSWER_PHONE_CALLS) && ctx.getSystemService(TelecomManager::class.java).endCall()
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(ctx: Context, number: String, label: String) {
        if (busy) return
        busy = true
        val app = ctx.applicationContext
        scope.launch {
            val tm = app.getSystemService(TelecomManager::class.java)
            val am = app.getSystemService(AudioManager::class.java)
            val start = System.currentTimeMillis()
            var summary = ""; var ok = false
            val oldVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val oldSpeaker = am.isSpeakerphoneOn
            try {
                delay(800)
                runCatching { tm.acceptRingingCall() }
                ok = withTimeoutOrNull(5000) { while (!inCall(app)) delay(150); true } == true
                if (!ok) return@launch
                delay(700)
                speaker(am, true)
                runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) }
                play(app, SettingsStore.callMaxPlaySec)
                val listen = SettingsStore.callListenSec
                if (listen > 0 && inCall(app) && has(app, Manifest.permission.RECORD_AUDIO)) {
                    val (text, voiceSec) = listen(app, listen)
                    summary = CallSummary.summarize(text, voiceSec)   // il testo non viene salvato
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { if (inCall(app)) tm.endCall() }
                withContext(Dispatchers.Main) { runCatching { speaker(am, oldSpeaker) } }
                runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldVol, 0) }
                val sec = ((System.currentTimeMillis() - start) / 1000).toInt()
                if (ok) {
                    CallGuard.record(Handled(System.currentTimeMillis(), number, label, "Risposta con il tuo audio", sec, summary))
                    val who = if (label.isNotBlank() && label != "Numero sconosciuto") label else CallGuard.pretty(number)
                    HomeNavi.sayOnReturn("Ho risposto io a $who con il tuo messaggio e ho riattaccato." + if (summary.isNotBlank()) " In breve: $summary" else "")
                } else {
                    CallGuard.record(Handled(System.currentTimeMillis(), number, label, "Non sono riuscito a rispondere", 0, ""))
                    reject(app)
                }
                busy = false
            }
        }
    }

    private fun inCall(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL) return true
        @Suppress("DEPRECATION")
        return runCatching { ctx.getSystemService(TelephonyManager::class.java).callState == TelephonyManager.CALL_STATE_OFFHOOK }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun speaker(am: AudioManager, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            else am.clearCommunicationDevice()
        } else am.isSpeakerphoneOn = on
    }

    private suspend fun play(ctx: Context, maxSec: Int) {
        val f = CallGuard.greetingFile; if (!f.exists()) return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.path); mp.prepare()
            withTimeoutOrNull(maxSec * 1000L) {
                suspendCancellableCoroutine<Unit> { c -> mp.setOnCompletionListener { if (c.isActive) c.resume(Unit) }; mp.start() }
            }
        } catch (_: Throwable) {} finally { runCatching { mp.stop() }; mp.release() }
    }

    /**
     * Ascolta per [sec] secondi. Riconoscimento vocale sul telefono quando Android lo permette (Android 13+, voce italiana offline);
     * altrimenti misura solo per quanti secondi l'altra persona ha parlato. Restituisce testo (solo in memoria) e secondi di voce.
     */
    @SuppressLint("MissingPermission")
    private suspend fun listen(ctx: Context, sec: Int): Pair<String, Int> {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3200)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2) }.getOrNull()
            ?: return "" to 0
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "" to 0 }
        val text = StringBuilder()
        var voiceFrames = 0
        val pipe = if (Build.VERSION.SDK_INT >= 33) runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() else null
        val main = Handler(Looper.getMainLooper())
        var sr: SpeechRecognizer? = null
        if (pipe != null) main.post { sr = startRecognizer(ctx, pipe[0], text) }
        try {
            rec.startRecording()
            val out = pipe?.let { ParcelFileDescriptor.AutoCloseOutputStream(it[1]) }
            val buf = ShortArray(rate / 10); val bytes = ByteArray(buf.size * 2)
            val end = System.currentTimeMillis() + sec * 1000L
            withContext(Dispatchers.IO) {
                while (System.currentTimeMillis() < end && inCall(ctx)) {
                    val n = rec.read(buf, 0, buf.size); if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i].toInt(); sum += v * v; bytes[2 * i] = (v and 0xff).toByte(); bytes[2 * i + 1] = (v shr 8 and 0xff).toByte() }
                    if (sqrt(sum / n) > 700) voiceFrames++
                    out?.let { o -> runCatching { o.write(bytes, 0, n * 2) } }
                }
                runCatching { out?.close() }
            }
            delay(1500) // tempo per gli ultimi risultati
        } finally {
            runCatching { rec.stop() }; rec.release()
            main.post { runCatching { sr?.destroy() } }
        }
        return text.toString().trim() to voiceFrames / 10
    }

    private fun startRecognizer(ctx: Context, src: ParcelFileDescriptor, acc: StringBuilder): SpeechRecognizer? {
        val sr = runCatching {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            else if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
        }.getOrNull() ?: return null
        sr.setRecognitionListener(object : RecognitionListener {
            fun take(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { synchronized(acc) { acc.append(' ').append(it) } } }
            override fun onResults(results: Bundle?) = take(results)
            override fun onSegmentResults(segmentResults: Bundle) = take(segmentResults)
            override fun onEndOfSegmentedSession() {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, src)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }
        runCatching { sr.startListening(i) }
        return sr
    }

    /** Ascolta l'audio della segreteria dall'app. */
    fun previewToggle(ctx: Context): Boolean {
        preview?.let { runCatching { it.stop() }; it.release(); preview = null; return false }
        val f = CallGuard.greetingFile; if (!f.exists()) return false
        return runCatching {
            preview = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(f.path); prepare(); start()
                setOnCompletionListener { it.release(); preview = null }
            }; true
        }.getOrDefault(false)
    }
    fun stopPreview() { preview?.let { runCatching { it.stop() }; it.release() }; preview = null }
}
