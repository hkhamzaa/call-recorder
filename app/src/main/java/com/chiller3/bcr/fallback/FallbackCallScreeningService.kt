/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.fallback

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.extension.phoneNumber
import com.chiller3.bcr.output.CallDirection

/**
 * Learns the phone number and direction of a call without system app permissions.
 *
 * [CallScreeningService] is the only documented way for a normal, user-installed app to see the
 * other party's number for both incoming and outgoing calls. It requires the user to grant the call
 * screening role, which is requested from the settings screen. BCR never blocks, rejects, or
 * silences anything here — it always responds with an all-default response so the call proceeds
 * exactly as it would have otherwise, and only uses the details to name the recording.
 *
 * When the role has not been granted, recordings simply fall back to a timestamp-only filename.
 */
class FallbackCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        try {
            val number = callDetails.phoneNumber
            val direction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (callDetails.callDirection) {
                    Call.Details.DIRECTION_INCOMING -> CallDirection.IN
                    Call.Details.DIRECTION_OUTGOING -> CallDirection.OUT
                    else -> null
                }
            } else {
                null
            }

            Log.d(TAG, "Screened call: direction=$direction, hasNumber=${number != null}")

            FallbackMode.setPendingCall(number, direction)

            // This callback is the app's opportunity to start a foreground service for the call.
            // Android 12+ forbids starting one from a BroadcastReceiver, which is why this is no
            // longer driven by the PHONE_STATE broadcast. The service goes into the foreground
            // right away and then waits for the call to actually be answered before recording.
            if (!FallbackMode.isPrivileged(this) && Preferences(this).isCallRecordingEnabled) {
                val started = FallbackRecordingService.monitorCall(this)
                if (!started) {
                    Log.e(TAG, "Could not start the recording service for this call")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract call details", e)
        } finally {
            // Always allow the call through untouched. An all-default response neither disallows
            // the call nor suppresses any notification.
            try {
                respondToCall(callDetails, CallResponse.Builder().build())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to respond to call", e)
            }
        }
    }

    companion object {
        private val TAG = FallbackCallScreeningService::class.java.simpleName
    }
}
