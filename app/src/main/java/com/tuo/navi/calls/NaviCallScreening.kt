package com.tuo.navi.calls

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.TelephonyManager

/**
 * Filtro chiamate di sistema (ruolo "App ID chiamante e spam"). Android lo consulta prima che il telefono squilli,
 * di solito solo per i numeri che non sono in rubrica. Non legge né registra niente: guarda solo il numero.
 */
class NaviCallScreening : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
        val number = details.handle?.schemeSpecificPart
        val d = if (incoming) CallGuard.onIncoming(this, number) else Decision.ALLOW
        val r = CallResponse.Builder()
        when (d) {
            Decision.REJECT -> r.setDisallowCall(true).setRejectCall(true).setSkipNotification(true).setSkipCallLog(false)
            Decision.ANSWER -> { r.setDisallowCall(false); if (Build.VERSION.SDK_INT >= 30) r.setSilenceCall(true) }
            Decision.ALLOW -> r.setDisallowCall(false)
        }
        runCatching { respondToCall(details, r.build()) }
        if (d == Decision.ANSWER) {
            val b = CallGuard.find(number)
            CallResponder.answer(this, CallGuard.normalize(number), b?.label ?: "")
        }
    }

    companion object {
        fun hasRole(ctx: Context) = runCatching { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
        fun roleIntent(ctx: Context): Intent? = runCatching {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING) else null
        }.getOrNull()
    }
}

/**
 * Rete di sicurezza per i numeri in rubrica (che il filtro di sistema spesso non vede): quando il telefono squilla
 * controlla il numero e, se è in black list, rifiuta o risponde con il tuo audio.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (i.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
        @Suppress("DEPRECATION") val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return
        if (CallResponder.busy) return
        when (CallGuard.onIncoming(ctx, number)) {
            Decision.REJECT -> CallResponder.reject(ctx)
            Decision.ANSWER -> CallResponder.answer(ctx, CallGuard.normalize(number), CallGuard.find(number)?.label ?: "")
            Decision.ALLOW -> {}
        }
    }
}
