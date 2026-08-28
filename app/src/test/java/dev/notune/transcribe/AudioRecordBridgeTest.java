package dev.notune.transcribe;

import android.media.AudioFormat;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM test suite for {@link AudioRecordBridge}.
 * Tests buffer constraints, RMS calculation mathematics, position preservation,
 * and null-safety contracts.
 */
public class AudioRecordBridgeTest {

    @Test
    public void testAudioConstants() {
        assertEquals(16000, AudioRecordBridge.SAMPLE_RATE);
        assertEquals(AudioFormat.CHANNEL_IN_MONO, AudioRecordBridge.CHANNEL_CONFIG);
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, AudioRecordBridge.AUDIO_FORMAT);
        assertEquals(3200, AudioRecordBridge.CHUNK_SIZE_BYTES); // 100ms at 16kHz 16-bit mono
    }

    @Test
    public void testInitialStateNotRecording() {
        AudioRecordBridge bridge = new AudioRecordBridge();
        assertFalse(bridge.isRecording());
    }

    @Test
    public void testCalculateRmsNullAndEmpty() {
        assertEquals(0f, AudioRecordBridge.calculateRms(null, 0), 0.0001f);
        assertEquals(0f, AudioRecordBridge.calculateRms(null, 3200), 0.0001f);

        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        assertEquals(0f, AudioRecordBridge.calculateRms(buf, 0), 0.0001f);
        assertEquals(0f, AudioRecordBridge.calculateRms(buf, 1), 0.0001f);
    }

    @Test
    public void testCalculateRmsSilence() {
        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        // All zeros
        float rms = AudioRecordBridge.calculateRms(buf, 3200);
        assertEquals(0f, rms, 0.0001f);
    }

    @Test
    public void testCalculateRmsFullScaleSquareWave() {
        int bytes = 3200;
        ByteBuffer buf = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < bytes / 2; i++) {
            buf.putShort((short) 32767);
        }
        buf.position(0);

        float rms = AudioRecordBridge.calculateRms(buf, bytes);
        // Peak normalized amplitude ~ 1.0, scaled * 5.0 clamped to 1.0
        assertEquals(1.0f, rms, 0.0001f);
    }

    @Test
    public void testCalculateRmsPreservesBufferPosition() {
        int bytes = 1600;
        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < bytes / 2; i++) {
            buf.putShort((short) 1000);
        }
        buf.position(42);

        float rms = AudioRecordBridge.calculateRms(buf, bytes);
        assertTrue(rms > 0f);
        assertEquals(42, buf.position());
    }

    @Test
    public void testCalculateRmsScalingMonotonicity() {
        ByteBuffer quietBuf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 1600; i++) {
            quietBuf.putShort((short) 500);
        }
        quietBuf.position(0);

        ByteBuffer loudBuf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 1600; i++) {
            loudBuf.putShort((short) 5000);
        }
        loudBuf.position(0);

        float quietRms = AudioRecordBridge.calculateRms(quietBuf, 3200);
        float loudRms = AudioRecordBridge.calculateRms(loudBuf, 3200);

        assertTrue(loudRms > quietRms);
    }

    @Test
    public void testStopIdempotencyAndNullSafety() {
        AudioRecordBridge bridge = new AudioRecordBridge();
        // Multiple stop calls on unstarted bridge must be safe and idempotent
        bridge.stop();
        bridge.stop();
        assertFalse(bridge.isRecording());

        // start with null parameters should fail gracefully without unhandled crashes
        boolean result = bridge.start(null, null, null);
        assertFalse(result);
        assertFalse(bridge.isRecording());
        bridge.stop();
    }

    @Test
    public void testDirectByteBufferChunkAllocation() {
        ByteBuffer buf = ByteBuffer.allocateDirect(AudioRecordBridge.CHUNK_SIZE_BYTES).order(ByteOrder.nativeOrder());
        assertTrue(buf.isDirect());
        assertEquals(AudioRecordBridge.CHUNK_SIZE_BYTES, buf.capacity());
        assertEquals(3200, buf.remaining());
    }

    @Test
    public void testCalculateRmsPartialChunk() {
        // Test odd byte read count (e.g. 1599 bytes: 799 full samples)
        ByteBuffer buf = ByteBuffer.allocate(3200).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 800; i++) {
            buf.putShort((short) 1000);
        }
        buf.position(0);

        float rms = AudioRecordBridge.calculateRms(buf, 1599);
        assertTrue(rms > 0f);
        assertTrue(rms <= 1.0f);
    }
}
