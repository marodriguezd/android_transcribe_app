package dev.notune.transcribe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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
 * Ensures proper communication mode acquisition before speech recording starts
 * and clean teardown/release when recording terminates.</p>
 */
public final class AudioDeviceManager {
    private static final String TAG = "AudioDeviceManager";

    public static final String MIC_MODE_AUTO = "auto";
    public static final String MIC_MODE_BLUETOOTH_ONLY = "bluetooth";
    public static final String MIC_MODE_BUILTIN_ONLY = "builtin";

    private static volatile boolean isRoutingActive = false;

    private AudioDeviceManager() {
        // Utility class
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
        } catch (Throwable t) {
            Log.e(TAG, "Error acquiring microphone routing", t);
        }
    }

    /**
     * Releases communication device routing and restores normal audio mode.
     * Must be called immediately when speech recording ends.
     */
    public static synchronized void releaseMicrophone(Context context) {
        if (context == null || !isRoutingActive) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice();
            } else {
                if (am.isBluetoothScoOn()) {
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                }
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error releasing microphone routing", t);
        } finally {
            isRoutingActive = false;
        }
    }

    private static void routeAuto(Context context, AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> commDevices = am.getAvailableCommunicationDevices();
            AudioDeviceInfo target = null;

            // 1. Check for Bluetooth Headset (SCO or BLE)
            for (AudioDeviceInfo d : commDevices) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
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
                Log.d(TAG, "Auto routed to communication device: " + target.getProductName() + " (success=" + set + ")");
            }
        } else {
            // Android 8 - 11 legacy SCO activation
            if (isBluetoothConnected(context)) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            }
        }
    }

    private static void routeToBluetoothMic(Context context, AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> commDevices = am.getAvailableCommunicationDevices();
            for (AudioDeviceInfo d : commDevices) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    am.setCommunicationDevice(d);
                    Log.d(TAG, "Routed to Bluetooth device: " + d.getProductName());
                    return;
                }
            }
        } else {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
        }
    }

    private static void routeToBuiltinMic(AudioManager am) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> commDevices = am.getAvailableCommunicationDevices();
            for (AudioDeviceInfo d : commDevices) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    am.setCommunicationDevice(d);
                    return;
                }
            }
            am.clearCommunicationDevice();
        } else {
            if (am.isBluetoothScoOn()) {
                am.stopBluetoothSco();
                am.setBluetoothScoOn(false);
            }
            am.setMode(AudioManager.MODE_NORMAL);
        }
    }

    /**
     * Checks if any Bluetooth audio recording device is connected.
     */
    public static boolean isBluetoothConnected(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a human-friendly description of the currently connected input devices.
     */
    public static List<String> getConnectedInputDevices(Context context) {
        List<String> result = new ArrayList<>();
        if (context == null) return result;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return result;

        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo d : devices) {
            CharSequence name = d.getProductName();
            String label = (name != null && name.length() > 0) ? name.toString() : "Audio Input";
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    result.add("🎧 " + label + " (Bluetooth)");
                    break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                    result.add("🎙️ " + label + " (USB)");
                    break;
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    result.add("🎧 " + label + " (Cable)");
                    break;
                case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                    result.add("📱 " + label + " (Interno)");
                    break;
            }
        }
        return result;
    }
}
