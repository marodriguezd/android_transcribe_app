package dev.notune.transcribe;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.google.android.material.color.DynamicColors;

public class App extends Application {
    public static final String CHANNEL_ID = "model_download";
    public static final String CHANNEL_ID_FG = "model_download_fg";

    private ModelDownloadManager downloadManager;

    @Override
    public void onCreate() {
        super.onCreate();
        ThemePrefs.apply(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel downloadChannel = new NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW);
        downloadChannel.setDescription("Shows download progress for transcription models");
        nm.createNotificationChannel(downloadChannel);

        NotificationChannel fgChannel = new NotificationChannel(
                CHANNEL_ID_FG,
                "Model Download Service",
                NotificationManager.IMPORTANCE_LOW);
        fgChannel.setDescription("Keeps download alive in background");
        nm.createNotificationChannel(fgChannel);
    }

    public ModelDownloadManager getDownloadManager() {
        return downloadManager;
    }

    public void startDownload(String variant, ModelDownloadManager.ProgressCallback callback) {
        if (downloadManager != null && downloadManager.isDownloading()) {
            if (variant.equals(downloadManager.getVariant())) {
                downloadManager.setCallback(callback);
                return;
            }
            downloadManager.cancel();
        }
        downloadManager = new ModelDownloadManager(this.getApplicationContext(), variant);
        downloadManager.download(callback);
    }
}
