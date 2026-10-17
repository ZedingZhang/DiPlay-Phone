package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real encrypted RTP is delivered to a sink shared by successive AirPlay sessions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarPlayAudioSessionOwnershipTest {
    private class RecordingSink : MediaSink {
        val audioStarted = CopyOnWriteArrayList<Int>()
        val audioStopped = CopyOnWriteArrayList<AudioStreamId>()
        val microphoneStarted = CopyOnWriteArrayList<MicrophoneConfig>()
        val microphoneStopped = CopyOnWriteArrayList<AudioStreamId>()
        val samples = LinkedBlockingQueue<Int>()
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { audioStarted += firstSample }
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { samples.offer(sample) }
        override fun onAudioStopped(id: AudioStreamId) { audioStopped += id }
        override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { microphoneStarted += config }
        override fun onMicrophoneStopped(id: AudioStreamId) { microphoneStopped += id }
    }

    @Test fun closingOldMediaSessionCannotStopItsReplacementButClosingCurrentSessionDoes() {
        checkReplacement("media", closeOldSession = true)
    }

    @Test fun oldCallTeardownCannotStopTheReplacementMicrophoneButCurrentCloseDoes() {
        checkReplacement("telephony", closeOldSession = false)
    }

    @Test fun closingOldCallSessionCannotRestoreTheReplacementCallRoute() {
        checkReplacement("telephony", closeOldSession = true)
    }

    @Test fun latePacketsFromAnOldSessionCannotReachOrRestartTheReplacementSink() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val first = session(1)
        val current = session(2)
        try {
            val firstPort = setup(engine, first, "telephony")
            val currentPort = setup(engine, current, "telephony")
            send(current, currentPort, 200)
            assertEquals(200, sink.samples.poll(5, TimeUnit.SECONDS))
            send(first, firstPort, 100) // Its first packet arrives only after replacement started.
            assertNull("late old audio reached the shared sink", sink.samples.poll(200, TimeUnit.MILLISECONDS))
            send(current, currentPort, 201)
            assertEquals(201, sink.samples.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(200), sink.audioStarted)
            assertEquals(1, sink.microphoneStarted.size)
        } finally {
            engine.onSessionClosed(first)
            engine.onSessionClosed(current)
            first.close()
            current.close()
        }
    }

    @Test fun sameSessionReconfigurationStopsTheOldGenerationAndFinalCloseStopsTheNewOne() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val session = session(1)
        val id = AudioStreamId(100, "telephony")
        try {
            send(session, setup(engine, session, "telephony"), 100)
            assertEquals(100, sink.samples.poll(5, TimeUnit.SECONDS))
            val replacementPort = setup(engine, session, "telephony")
            assertEquals(listOf(id), sink.audioStopped)
            assertEquals(listOf(id), sink.microphoneStopped)
            send(session, replacementPort, 200)
            assertEquals(200, sink.samples.poll(5, TimeUnit.SECONDS))
            assertEquals(2, sink.microphoneStarted.size)
            engine.onSessionClosed(session)
            assertEquals(listOf(id, id), sink.audioStopped)
            assertEquals(listOf(id, id), sink.microphoneStopped)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun checkReplacement(audioType: String, closeOldSession: Boolean) {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val first = session(1)
        val current = session(2)
        val id = AudioStreamId(100, audioType)
        try {
            send(first, setup(engine, first, audioType), 100)
            assertEquals(100, sink.samples.poll(5, TimeUnit.SECONDS))
            sink.audioStopped.clear()
            val currentPort = setup(engine, current, audioType)
            send(current, currentPort, 200)
            assertEquals(200, sink.samples.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(id), sink.audioStopped) // Replacement retires the first owner.
            val micStops = sink.microphoneStopped.toList()
            if (audioType == "telephony") {
                assertEquals(2, sink.microphoneStarted.size)
                assertEquals(listOf(id), micStops)
            }

            if (closeOldSession) engine.onSessionClosed(first) else engine.onTeardown(first, 100)
            assertEquals("old session stopped replacement audio", listOf(id), sink.audioStopped)
            assertEquals("old session stopped replacement microphone", micStops, sink.microphoneStopped)
            send(current, currentPort, 201)
            assertEquals(201, sink.samples.poll(5, TimeUnit.SECONDS))

            engine.onSessionClosed(current)
            assertEquals(listOf(id, id), sink.audioStopped)
            if (audioType == "telephony") assertEquals(listOf(id, id), sink.microphoneStopped)
        } finally {
            engine.onSessionClosed(first)
            engine.onSessionClosed(current)
            first.close()
            current.close()
        }
    }

    private fun setup(engine: CarPlayMediaEngine, session: AirPlaySession, audioType: String): Int =
        requireNotNull(engine.onAudio(session, 100, mapOf(
            "audioType" to audioType, "audioFormat" to 0x8000L,
            "streamConnectionID" to 42L, "dataPort" to 9,
        )))["dataPort"] as Int

    private fun send(session: AirPlaySession, port: Int, sample: Int) {
        val key = AirPlayCrypto.hkdfSha512(requireNotNull(session.sharedSecret),
            "DataStream-Salt42".toByteArray(), "DataStream-Output-Encryption-Key".toByteArray(), 32)
        val bytes = MicrophonePacketizer.sealPacket(key, 100, MicrophoneCounters(timestamp = sample),
            byteArrayOf(0, 0, 0, 0), 352)
        DatagramSocket().use { socket ->
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
        }
    }

    private fun session(secretByte: Int): AirPlaySession {
        val socket = object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 7000)
        }
        return AirPlaySession(socket,
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
        ).also { session ->
            session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
                .set(session.pairVerify, ByteArray(32) { secretByte.toByte() })
        }
    }
}
