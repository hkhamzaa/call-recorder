/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.fallback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Observes the telephony call state for diagnostics only.
 *
 * IMPORTANT: this receiver must never start [FallbackRecordingService]. A [BroadcastReceiver] runs
 * in a background execution context, and since Android 12 starting a foreground service from there
 * throws [android.app.ForegroundServiceStartNotAllowedException]. Doing so previously caused the
 * app to be killed on every call.
 *
 * Recording is started from [FallbackCallScreeningService.onScreenCall] and from
 * [CallRecorderAccessibilityService] instead, both of which are contexts the platform permits.
 * Once running, [FallbackRecordingService] registers its own
 * [android.telephony.TelephonyCallback] and therefore detects the end of the call without help
 * from this receiver.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        // Logging only. Deliberately no service interaction of any kind: a background receiver can
        // neither start a foreground service nor a background service on modern Android.
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.v(TAG, "Observed phone state: $state")

        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            // The call is over, so any number learned from call screening is now stale. Clearing it
            // here is safe from a receiver because it only touches in-memory state.
            FallbackMode.clearPendingCall()
        }
    }

    companion object {
        private val TAG = PhoneStateReceiver::class.java.simpleName
    }
}
