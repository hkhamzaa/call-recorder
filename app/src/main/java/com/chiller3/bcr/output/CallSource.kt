/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.output

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.Call
import android.util.Log
import com.chiller3.bcr.extension.phoneNumber
import com.chiller3.bcr.format.AudioSource
import com.chiller3.bcr.withContactsByPhoneNumber
import java.time.ZonedDateTime

/**
 * The origin of a recording.
 *
 * [com.chiller3.bcr.RecorderThread] does not care how a call was detected, only what to name the
 * output file, which numbers to evaluate record rules against, and which audio sources to capture.
 * Abstracting that out lets the privileged [android.telecom.InCallService] path and the unprivileged
 * fallback path share the entire encoding, output, retention, and rule pipeline.
 */
interface CallSource {
    /** The most recently computed metadata. Safe to read from other threads. */
    val callMetadata: CallMetadata

    /** The phone numbers taking part in the call, used for evaluating record rules. */
    val phoneNumbers: Set<PhoneNumber>

    /** The package that owns the call. Only used for diagnostics. */
    val packageName: String

    /**
     * Overrides the user's configured audio source when non-null.
     *
     * The fallback path must use an unprivileged source because the `VOICE_*` sources require
     * [Manifest.permission.CAPTURE_AUDIO_OUTPUT], which is only held by a system app.
     */
    val forcedAudioSource: AudioSource?

    /** Human-readable description for log messages. */
    val description: String

    /**
     * Recompute [callMetadata].
     *
     * @param allowBlockingCalls Whether synchronous contact/call log lookups are permitted. Only
     * true for the final update, after the recording has stopped.
     */
    fun update(allowBlockingCalls: Boolean)
}

/**
 * A call reported by the telephony framework via [android.telecom.InCallService].
 *
 * This requires BCR to be installed as a system app and provides the full, unmodified upstream
 * behavior, including conference call handling and true call audio capture.
 */
class TelecomCallSource(
    context: Context,
    private val parentCall: Call,
) : CallSource {
    private val collector = CallMetadataCollector(context, parentCall)

    override val callMetadata: CallMetadata
        get() = collector.callMetadata

    override val phoneNumbers: Set<PhoneNumber>
        get() = hashSetOf<PhoneNumber>().apply {
            if (parentCall.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) {
                for (childCall in parentCall.children) {
                    childCall.details?.phoneNumber?.let { add(it) }
                }
            } else {
                parentCall.details?.phoneNumber?.let { add(it) }
            }
        }

    override val packageName: String
        get() = parentCall.details.accountHandle.componentName.packageName

    /** The privileged path honors whatever the user picked in the output format screen. */
    override val forcedAudioSource: AudioSource? = null

    override val description: String
        get() = parentCall.toString()

    override fun update(allowBlockingCalls: Boolean) {
        collector.update(allowBlockingCalls)
    }

    /** Forward updated call details from [android.telecom.Call.Callback]. */
    fun updateCallDetails(call: Call, details: Call.Details) {
        collector.updateCallDetails(call, details)
    }
}

/**
 * A call detected without system app permissions.
 *
 * The number and direction come from [com.chiller3.bcr.fallback.FallbackCallScreeningService] when
 * the user has granted the call screening role, or are simply unknown when only the telephony call
 * state was observed. Conference calls and SIM slot reporting are not supported here because the
 * required details are not exposed to unprivileged apps.
 */
class FallbackCallSource(
    private val context: Context,
    private val number: PhoneNumber?,
    private val direction: CallDirection?,
    private val timestamp: ZonedDateTime,
    private val audioSource: AudioSource,
) : CallSource {
    private var cached: CallMetadata = computeMetadata(false)

    override val callMetadata: CallMetadata
        get() = synchronized(this) { cached }

    override val phoneNumbers: Set<PhoneNumber>
        get() = number?.let { setOf(it) } ?: emptySet()

    override val packageName: String = PHONE_PACKAGE

    override val forcedAudioSource: AudioSource = audioSource

    override val description: String
        get() = "fallback call (direction=$direction, hasNumber=${number != null})"

    private fun lookupContactName(allowBlockingCalls: Boolean): String? {
        if (!allowBlockingCalls || number == null) {
            return null
        }

        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permissions not granted for looking up contacts")
            return null
        }

        return try {
            withContactsByPhoneNumber(context, number) { it.firstOrNull() }?.displayName
        } catch (e: Exception) {
            Log.d(TAG, "Failed to look up contact", e)
            null
        }
    }

    private fun computeMetadata(allowBlockingCalls: Boolean): CallMetadata = CallMetadata(
        timestamp = timestamp,
        packageName = PHONE_PACKAGE,
        direction = direction,
        // Unprivileged apps cannot reliably attribute a call to a SIM slot, so the filename
        // template's {sim_slot} placeholder is simply left empty.
        simCount = null,
        simSlot = null,
        callLogName = null,
        calls = listOf(
            CallPartyDetails(
                phoneNumber = number,
                callerName = null,
                contactName = lookupContactName(allowBlockingCalls),
            ),
        ),
    )

    override fun update(allowBlockingCalls: Boolean) {
        val metadata = computeMetadata(allowBlockingCalls)
        synchronized(this) {
            cached = metadata
        }
    }

    companion object {
        private val TAG = FallbackCallSource::class.java.simpleName

        private const val PHONE_PACKAGE = "com.android.phone"
    }
}
