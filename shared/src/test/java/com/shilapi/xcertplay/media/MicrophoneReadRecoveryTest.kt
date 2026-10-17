package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class MicrophoneReadRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = AudioStreamId(100, "telephony")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        manager.mode = AudioManager.MODE_RINGTONE
        sink = AndroidMediaSink(context = context, callSpeakerphone = { true }, onAudioDiagnostic = diagnostics::add)
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun aTransientReadErrorRestartsTheRecorderAndKeepsTheCallRoute() {
        val reads = AtomicInteger()
        val recovered = CountDownLatch(1)
        source {
            if (reads.getAndIncrement() == 0) AudioRecord.ERROR_INVALID_OPERATION
            else { recovered.countDown(); 0 }
        }
        startCall()
        assertTrue(recovered.await(5, TimeUnit.SECONDS))
        assertEquals(1, diagnostics.count { it.startsWith("Microphone: recording restarted") })
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        @Suppress("DEPRECATION")
        assertTrue(manager.isSpeakerphoneOn)
        sink.onMicrophoneStopped(id)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
    }

    @Test fun repeatedReadFailureStopsAfterThreeRetriesAndRestoresTheRoute() {
        val reads = AtomicInteger()
        source { reads.incrementAndGet(); AudioRecord.ERROR_INVALID_OPERATION }
        startCall()
        awaitRouteRestore()
        assertEquals(4, reads.get())
        assertEquals(3, diagnostics.count { it.startsWith("Microphone: recording restarted") })
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
    }

    @Test fun aDeadRecorderDoesNotRetryAndRestoresTheRoute() {
        val reads = AtomicInteger()
        source { reads.incrementAndGet(); AudioRecord.ERROR_DEAD_OBJECT }
        startCall()
        awaitRouteRestore()
        assertEquals(1, reads.get())
        assertTrue(diagnostics.none { it.startsWith("Microphone: recording restarted") })
        @Suppress("DEPRECATION")
        assertFalse(manager.isSpeakerphoneOn)
    }

    @Test fun delayedOldWorkerCompletionCannotRestoreAReplacementCallAfterExplicitStop() {
        checkDelayedOldCompletion(stopOldCall = true)
    }

    @Test fun replacementCallRetiresAnOldModeWhoseWorkerHasAlreadyRemovedItself() {
        checkDelayedOldCompletion(stopOldCall = false)
    }

    private fun checkDelayedOldCompletion(stopOldCall: Boolean) {
        val uplinks = CompletionPauseMap()
        AndroidMediaSink::class.java.getDeclaredField("microphoneUplinks").apply { isAccessible = true }
            .set(sink, uplinks)
        val allowOldFailure = CountDownLatch(1)
        val oldRead = CountDownLatch(1)
        val replacementRead = CountDownLatch(1)
        val recorders = AtomicInteger()
        ShadowAudioRecord.setSourceProvider { _ ->
            val first = recorders.getAndIncrement() == 0
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    if (first) {
                        oldRead.countDown()
                        allowOldFailure.await(5, TimeUnit.SECONDS)
                        return AudioRecord.ERROR_DEAD_OBJECT
                    }
                    replacementRead.countDown()
                    Thread.sleep(5)
                    return 0
                }
            }
        }
        try {
            startCall()
            assertTrue(oldRead.await(5, TimeUnit.SECONDS))
            uplinks.oldOwner = requireNotNull(uplinks[id])
            val oldWorker = MicrophoneUplink::class.java.getDeclaredField("thread").apply { isAccessible = true }
                .get(uplinks.oldOwner) as Thread
            allowOldFailure.countDown()
            assertTrue("old worker never reached completion", uplinks.oldRemoved.await(5, TimeUnit.SECONDS))
            assertTrue(uplinks.isEmpty())
            // Pause the real completion between map removal and route restoration. A new
            // call now owns exactly the same stream id while the old callback is still pending.
            if (stopOldCall) sink.onMicrophoneStopped(id)
            startCall()
            assertTrue(replacementRead.await(5, TimeUnit.SECONDS))
            assertNotSame(uplinks.oldOwner, uplinks[id])
            uplinks.continueRestore.countDown()
            oldWorker.join(5_000)
            assertFalse("old completion did not finish", oldWorker.isAlive)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            @Suppress("DEPRECATION")
            assertTrue(manager.isSpeakerphoneOn)

            sink.onMicrophoneStopped(id)
            assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
            @Suppress("DEPRECATION")
            assertFalse(manager.isSpeakerphoneOn)
        } finally {
            allowOldFailure.countDown()
            uplinks.continueRestore.countDown()
        }
    }

    private class CompletionPauseMap : ConcurrentHashMap<AudioStreamId, MicrophoneUplink>() {
        @Volatile var oldOwner: MicrophoneUplink? = null
        val oldRemoved = CountDownLatch(1)
        val continueRestore = CountDownLatch(1)

        override fun remove(key: AudioStreamId, value: MicrophoneUplink): Boolean {
            val removed = super.remove(key, value)
            if (removed && value === oldOwner) {
                oldRemoved.countDown()
                continueRestore.await(5, TimeUnit.SECONDS)
            }
            return removed
        }
    }

    private fun source(read: () -> Int) {
        ShadowAudioRecord.setSourceProvider { _ ->
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return read()
                }
            }
        }
    }

    private fun startCall() = sink.onMicrophoneStarted(id, MicrophoneConfig("telephony", 16_000, 1, 100, 20,
        InetAddress.getLoopbackAddress(), 9, ByteArray(32)))

    private fun awaitRouteRestore() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (manager.mode != AudioManager.MODE_RINGTONE && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(diagnostics.toString(), AudioManager.MODE_RINGTONE, manager.mode)
    }
}
