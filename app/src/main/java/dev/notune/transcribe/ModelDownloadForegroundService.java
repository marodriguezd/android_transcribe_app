package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class ModelDownloadForegroundService extends Service {
    private static final String TAG = "ModelDlFgService";
    public static final String ACTION_START = "dev.notune.transcribe.START_MODEL_DOWNLOAD";
    public static final String ACTION_STOP = "dev.notune.transcribe.STOP_MODEL_DOWNLOAD";
    public static final String EXTRA_VARIANT = "variant";
    private static final String CHANNEL_ID = "model_download_fg";
    private static final int NOTIFICATION_ID = 77702;

    private ModelDownloadManager downloadManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            cancel();
            stopSelf();
            return START_REDELIVER_INTENT;
        }

        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        String variant = intent.getStringExtra(EXTRA_VARIANT);
        if (variant == null) {
            Log.e(TAG, "Missing variant extra");
            stopSelf();
            return START_NOT_STICKY;
        }

        String modelName = getModelName(variant);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID,
                        createProgressNotification(0, modelName),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID,
                        createProgressNotification(0, modelName));
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed, continuing without notification", e);
        }

        ((App) getApplication()).startDownload(variant, new ModelDownloadManager.ProgressCallback() {
            @Override
            public void onProgress(String fileName, int percent, long bytesDownloaded, long totalBytes) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, createProgressNotification(percent, modelName));
                }
            }

            @Override
            public void onComplete() {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }

            @Override
            public void onError(String error, boolean retryable) {
                Log.e(TAG, "Download error: " + error + " retryable=" + retryable);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }

            @Override
            public void onRetry(String fileName, int attempt, long waitMs) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, createRetryNotification(fileName, attempt, modelName));
                }
            }
        });

        return START_REDELIVER_INTENT;
    }

    public void cancel() {
        App app = (App) getApplication();
        ModelDownloadManager mgr = app.getDownloadManager();
        if (mgr != null) {
            mgr.cancel();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override
    public void onDestroy() {
        cancel();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Model Downloads",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows download progress for transcription models");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createProgressNotification(int percent, String modelName) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading " + modelName + " model...")
                .setContentText(percent + "% complete")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private Notification createRetryNotification(String fileName, int attempt, String modelName) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading " + modelName + " model...")
                .setContentText("Retrying " + fileName + " (attempt " + attempt + ")")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private String getModelName(String variant) {
        if ("0.6b".equals(variant)) {
            return "Fast (0.6B)";
        } else if ("1.1b".equals(variant)) {
            return "Precise (1.1B)";
        }
        return variant;
    }
}
