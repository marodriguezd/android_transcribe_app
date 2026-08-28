package dev.notune.transcribe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Process;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated Android AudioRecord bridge for Bluetooth/communication routing.
 *
 * <p>Configures MediaRecorder.AudioSource.VOICE_COMMUNICATION at 16000 Hz mono 16-bit PCM,
 * binds directly to preferred Bluetooth SCO/BLE devices, and delivers zero-copy direct ByteBuffers.</p>
 */
@SuppressLint("MissingPermission")
public class AudioRecordBridge {
    private static final String TAG = "AudioRecordBridge";

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int CHUNK_SIZE_BYTES = 3200; // 100ms at 16kHz 16-bit mono

    public interface Callback {
        void onAudioChunk(ByteBuffer directBuffer, int bytesRead);
        void onAudioLevel(float level);
        void onError(String message);
    }

    private AudioRecord audioRecord;
    private Thread captureThread;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private ByteBuffer directBuffer;

    public synchronized boolean start(Context context, String micPreference, Callback callback) {
        if (isRecording.get()) {
            stop();
        }

        try {
            AudioDeviceManager.acquireMicrophone(context, micPreference);
            audioRecord = AudioDeviceManager.createAudioRecord(context, micPreference);
            if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord");
                if (callback != null) callback.onError("Failed to initialize AudioRecord");
                AudioDeviceManager.releaseMicrophone(context);
                return false;
            }

            directBuffer = ByteBuffer.allocateDirect(CHUNK_SIZE_BYTES).order(ByteOrder.nativeOrder());
            audioRecord.startRecording();
            isRecording.set(true);

            captureThread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                while (isRecording.get()) {
                    directBuffer.clear();
                    int read = audioRecord.read(directBuffer, CHUNK_SIZE_BYTES);
                    if (read > 0) {
                        directBuffer.position(0);
                        directBuffer.limit(read);

                        float rms = calculateRms(directBuffer, read);
                        if (callback != null) {
                            callback.onAudioLevel(rms);
                            callback.onAudioChunk(directBuffer, read);
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read error: " + read);
                        if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                            break;
                        }
                    }
                }
            }, "AudioRecordCaptureThread");
            captureThread.start();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Error starting AudioRecord capture", t);
            if (callback != null) callback.onError(t.getMessage());
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        isRecording.set(false);
        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Throwable ignored) {}
            try {
                audioRecord.release();
            } catch (Throwable ignored) {}
            audioRecord = null;
        }
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public static float calculateRms(ByteBuffer buffer, int bytesRead) {
        if (buffer == null || bytesRead < 2) return 0f;
        int samples = bytesRead / 2;
        double sum = 0.0;
        int oldPos = buffer.position();
        buffer.position(0);
        for (int i = 0; i < samples; i++) {
            short s = buffer.getShort();
            double norm = s / 32768.0;
            sum += norm * norm;
        }
        buffer.position(oldPos);
        double mean = sum / samples;
        double rms = Math.sqrt(mean);
        return (float) Math.min(1.0, rms * 5.0);
    }
}
