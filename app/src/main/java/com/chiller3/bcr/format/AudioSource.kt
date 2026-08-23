/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.format

import android.media.MediaRecorder
import com.chiller3.bcr.R

enum class AudioSource {
    VOICE_CALL,
    VOICE_UPLINK_DOWNLINK,
    VOICE_UPLINK,
    VOICE_DOWNLINK,

    // Unprivileged sources, only used by the non-root fallback path. These capture the device
    // microphone, not the call audio stream. See [isPrivileged].
    MIC,
    VOICE_RECOGNITION,
    VOICE_COMMUNICATION;

    val sources: Array<Int>
        get() = when (this) {
            VOICE_CALL -> arrayOf(MediaRecorder.AudioSource.VOICE_CALL)
            VOICE_UPLINK_DOWNLINK -> arrayOf(
                MediaRecorder.AudioSource.VOICE_UPLINK,
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
            )
            VOICE_UPLINK -> arrayOf(MediaRecorder.AudioSource.VOICE_UPLINK)
            VOICE_DOWNLINK -> arrayOf(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            MIC -> arrayOf(MediaRecorder.AudioSource.MIC)
            VOICE_RECOGNITION -> arrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            VOICE_COMMUNICATION -> arrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }

    val isStereo: Boolean
        get() = this == VOICE_UPLINK_DOWNLINK

    /**
     * Whether this source requires [android.Manifest.permission.CAPTURE_AUDIO_OUTPUT], which is
     * only granted to a system app. Unprivileged sources record the microphone instead of the call
     * audio stream.
     */
    val isPrivileged: Boolean
        get() = when (this) {
            VOICE_CALL, VOICE_UPLINK_DOWNLINK, VOICE_UPLINK, VOICE_DOWNLINK -> true
            MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION -> false
        }

    val nameResId: Int
        get() = when (this) {
            VOICE_CALL -> R.string.audio_source_voice_call
            VOICE_UPLINK_DOWNLINK -> R.string.audio_source_voice_uplink_downlink
            VOICE_UPLINK -> R.string.audio_source_voice_uplink
            VOICE_DOWNLINK -> R.string.audio_source_voice_downlink
            MIC -> R.string.audio_source_mic
            VOICE_RECOGNITION -> R.string.audio_source_voice_recognition
            VOICE_COMMUNICATION -> R.string.audio_source_voice_communication
        }

    companion object {
        fun getByName(name: String): AudioSource? = AudioSource.entries.find { it.name == name }

        /** Sources that an app without system app permissions is allowed to open. */
        val UNPRIVILEGED: List<AudioSource> = entries.filter { !it.isPrivileged }

        /** Sources that require system app permissions. */
        val PRIVILEGED: List<AudioSource> = entries.filter { it.isPrivileged }
    }
}
