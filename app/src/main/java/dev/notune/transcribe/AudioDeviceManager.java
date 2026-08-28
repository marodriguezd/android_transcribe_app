package dev.notune.transcribe;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Intelligent audio input routing manager (FUTO Keyboard style).
 *
 * <p>Handles seamless switching between Bluetooth SCO headsets, Bluetooth LE Audio,
 * USB external microphones, wired headsets, and built-in microphones.
 * Provides pre-warming handshake for zero-latency capture and dedicated AudioRecord creation.</p>
 */
@SuppressLint({"MissingPermission", "NewApi", "InlinedApi"})
public final class AudioDeviceManager {
    private static final String TAG = "AudioDeviceManager";

    public static final String MIC_MODE_AUTO = "auto";
    public static final String MIC_MODE_BLUETOOTH_ONLY = "bluetooth";
    public static final String MIC_MODE_BUILTIN_ONLY = "builtin";

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static volatile boolean isRoutingActive = false;
    private static volatile boolean isPrewarmed = false;

    private AudioDeviceManager() {
        // Utility class
    }

    /**
     * Returns true if audio routing is currently active for recording.
     */
    public static boolean isRoutingActive() {
        return isRoutingActive;
    }

    /**
     * Returns true if the communication channel is currently pre-warmed.
     */
    public static boolean isPrewarmed() {
        return isPrewarmed;
    }

    /**
     * Pre-warms the Bluetooth communication channel in the background when the
     * keyboard window or floating bubble appears, so tapping "Record" begins
     * capturing immediately (0 ms latency) without clipping the speaker's first syllables.
     */
    public static synchronized void prewarmMicrophone(Context context, String micPreference) {
        if (context == null || isRoutingActive || isPrewarmed) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        try {
            if (MIC_MODE_BUILTIN_ONLY.equals(micPreference)) {
                // Builtin does not require pre-warming
                return;
            }

            if (isBluetoothConnected(context)) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                try {
                    am.setSpeakerphoneOn(false);
                } catch (Throwable ignored) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AudioDeviceInfo target = findTargetDevice(context, micPreference);
                    if (target != null) {
                        am.setCommunicationDevice(target);
                    }
                }

                try {
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                } catch (Throwable ignored) {}

                isPrewarmed = true;
                Log.d(TAG, "Bluetooth communication channel pre-warmed successfully");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error pre-warming microphone routing", t);
        }
    }

    /**
     * Cancels any pending pre-warmed communication channel if recording was not started.
     */
    public static synchronized void cancelPrewarm(Context context) {
        if (isPrewarmed && !isRoutingActive) {
            releaseMicrophone(context);
        }
    }

    /**
     * Acquires the best audio input device based on user preference and hardware state.
     * Must be called immediately before opening the audio capture stream.
     */
    public static synchronized void acquireMicrophone(Context context, String micPreference) {
        if (context == null) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        try {
            if (MIC_MODE_BUILTIN_ONLY.equals(micPreference)) {
                routeToBuiltinMic(am);
            } else if (MIC_MODE_BLUETOOTH_ONLY.equals(micPreference)) {
                routeToBluetoothMic(context, am);
            } else {
                // AUTO: Prefer Bluetooth / USB / Wired if connected, otherwise fallback to builtin
                routeAuto(context, am);
            }
            isRoutingActive = true;
            isPrewarmed = false;
        } catch (Throwable t) {
            Log.e(TAG, "Error acquiring microphone routing", t);
        }
    }

    /**
     * Releases communication device routing and restores normal audio mode.
     * Must be called immediately when speech recording ends.
     */
    public static synchronized void releaseMicrophone(Context context) {
        if (context == null) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                clearCommunicationDeviceApi31(am);
            }
            try {
                am.stopBluetoothSco();
            } catch (Throwable ignored) {}
            try {
                am.setBluetoothScoOn(false);
            } catch (Throwable ignored) {}
            try {
                am.setSpeakerphoneOn(false);
            } catch (Throwable ignored) {}
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Throwable t) {
            Log.e(TAG, "Error releasing microphone routing", t);
        } finally {
            isRoutingActive = false;
            isPrewarmed = false;
        }
    }

    /**
     * Resolves the target AudioDeviceInfo according to user preference and hardware connectivity.
     */
    public static AudioDeviceInfo findTargetDevice(Context context, String micPreference) {
        if (context == null || MIC_MODE_BUILTIN_ONLY.equals(micPreference)) {
            return null;
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                List<AudioDeviceInfo> commDevices = am.getAvailableCommunicationDevices();
                // 1. Bluetooth headsets
                for (AudioDeviceInfo d : commDevices) {
                    int type = d.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        type == AudioDeviceInfo.TYPE_HEARING_AID ||
                        type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                        return d;
                    }
                }
                // 2. USB / Wired if in AUTO mode
                if (MIC_MODE_AUTO.equals(micPreference)) {
                    for (AudioDeviceInfo d : commDevices) {
                        int type = d.getType();
                        if (type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                            return d;
                        }
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
                if (devices != null) {
                    for (AudioDeviceInfo d : devices) {
                        int type = d.getType();
                        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            type == AudioDeviceInfo.TYPE_HEARING_AID ||
                            type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                            return d;
                        }
                    }
                    if (MIC_MODE_AUTO.equals(micPreference)) {
                        for (AudioDeviceInfo d : devices) {
                            int type = d.getType();
                            if (type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                                type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                                type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                                return d;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error finding target audio device", t);
        }
        return null;
    }

    /**
     * Creates and configures a dedicated Android AudioRecord instance with
     * MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000 Hz, mono PCM 16-bit,
     * bound directly to target AudioDeviceInfo via setPreferredDevice().
     */
    public static AudioRecord createAudioRecord(Context context, String micPreference) {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuf <= 0) {
            minBuf = 3200 * 2;
        }
        int bufferSize = Math.max(minBuf, 3200 * 2);

        AudioRecord record = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build())
                    .setBufferSizeInBytes(bufferSize);
                record = builder.build();

                AudioDeviceInfo target = findTargetDevice(context, micPreference);
                if (target != null && record != null) {
                    record.setPreferredDevice(target);
                }
            } else {
                record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                );
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error creating AudioRecord with VOICE_COMMUNICATION", t);
        }

        // Fallback to standard MIC audio source if VOICE_COMMUNICATION failed to initialize
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            if (record != null) {
                try { record.release(); } catch (Throwable ignored) {}
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord.Builder builder = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                        .setBufferSizeInBytes(bufferSize);
                    record = builder.build();
                } else {
                    record = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    );
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error creating AudioRecord fallback with MIC source", t);
            }
        }

        return record;
    }

    /**
     * Returns true if audio is actively being captured (or pre-warmed) from a Bluetooth headset.
     */
    public static boolean isBluetoothCapturing(Context context) {
        if (!isRoutingActive && !isPrewarmed) return false;
        return isBluetoothConnected(context);
    }

    /**
     * Returns a human-friendly name of the active input device for diagnostics.
     */
    public static String getActiveInputDeviceName(Context context) {
        if (context == null) return "Unknown";
        try {
            List<String> connected = getConnectedInputDevices(context);
            if (!connected.isEmpty()) {
                // If bluetooth connected and routing active, return bluetooth device
                for (String dev : connected) {
                    if (dev.contains("Bluetooth")) {
                        return dev;
                    }
                }
                return connected.get(0);
            }
        } catch (Throwable ignored) {}
        return "📱 " + context.getString(R.string.mic_active_builtin);
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static void clearCommunicationDeviceApi31(AudioManager am) {
        try {
            am.clearCommunicationDevice();
        } catch (Throwable t) {
            Log.w(TAG, "Error clearing communication device on API 31+", t);
        }
    }

    private static void routeAuto(Context context, AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeAutoApi31(context, am);
        } else {
            // Android 8 - 11 legacy SCO activation
            if (isBluetoothConnected(context)) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                try {
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                } catch (Throwable t) {
                    Log.w(TAG, "Error activating Bluetooth SCO in routeAuto", t);
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static void routeAutoApi31(Context context, AudioManager am) {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            List<AudioDeviceInfo> commDevices = am.getAvailableCommunicationDevices();
            AudioDeviceInfo target = null;

            // 1. Check for Bluetooth Headset (SCO, BLE Headset, Hearing Aid, BLE Speaker)
            for (AudioDeviceInfo d : commDevices) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    target = d;
                    break;
                }
            }

            // 2. Check for USB or Wired Headset
            if (target == null) {
                for (AudioDeviceInfo d : commDevices) {
                    int type = d.getType();
                    if (type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                        target = d;
                        break;
                    }
                }
            }

            if (target != null) {
                boolean set = am.setCommunicationDevice(target);
                String name = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ? String.valueOf(target.getProductName()) : "device";
                Log.d(TAG, "Auto routed to communication device: " + name + " (success=" + set + ")");
            } else if (isBluetoothConnected(context)) {
                // Fallback for Bluetooth devices not yet in commDevices
                try {
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in routeAutoApi31", t);
        }
    }

    private static void routeToBluetoothMic(Context context, AudioManager am) {
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        try {
            am.setSpeakerphoneOn(false);
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeToBluetoothMicApi31(am);
        }
        // Always attempt SCO as well for maximum headset compatibility
        try {
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
        } catch (Throwable t) {
            Log.w(TAG, "Error starting Bluetooth SCO", t);
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static void routeToBluetoothMicApi31(AudioManager am) {
        try {
            List<AudioDeviceInfo> commDevices = am.getAvailableCommunicationDevices();
            for (AudioDeviceInfo d : commDevices) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    boolean set = am.setCommunicationDevice(d);
                    String name = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ? String.valueOf(d.getProductName()) : "bluetooth";
                    Log.d(TAG, "Routed to Bluetooth device: " + name + " (success=" + set + ")");
                    return;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in routeToBluetoothMicApi31", t);
        }
    }

    private static void routeToBuiltinMic(AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeToBuiltinMicApi31(am);
        } else {
            try {
                am.stopBluetoothSco();
            } catch (Throwable ignored) {}
            try {
                am.setBluetoothScoOn(false);
            } catch (Throwable ignored) {}
            am.setMode(AudioManager.MODE_NORMAL);
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static void routeToBuiltinMicApi31(AudioManager am) {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            List<AudioDeviceInfo> commDevices = am.getAvailableCommunicationDevices();
            for (AudioDeviceInfo d : commDevices) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    am.setCommunicationDevice(d);
                    return;
                }
            }
            am.clearCommunicationDevice();
        } catch (Throwable t) {
            Log.e(TAG, "Error in routeToBuiltinMicApi31", t);
        }
    }

    /**
     * Checks if any Bluetooth audio recording device (SCO, A2DP headset, BLE Audio) is connected.
     */
    public static boolean isBluetoothConnected(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        try {
            // Check BluetoothAdapter profile connection states
            try {
                android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    if (adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                        adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;

            if (am.isBluetoothA2dpOn() || am.isBluetoothScoOn()) {
                return true;
            }

            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_ALL);
            if (devices == null) return false;
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                         type == AudioDeviceInfo.TYPE_HEARING_AID ||
                         type == AudioDeviceInfo.TYPE_BLE_SPEAKER))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error checking bluetooth connected state", t);
        }
        return false;
    }

    /**
     * Returns a human-friendly description of the currently connected input and audio devices.
     */
    public static List<String> getConnectedInputDevices(Context context) {
        List<String> result = new ArrayList<>();
        if (context == null) return result;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return result;

            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_ALL);
            if (devices == null) return result;
            for (AudioDeviceInfo d : devices) {
                String label = "Audio Device";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        CharSequence name = d.getProductName();
                        if (name != null && name.length() > 0) {
                            label = name.toString();
                        }
                    } catch (Throwable ignored) {}
                }
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                         type == AudioDeviceInfo.TYPE_HEARING_AID ||
                         type == AudioDeviceInfo.TYPE_BLE_SPEAKER))) {
                    String entry = "🎧 " + label + " (Bluetooth)";
                    if (!result.contains(entry)) result.add(entry);
                } else if (type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                           type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    String entry = "🎙️ " + label + " (USB)";
                    if (!result.contains(entry)) result.add(entry);
                } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                           type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                    String entry = "🎧 " + label + " (Cable)";
                    if (!result.contains(entry)) result.add(entry);
                } else if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    String entry = "📱 " + label + " (Interno)";
                    if (!result.contains(entry)) result.add(entry);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error querying connected input devices", t);
        }
        return result;
    }
}
