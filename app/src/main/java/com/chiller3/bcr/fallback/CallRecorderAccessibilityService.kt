/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.fallback

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.output.PhoneNumber

/**
 * Observes the in-call user interface on devices where BCR is not installed as a system app.
 *
 * This service does two things, both best-effort:
 *
 *  1. It reports that an in-call screen is on top, which is a more timely signal than the
 *     `PHONE_STATE` broadcast on OEM builds that delay or throttle it.
 *  2. When the call screening role has not been granted, it tries to read the other party's number
 *     off the in-call screen so recordings can still be named per contact.
 *
 * It deliberately does NOT try to defeat any platform restriction. Enabling an accessibility
 * service does not grant access to the call audio stream — that was only true before Android 10,
 * and the platform closed it. The service is offered because it improves call detection and
 * filename accuracy, not because it improves audio quality.
 */
class CallRecorderAccessibilityService : AccessibilityService() {
    private lateinit var prefs: Preferences

    /** Debounces service start attempts, since call UI events fire continuously. */
    private var lastStartAttempt = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (!isDialerPackage(packageName)) {
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        // Only bother scraping the UI when we don't already know the number and the user actually
        // wants recordings named per contact.
        if (!prefs.isCallRecordingEnabled || !FallbackMode.isFallbackActive(this)) {
            return
        }

        try {
            val root = rootInActiveWindow ?: return
            try {
                val number = findPhoneNumber(root, 0)
                if (number != null) {
                    Log.d(TAG, "Found a phone number on the in-call screen")
                    FallbackMode.setPendingCallIfAbsent(number)
                }
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }

            maybeStartRecordingService()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect in-call screen", e)
        }
    }

    /**
     * Start the recording service as a backup trigger.
     *
     * [FallbackCallScreeningService] is the primary trigger, but it only runs when the user granted
     * the call screening role. An accessibility service is bound by the system rather than running
     * in a background execution context, so it is a better place to start a foreground service from
     * than a [android.content.BroadcastReceiver] — which Android 12+ forbids outright.
     *
     * If the platform still refuses, [FallbackRecordingService.monitorCall] reports it and the
     * service shuts itself down cleanly instead of being killed by the system.
     */
    private fun maybeStartRecordingService() {
        // Window content events fire constantly during a call, so debounce hard. The service itself
        // is idempotent, but there is no point asking the system over and over.
        val now = SystemClock.elapsedRealtime()
        if (now - lastStartAttempt < START_DEBOUNCE_MILLIS) {
            return
        }
        lastStartAttempt = now

        FallbackRecordingService.monitorCall(this)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    /**
     * Walk the node tree looking for text that parses as a phone number.
     *
     * This is inherently OEM-specific and is only used when nothing better is available.
     */
    private fun findPhoneNumber(node: AccessibilityNodeInfo, depth: Int): PhoneNumber? {
        if (depth > MAX_SCRAPE_DEPTH) {
            return null
        }

        val candidates = sequenceOf(node.text, node.contentDescription)

        for (candidate in candidates) {
            val text = candidate?.toString()?.trim() ?: continue
            if (text.length < MIN_NUMBER_LENGTH || text.length > MAX_NUMBER_LENGTH) {
                continue
            }
            if (!text.any { it.isDigit() }) {
                continue
            }
            // Reject anything that looks like prose (durations, names, status text).
            if (text.any { it.isLetter() }) {
                continue
            }

            try {
                return PhoneNumber(text)
            } catch (_: IllegalArgumentException) {
                // Not a usable number, keep looking.
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                findPhoneNumber(child, depth + 1)?.let { return it }
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }

        return null
    }

    private fun isDialerPackage(packageName: String): Boolean =
        DIALER_PACKAGE_HINTS.any { packageName.contains(it, ignoreCase = true) }

    companion object {
        private val TAG = CallRecorderAccessibilityService::class.java.simpleName

        private const val START_DEBOUNCE_MILLIS = 15_000L
        private const val MAX_SCRAPE_DEPTH = 12
        private const val MIN_NUMBER_LENGTH = 3
        private const val MAX_NUMBER_LENGTH = 24

        /**
         * Substrings matching the in-call UI package on AOSP and common OEM builds. Matching on a
         * substring keeps this working across the many vendor-specific dialer package names.
         */
        private val DIALER_PACKAGE_HINTS = arrayOf(
            "dialer",
            "incallui",
            "com.android.phone",
            "com.android.server.telecom",
            "contacts",
        )
    }
}
