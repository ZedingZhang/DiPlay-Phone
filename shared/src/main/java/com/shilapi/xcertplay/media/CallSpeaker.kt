package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Selects the call loudspeaker and restores the route that existed before this call. */
internal class CallSpeaker private constructor(private val undo: () -> Unit) {
    fun restore() {
        runCatching(undo)
    }

    companion object {
        /** A refused or unavailable speaker leaves the call on its existing route. */
        fun on(manager: AudioManager, sdk: Int = Build.VERSION.SDK_INT): CallSpeaker? = runCatching {
            if (sdk >= Build.VERSION_CODES.S) {
                val speaker = manager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return null
                val before = manager.communicationDevice
                val restore = {
                    if (before == null) manager.clearCommunicationDevice()
                    else manager.setCommunicationDevice(before).let { Unit }
                }
                try {
                    if (!manager.setCommunicationDevice(speaker)) {
                        runCatching(restore)
                        return null
                    }
                } catch (error: RuntimeException) {
                    runCatching(restore)
                    throw error
                }
                CallSpeaker(restore)
            } else {
                @Suppress("DEPRECATION")
                val before = manager.isSpeakerphoneOn
                val restore = {
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = before
                }
                try {
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = true
                } catch (error: RuntimeException) {
                    runCatching(restore)
                    throw error
                }
                CallSpeaker(restore)
            }
        }.getOrNull()
    }
}
