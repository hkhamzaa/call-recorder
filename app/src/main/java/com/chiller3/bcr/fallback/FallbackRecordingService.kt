/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.bcr.fallback

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.StringRes
import com.chiller3.bcr.Notifications
import com.chiller3.bcr.Permissions
import com.chiller3.bcr.Preferences
import com.chiller3.bcr.R
import com.chiller3.bcr.RecorderThread
import com.chiller3.bcr.extension.threadIdCompat
import com.chiller3.bcr.output.FallbackCallSource
import com.chiller3.bcr.output.OutputFile
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Records a call using the microphone when BCR is not installed as a system app.
 *
 * ## Why this service is started from the call screening callback
 *
 * Android 12 forbids starting a foreground service from a background execution context, which
 * includes [android.content.BroadcastReceiver.onReceive]. Starting this service from a
 * `PHONE_STATE` broadcast therefore threw [android.app.ForegroundServiceStartNotAllowedException]
 * on every call. The service is now started from
 * [FallbackCallScreeningService.onScreenCall], where the app is handling a call on behalf of the
 * telecom framework, and from [CallRecorderAccessibilityService], which is a system-bound service.
 *
 * ## Why the service observes call state itself
 *
 * The call screening callback fires when a call *arrives*, not when it is *answered*. The service
 * goes into the foreground immediately (which is what Android requires), then watches the telephony
 * call state itself and only opens the microphone once the call actually goes off-hook. Because the
 * service is already in the foreground by then, no further background-start restriction applies.
 *
 * See [FallbackMode] for why the resulting audio only captures the remote party via the earpiece.
 */
class FallbackRecordingService : Service(), RecorderThread.OnRecordingCompletedListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: Preferences
    private lateinit var notifications: Notifications
    private lateinit var telephonyManager: TelephonyManager

    private val notificationId by lazy { prefs.nextNotificationId }

    /** Whether [startForeground] has succeeded. The service must not do anything before this. */
    private var isForeground = false

    /** Whether we are already watching the telephony call state. */
    private var isMonitoring = false

    /** The single in-flight recorder, if any. Only touched on the main thread. */
    private var recorder: RecorderThread? = null

    /** Guards against third party apps driving the notification actions. */
    private val token = Random.nextBytes(128)

    private var lastNotificationState: NotificationState? = null

    private var telephonyCallback: Any? = null

    /**
     * Gives up if the call is never answered, so a declined or missed call does not leave a
     * foreground service (and its notification) running forever.
     */
    private val giveUpRunnable = Runnable {
        if (recorder == null) {
            Log.i(TAG, "Call was never answered; stopping")
            stopSelf()
        }
    }

    private data class NotificationState(
        @StringRes val titleResId: Int,
        val message: String?,
        val actionsResIds: List<Int>,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(NotificationManager::class.java)
        prefs = Preferences(this)
        notifications = Notifications(this)
        telephonyManager = getSystemService(TelephonyManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() to be called within a few seconds of the service
        // starting. Nothing else may happen first: if this fails, the service must die immediately
        // or the system will kill the entire process with
        // ForegroundServiceDidNotStartInTimeException.
        if (!enterForeground(R.string.notification_recording_initializing, null, emptyList())) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_MONITOR_CALL -> startMonitoring()
            ACTION_STOP -> {
                stopRecordingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE, ACTION_RESUME -> {
                if (hasValidToken(intent)) {
                    recorder?.isPaused = intent.action == ACTION_PAUSE
                }
            }
            ACTION_RESTORE, ACTION_DELETE -> {
                if (hasValidToken(intent)) {
                    recorder?.keepRecording = if (intent.action == ACTION_RESTORE) {
                        RecorderThread.KeepState.KEEP
                    } else {
                        RecorderThread.KeepState.DISCARD
                    }
                }
            }
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }

        updateNotification()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUpRunnable)
        stopMonitoring()
        super.onDestroy()
    }

    private fun hasValidToken(intent: Intent): Boolean {
        val received = intent.getByteArrayExtra(EXTRA_TOKEN)
        if (!received.contentEquals(token)) {
            Log.w(TAG, "Ignoring intent with invalid token")
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Foreground state
    // ---------------------------------------------------------------------------------------

    /**
     * Enter (or update) the foreground state.
     *
     * @return Whether the service is in the foreground. When this returns false, the caller MUST
     * stop the service. Continuing to run without being in the foreground is what causes Android to
     * kill the process.
     */
    private fun enterForeground(
        @StringRes titleResId: Int,
        message: String?,
        actions: List<Pair<Int, Intent>>,
    ): Boolean {
        val notification = try {
            notifications.createPersistentNotification(titleResId, message, actions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build notification", e)
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(notificationId, notification)
            }

            isForeground = true
            true
        } catch (e: Exception) {
            // On Android 12+ this throws ForegroundServiceStartNotAllowedException when the system
            // considers the app to be in the background with no applicable exemption. On Android 14+
            // it can also throw SecurityException if the microphone foreground service type is not
            // permitted right now. Either way the service cannot legally continue.
            Log.e(TAG, "Failed to enter the foreground; giving up on this call", e)
            isForeground = false
            false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Call state monitoring
    // ---------------------------------------------------------------------------------------

    private fun startMonitoring() {
        if (isMonitoring) {
            Log.v(TAG, "Already monitoring the call state")
            return
        }

        if (!prefs.isCallRecordingEnabled) {
            Log.v(TAG, "Call recording is disabled")
            stopSelf()
            return
        }

        if (!Permissions.haveRequired(this)) {
            Log.v(TAG, "Required permissions have not been granted")
            stopSelf()
            return
        }

        if (FallbackMode.isPrivileged(this)) {
            // RecorderInCallService owns recording on a system app install.
            Log.v(TAG, "Running as a system app; fallback recorder not needed")
            stopSelf()
            return
        }

        isMonitoring = true
        handler.postDelayed(giveUpRunnable, UNANSWERED_TIMEOUT_MILLIS)

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) {
            // Without READ_PHONE_STATE we cannot tell when the call is answered, so the best we can
            // do is start recording right away and rely on the screening callback having fired for
            // a real call.
            Log.w(TAG, "READ_PHONE_STATE not granted; recording immediately")
            handler.removeCallbacks(giveUpRunnable)
            startRecording()
            return
        }

        registerCallStateListener()

        // Handle the case where the call is already off-hook by the time we get here.
        onCallStateChanged(currentCallState())
    }

    @SuppressLint("MissingPermission")
    private fun currentCallState(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.callStateForSubscription
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.callState
        }

    @SuppressLint("MissingPermission")
    private fun registerCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        this@FallbackRecordingService.onCallStateChanged(state)
                    }
                }
                telephonyCallback = callback
                telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        this@FallbackRecordingService.onCallStateChanged(state)
                    }
                }
                telephonyCallback = listener
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register call state listener; recording immediately", e)
            handler.removeCallbacks(giveUpRunnable)
            startRecording()
        }
    }

    private fun stopMonitoring() {
        val callback = telephonyCallback ?: return
        telephonyCallback = null
        isMonitoring = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(callback as TelephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(
                    callback as PhoneStateListener,
                    PhoneStateListener.LISTEN_NONE,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister call state listener", e)
        }
    }

    private fun onCallStateChanged(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                handler.removeCallbacks(giveUpRunnable)
                startRecording()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isMonitoring) {
                    Log.d(TAG, "Call ended")
                    stopRecordingAndSelf()
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.v(TAG, "Phone is ringing; waiting for it to be answered")
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------------

    private fun startRecording() {
        if (recorder != null) {
            return
        }

        if (!isForeground) {
            Log.e(TAG, "Refusing to record without being in the foreground")
            stopSelf()
            return
        }

        val pending = FallbackMode.takePendingCall()
        val callSource = FallbackCallSource(
            context = this,
            number = pending?.number,
            direction = pending?.direction,
            timestamp = pending?.timestamp ?: ZonedDateTime.now(),
            audioSource = prefs.fallbackAudioSource,
        )

        val thread = try {
            RecorderThread(this, this, callSource)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recorder thread", e)
            notifications.notifyRecordingFailure(e.message, null, emptyList())
            stopSelf()
            return
        }

        recorder = thread
        thread.start()
        updateNotification()
    }

    private fun stopRecordingAndSelf() {
        FallbackMode.clearPendingCall()
        handler.removeCallbacks(giveUpRunnable)
        stopMonitoring()

        val thread = recorder
        if (thread != null) {
            // onRecordingCompleted() stops the service once the thread has finished writing.
            thread.cancel()
        } else {
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------

    private fun createActionIntent(action: String): Intent =
        Intent(this, FallbackRecordingService::class.java).apply {
            this.action = action
            data = Uri.fromParts("notification", notificationId.toString(), null)
            putExtra(EXTRA_TOKEN, token)
        }

    private fun updateNotification() {
        if (!isForeground) {
            return
        }

        val thread = recorder
        if (thread == null) {
            val state = NotificationState(
                R.string.notification_recording_initializing,
                getString(R.string.notification_message_fallback_waiting),
                emptyList(),
            )
            if (state != lastNotificationState) {
                lastNotificationState = state
                if (!enterForeground(state.titleResId, state.message, emptyList())) {
                    stopSelf()
                }
            }
            return
        }

        val titleResId: Int
        val actionResIds = mutableListOf<Int>()
        val actionIntents = mutableListOf<Intent>()
        val canShowDelete: Boolean

        when (thread.state) {
            RecorderThread.State.NOT_STARTED -> {
                titleResId = R.string.notification_recording_initializing
                canShowDelete = true
            }
            RecorderThread.State.RECORDING -> {
                if (thread.isPaused) {
                    titleResId = R.string.notification_recording_paused
                    actionResIds.add(R.string.notification_action_resume)
                    actionIntents.add(createActionIntent(ACTION_RESUME))
                } else {
                    titleResId = R.string.notification_recording_in_progress
                    actionResIds.add(R.string.notification_action_pause)
                    actionIntents.add(createActionIntent(ACTION_PAUSE))
                }
                canShowDelete = true
            }
            RecorderThread.State.FINALIZING, RecorderThread.State.COMPLETED -> {
                titleResId = R.string.notification_recording_finalizing
                canShowDelete = false
            }
        }

        val message = StringBuilder(thread.outputPath.unredacted)

        // Make it obvious in the notification itself that this is the degraded capture mode.
        message.append("\n\n")
        message.append(getString(R.string.notification_message_fallback_mode))

        if (canShowDelete) {
            thread.keepRecording?.let {
                when (it) {
                    RecorderThread.KeepState.KEEP -> {
                        actionResIds.add(R.string.notification_action_delete)
                        actionIntents.add(createActionIntent(ACTION_DELETE))
                    }
                    RecorderThread.KeepState.DISCARD -> {
                        message.append("\n\n")
                        message.append(getString(R.string.notification_message_delete_at_end))
                        actionResIds.add(R.string.notification_action_restore)
                        actionIntents.add(createActionIntent(ACTION_RESTORE))
                    }
                    RecorderThread.KeepState.DISCARD_TOO_SHORT -> {
                        val minDuration = prefs.minDuration

                        message.append("\n\n")
                        message.append(resources.getQuantityString(
                            R.plurals.notification_message_delete_at_end_too_short,
                            minDuration,
                            minDuration,
                        ))
                        actionResIds.add(R.string.notification_action_restore)
                        actionIntents.add(createActionIntent(ACTION_RESTORE))
                    }
                }
            }
        }

        val state = NotificationState(titleResId, message.toString(), actionResIds)
        if (state == lastNotificationState) {
            // Android rate limits notification updates to 10 per second.
            return
        }
        lastNotificationState = state

        if (!enterForeground(titleResId, state.message, actionResIds.zip(actionIntents))) {
            stopSelf()
        }
    }

    override fun onRecordingStateChanged(thread: RecorderThread) {
        handler.post {
            updateNotification()
        }
    }

    override fun onRecordingCompleted(
        thread: RecorderThread,
        file: OutputFile?,
        additionalFiles: List<OutputFile>,
        status: RecorderThread.Status,
    ) {
        Log.i(TAG, "Recording completed: ${thread.threadIdCompat}: ${file?.redacted}: $status")

        handler.post {
            if (recorder === thread) {
                recorder = null
            }

            val firstMoveError = file?.moveError
                ?: additionalFiles.firstNotNullOfOrNull { it.moveError }
            if (firstMoveError != null) {
                notifications.notifyMoveFailure(firstMoveError.localizedMessage)
            }

            when (status) {
                RecorderThread.Status.Succeeded -> {
                    notifications.notifyRecordingSuccess(file!!, additionalFiles)
                }
                is RecorderThread.Status.Failed -> {
                    notifications.notifyRecordingFailure(
                        status.exception?.localizedMessage, file, additionalFiles)
                }
                is RecorderThread.Status.Discarded -> {
                    if (status.reason is RecorderThread.DiscardReason.Silence) {
                        notifications.notifyRecordingPureSilence(status.reason.callPackage)
                    }
                }
                RecorderThread.Status.Cancelled -> {}
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        private val TAG = FallbackRecordingService::class.java.simpleName

        private val ACTION_MONITOR_CALL =
            "${FallbackRecordingService::class.java.canonicalName}.monitor_call"
        private val ACTION_STOP =
            "${FallbackRecordingService::class.java.canonicalName}.stop"
        private val ACTION_PAUSE =
            "${FallbackRecordingService::class.java.canonicalName}.pause"
        private val ACTION_RESUME =
            "${FallbackRecordingService::class.java.canonicalName}.resume"
        private val ACTION_RESTORE =
            "${FallbackRecordingService::class.java.canonicalName}.restore"
        private val ACTION_DELETE =
            "${FallbackRecordingService::class.java.canonicalName}.delete"

        private const val EXTRA_TOKEN = "token"

        /** How long to wait for a ringing call to be answered before giving up. */
        private const val UNANSWERED_TIMEOUT_MILLIS = 90_000L

        /**
         * Start monitoring the current call.
         *
         * This MUST only be called from a context that Android allows to start a foreground
         * service: [FallbackCallScreeningService.onScreenCall], the accessibility service, or a
         * foreground activity. Calling it from a plain [android.content.BroadcastReceiver] throws
         * [android.app.ForegroundServiceStartNotAllowedException] on Android 12+.
         *
         * @return Whether the start request was accepted by the system.
         */
        fun monitorCall(context: Context): Boolean {
            val intent = Intent(context, FallbackRecordingService::class.java).apply {
                action = ACTION_MONITOR_CALL
            }

            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Not allowed to start the fallback recording service", e)
                false
            }
        }
    }
}
