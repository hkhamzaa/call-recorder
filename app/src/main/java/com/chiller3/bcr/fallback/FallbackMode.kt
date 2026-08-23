/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.fallback

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.chiller3.bcr.output.CallDirection
import com.chiller3.bcr.output.PhoneNumber
import java.time.ZonedDateTime

/**
 * Determines whether BCR is running as a system app and tracks the state needed by the
 * unprivileged fallback recorder.
 *
 * BCR upstream only supports being installed as a system app, where it holds
 * [Manifest.permission.CAPTURE_AUDIO_OUTPUT] and can capture the real call audio stream. When the
 * APK is simply installed by the user on stock firmware, that permission cannot be granted, so the
 * app falls back to recording the microphone instead.
 *
 * IMPORTANT: the fallback cannot capture the remote party's audio. Since Android 10, the platform
 * silences unprivileged microphone capture during a telephony call, and the `VOICE_*` audio sources
 * are gated behind [Manifest.permission.CAPTURE_AUDIO_OUTPUT]. In practice the fallback records the
 * local side clearly and the remote side only as acoustic leakage from the earpiece, which means it
 * is genuinely two-way only when the speakerphone is on. This is an OS restriction that BCR does
 * not attempt to work around.
 */
object FallbackMode {
    private val TAG = FallbackMode::class.java.simpleName

    /**
     * Whether BCR holds the system app permission needed to capture the call audio stream.
     *
     * This is only true when the APK is installed in `/system/priv-app` alongside the matching
     * `privapp-permissions` XML, which is what the Magisk/KernelSU module does.
     */
    fun isPrivileged(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT) ==
                PackageManager.PERMISSION_GRANTED

    /** Whether the unprivileged microphone-based recorder should be used. */
    fun isFallbackActive(context: Context): Boolean = !isPrivileged(context)

    /**
     * Whether BCR currently holds the call screening role.
     *
     * Without it, [FallbackCallScreeningService] never runs and recordings are named by timestamp
     * only, since an unprivileged app has no other supported way to learn the other party's number.
     */
    fun isCallScreeningRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query call screening role", e)
            false
        }
    }

    /** Whether the device supports granting the call screening role at all. */
    fun isCallScreeningSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query call screening role availability", e)
            false
        }
    }

    /** Build the intent that asks the user to grant the call screening role. */
    fun createCallScreeningRoleIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create call screening role intent", e)
            null
        }
    }

    /** Whether the user has enabled BCR's accessibility service. */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, CallRecorderAccessibilityService::class.java)

        return try {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    val info = it.resolveInfo.serviceInfo
                    ComponentName(info.packageName, info.name) == expected
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query enabled accessibility services", e)
            false
        }
    }

    /**
     * Details about the call currently being observed by the fallback path.
     *
     * The number and direction are populated by [FallbackCallScreeningService] when the user has
     * granted the call screening role. Without that role, only the timestamp is known and the
     * filename template falls back to a plain timestamp.
     */
    data class PendingCall(
        val number: PhoneNumber?,
        val direction: CallDirection?,
        val timestamp: ZonedDateTime,
    )

    private val lock = Any()
    private var pendingCall: PendingCall? = null

    /** Record what the call screening service learned about an upcoming call. */
    fun setPendingCall(number: PhoneNumber?, direction: CallDirection?) {
        synchronized(lock) {
            pendingCall = PendingCall(number, direction, ZonedDateTime.now())
            Log.d(TAG, "Pending call recorded (direction=$direction, hasNumber=${number != null})")
        }
    }

    /**
     * Record a number scraped from the in-call screen, but only if nothing better is already known.
     *
     * The call screening service is authoritative, so a number it provided is never overwritten by
     * the accessibility service's best-effort UI scraping. The timestamp of an existing entry is
     * preserved so that call start time stays accurate.
     */
    fun setPendingCallIfAbsent(number: PhoneNumber) {
        synchronized(lock) {
            val existing = pendingCall

            pendingCall = if (existing == null) {
                PendingCall(number, null, ZonedDateTime.now())
            } else if (existing.number == null) {
                existing.copy(number = number)
            } else {
                return
            }

            Log.d(TAG, "Pending call number populated from in-call screen")
        }
    }

    /**
     * Take the pending call details, if any.
     *
     * The details are consumed so that a later call without screening information does not reuse a
     * stale number. A pending call older than [PENDING_CALL_MAX_AGE_MILLIS] is discarded, which
     * avoids attributing a number to an unrelated call after a missed screening callback.
     */
    fun takePendingCall(): PendingCall? = synchronized(lock) {
        val call = pendingCall
        pendingCall = null

        if (call == null) {
            return null
        }

        val ageMillis = java.time.Duration.between(call.timestamp, ZonedDateTime.now()).toMillis()
        if (ageMillis > PENDING_CALL_MAX_AGE_MILLIS) {
            Log.w(TAG, "Discarding stale pending call (${ageMillis}ms old)")
            return null
        }

        call
    }

    fun clearPendingCall() {
        synchronized(lock) {
            pendingCall = null
        }
    }

    private const val PENDING_CALL_MAX_AGE_MILLIS = 120_000L
}
