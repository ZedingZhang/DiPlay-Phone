package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class OpusAudioClockTest {
    @Test fun opusPcmStaysAt48KhzWhilePacketTimestampsUseTheNegotiatedClock() {
        for ((bits, rate) in listOf(0x10000000L to 16_000, 0x20000000L to 24_000, 0x40000000L to 48_000)) {
            val format = AudioStreamCodec.fromFormatBits(bits, 100, "telephony")
            assertEquals(AudioCodecKind.OPUS, format.codec)
            assertEquals(48_000, format.sampleRate)
            assertEquals(rate, format.rtpClockRate)
            val config = MicrophoneConfig("telephony", format.sampleRate, 1, 100, 20,
                InetAddress.getLoopbackAddress(), 9, ByteArray(32), AudioCodecKind.OPUS,
                opusClockRate = MicrophoneConfig.opusClockRate(bits))
            assertEquals(1_920, config.frameBytes)
            assertEquals(rate / 50, config.rtpSamplesPerPacket)
            val counters = MicrophoneCounters()
            repeat(50) {
                MicrophonePacketizer.sealPacket(config.key, config.payloadType, counters,
                    byteArrayOf(1, 2), config.rtpSamplesPerPacket)
            }
            assertEquals(rate, counters.timestamp)
        }
    }

    @Test fun pcmRetainsItsNativeClockAndFrameSize() {
        val config = MicrophoneConfig("telephony", 16_000, 1, 100, 20,
            InetAddress.getLoopbackAddress(), 9, ByteArray(32))
        assertEquals(320, config.samplesPerPacket)
        assertEquals(640, config.frameBytes)
        assertEquals(config.samplesPerPacket, config.rtpSamplesPerPacket)
    }
}
