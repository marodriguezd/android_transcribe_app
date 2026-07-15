package dev.notune.transcribe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModelDownloadManager {
    private static final String TAG = "ModelDownloadManager";

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {2_000, 4_000, 8_000};
    private static final long NETWORK_POLL_INTERVAL_MS = 2_000;
    private static final long NETWORK_WAIT_TIMEOUT_MS = 60_000;
    private static final Map<String, String> BASE_URLS = new HashMap<>();
    static {
        BASE_URLS.put("0.6b",
                "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main");
        BASE_URLS.put("1.1b",
                "https://huggingface.co/istupakov/canary-1b-v2-onnx/resolve/main");
    }

    private static final Map<String, String[]> MODEL_FILES = new HashMap<>();
    static {
        MODEL_FILES.put("0.6b", new String[]{
                "encoder-model.int8.onnx",
                "decoder_joint-model.int8.onnx",
                "nemo128.onnx",
                "vocab.txt"
        });
        MODEL_FILES.put("1.1b", new String[]{
                "encoder.int8.onnx",
                "decoder.int8.onnx",
                "joiner.int8.onnx",
                "tokens.txt"
        });
    }

    private final Context context;
    private final String variant;
    private final String baseUrl;
    private final String[] modelFiles;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private long lastNotificationTime;
    private int lastNotificationPercent;
    private static final long NOTIFICATION_THROTTLE_MS = 500;
    private static final int NOTIFICATION_THROTTLE_PCT = 2;

    private final java.util.List<ProgressCallback> callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int currentPercent;
    private volatile String currentFileName;
    private volatile long currentBytesDownloaded;
    private volatile long currentTotalBytes;
    private volatile boolean downloadActive;
    private PowerManager.WakeLock wakeLock;

    public interface ProgressCallback {
        void onProgress(String fileName, int percent, long bytesDownloaded, long totalBytes);
        void onRetry(String fileName, int attempt, long waitMs);
        void onComplete();
        void onError(String error, boolean retryable);
    }

    public ModelDownloadManager(Context context, String variant) {
        this.context = context.getApplicationContext();
        this.variant = variant;
        this.baseUrl = BASE_URLS.get(variant);
        this.modelFiles = MODEL_FILES.get(variant);

        if (this.baseUrl == null || this.modelFiles == null) {
            throw new IllegalArgumentException("Unknown model variant: " + variant);
        }
    }

    public void setCallback(ProgressCallback callback) {
        if (callback != null && !callbacks.contains(callback)) {
            callbacks.add(callback);
        }
    }

    public boolean isDownloading() {
        return downloading.get();
    }

    public String getVariant() {
        return variant;
    }

    public int getCurrentPercent() {
        return currentPercent;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    public long getCurrentBytesDownloaded() {
        return currentBytesDownloaded;
    }

    public long getCurrentTotalBytes() {
        return currentTotalBytes;
    }

    public boolean isDownloadActive() {
        return downloadActive;
    }

    public boolean isModelDownloaded() {
        File dir = getModelDir();
        if (!dir.exists()) return false;
        for (String fileName : modelFiles) {
            File f = new File(dir, fileName);
            if (!f.exists() || f.length() == 0) return false;
        }
        return true;
    }

    public File getModelDir() {
        return new File(context.getFilesDir(), "models/parakeet-tdt-" + variant + "-v3-int8");
    }

    public void deleteModel() {
        File dir = getModelDir();
        if (dir.exists()) {
            deleteRecursively(dir);
        }
    }

    private void deleteRecursively(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        fileOrDir.delete();
    }

    public void download(ProgressCallback callback) {
        setCallback(callback);
        if (downloading.compareAndSet(false, true)) {
            currentPercent = 0;
            currentFileName = null;
            currentBytesDownloaded = 0;
            currentTotalBytes = 0;
            downloadActive = true;
            acquireWakeLock();
            executor.execute(() -> {
                try {
                    downloadFiles();
                } finally {
                    downloadActive = false;
                    releaseWakeLock();
                    downloading.set(false);
                }
            });
        }
    }

    public void cancel() {
        downloading.set(false);
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        if (wakeLock == null || !wakeLock.isHeld()) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "TranscribeApp::ModelDownload");
                wakeLock.acquire(30 * 60 * 1000L); // 30 min max safety timeout
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void downloadFiles() {
        File dir = getModelDir();

        File modelsParent = new File(context.getFilesDir(), "models");
        if (modelsParent.exists() && modelsParent.isFile()) {
            Log.w(TAG, "Deleting stale file 'models' that blocks directory creation");
            modelsParent.delete();
        }

        if (!dir.exists()) {
            long usableBytes = context.getFilesDir().getUsableSpace();
            Log.d(TAG, "Creating model dir " + dir.getAbsolutePath()
                    + " (usable storage: " + usableBytes + " bytes)");
            if (!dir.mkdirs()) {
                Log.e(TAG, "mkdirs still failed; parent exists=" + modelsParent.exists()
                        + " isDir=" + modelsParent.isDirectory());
                postError("Failed to create model directory", true);
                return;
            }
        }

        for (String fileName : modelFiles) {
            if (!downloading.get()) {
                postError("Download canceled", false);
                return;
            }

            File destFile = new File(dir, fileName);
            if (destFile.exists() && destFile.length() > 0) {
                Log.d(TAG, "Skipping " + fileName + " (already exists)");
                continue;
            }

            boolean success = downloadFileWithRetry(fileName, destFile);
            if (!success) {
                return;
            }
        }

        mainHandler.post(() -> {
            for (ProgressCallback cb : callbacks) cb.onComplete();
        });
    }

    private boolean downloadFileWithRetry(String fileName, File destFile) {
        File tmpFile = new File(destFile.getParent(), destFile.getName() + ".tmp");

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (!downloading.get()) {
                downloadActive = false;
                postError("Download canceled", false);
                return false;
            }

            long waitMs = (attempt == 1) ? 0 : BACKOFF_MS[attempt - 2];

            if (waitMs > 0) {
                final int a = attempt;
                final long w = waitMs;
                mainHandler.post(() -> {
                    for (ProgressCallback cb : callbacks) cb.onRetry(fileName, a, w);
                });

                if (!sleepWithNetworkPoll(waitMs)) {
                    return false;
                }
            }

            if (!downloading.get()) {
                downloadActive = false;
                postError("Download canceled", false);
                return false;
            }

            try {
                downloadFile(fileName, destFile, tmpFile);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Attempt " + attempt + "/" + MAX_RETRIES
                        + " failed for " + fileName, e);
                if (attempt == MAX_RETRIES) {
                    postError("Failed to download " + fileName
                            + " after " + MAX_RETRIES + " attempts: " + e.getMessage(), true);
                    return false;
                }
            }
        }
        return false;
    }

    private void downloadFile(String fileName, File destFile, File tmpFile) throws IOException {
        if (!waitForNetwork()) {
            throw new IOException("No network available");
        }

        long existingBytes = tmpFile.exists() ? tmpFile.length() : 0;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/" + fileName + "?download=true");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "TranscribeApp/1.0");

            if (existingBytes > 0) {
                conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                Log.d(TAG, "Resuming " + fileName + " from byte " + existingBytes);
            }

            int responseCode = conn.getResponseCode();

            if (existingBytes > 0
                    && responseCode == HttpURLConnection.HTTP_PARTIAL) {
                // Resume succeeded
            } else if (existingBytes > 0
                    && responseCode == HttpURLConnection.HTTP_OK) {
                existingBytes = 0;
                tmpFile.delete();
            } else if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode);
            }

            long totalBytes = conn.getContentLengthLong();
            if (totalBytes <= 0) {
                totalBytes = -1;
            } else if (existingBytes > 0) {
                totalBytes += existingBytes;
            }

            InputStream inStream = conn.getInputStream();

            try (BufferedInputStream in = new BufferedInputStream(inStream, 8192);
                 FileOutputStream out = new FileOutputStream(tmpFile, existingBytes > 0)) {

                byte[] buf = new byte[8192];
                long downloaded = existingBytes;
                int read;

                while ((read = in.read(buf)) != -1) {
                    if (!downloading.get()) {
                        throw new IOException("Download canceled");
                    }
                    out.write(buf, 0, read);
                    downloaded += read;

                    if (totalBytes > 0) {
                        int pct = (int) (downloaded * 100 / totalBytes);
                        final long dl = downloaded;
                        final long tl = totalBytes;
                        final String fn = fileName;

                        // Track current progress for state restoration
                        currentPercent = pct;
                        currentFileName = fn;
                        currentBytesDownloaded = dl;
                        currentTotalBytes = tl;

                        // Throttle notifications and UI updates
                        long now = System.currentTimeMillis();
                        boolean pctJumped = Math.abs(pct - lastNotificationPercent) >= NOTIFICATION_THROTTLE_PCT;
                        boolean timeElapsed = (now - lastNotificationTime) >= NOTIFICATION_THROTTLE_MS;

                        if (pctJumped || timeElapsed || pct >= 100) {
                            lastNotificationTime = now;
                            lastNotificationPercent = pct;
                            mainHandler.post(() -> {
                                for (ProgressCallback cb : callbacks) cb.onProgress(fn, pct, dl, tl);
                            });
                        }
                    }
                }
            }

            if (!tmpFile.renameTo(destFile)) {
                throw new IOException("Failed to rename temp file to " + destFile.getName());
            }

            Log.d(TAG, "Downloaded " + fileName + " (" + destFile.length() + " bytes)");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean waitForNetwork() {
        if (isNetworkAvailable()) return true;

        Log.w(TAG, "No network — waiting for connectivity");
        long elapsed = 0;
        while (elapsed < NETWORK_WAIT_TIMEOUT_MS) {
            if (!downloading.get()) return false;

            try {
                Thread.sleep(NETWORK_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            elapsed += NETWORK_POLL_INTERVAL_MS;

            if (isNetworkAvailable()) {
                Log.d(TAG, "Network restored after " + elapsed + "ms");
                return true;
            }
        }
        Log.e(TAG, "Network wait timed out after " + NETWORK_WAIT_TIMEOUT_MS + "ms");
        return false;
    }

    private boolean sleepWithNetworkPoll(long durationMs) {
        long elapsed = 0;
        while (elapsed < durationMs) {
            if (!downloading.get()) return false;

            long remaining = durationMs - elapsed;
            long sleepMs = Math.min(NETWORK_POLL_INTERVAL_MS, remaining);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            elapsed += sleepMs;
        }
        return true;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnectedOrConnecting();
    }

    private void postError(String msg, boolean retryable) {
        mainHandler.post(() -> {
            for (ProgressCallback cb : callbacks) cb.onError(msg, retryable);
        });
    }
}
