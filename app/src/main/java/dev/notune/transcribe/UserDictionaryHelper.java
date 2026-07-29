package dev.notune.transcribe;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.Settings;
import android.provider.UserDictionary;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Helper to query and open Android's native System User Dictionary (Personal Dictionary),
 * matching FUTO Keyboard's approach. Automatically syncs system words into the custom_words
 * marker file so Rust's phonetic corrector (src/corrector.rs) applies them seamlessly.
 */
public class UserDictionaryHelper {

    private static final String TAG = "UserDictionaryHelper";
    public static final String CUSTOM_WORDS_FILE = "custom_words";

    /**
     * Opens Android's native User Dictionary Settings screen (Settings.ACTION_USER_DICTIONARY_SETTINGS),
     * allowing the user to manage their custom words directly in Android's system UI (FUTO-style).
     */
    public static void openSystemUserDictionarySettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_USER_DICTIONARY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "ACTION_USER_DICTIONARY_SETTINGS not found, trying string action fallback", e);
            try {
                Intent fallback = new Intent("android.settings.USER_DICTIONARY_SETTINGS");
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ex) {
                Log.w(TAG, "USER_DICTIONARY_SETTINGS string fallback failed, opening general settings", ex);
                try {
                    Intent genSettings = new Intent(Settings.ACTION_SETTINGS);
                    genSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(genSettings);
                } catch (Exception ex2) {
                    Log.e(TAG, "Failed to open settings", ex2);
                }
            }
        }
    }

    /**
     * Reads words from Android's UserDictionary ContentProvider and syncs them into the
     * filesDir/custom_words marker file so Rust's corrector.rs automatically uses them.
     */
    public static void syncSystemUserDictionaryAsync(final Context context) {
        if (context == null) return;
        new Thread(() -> syncSystemUserDictionary(context)).start();
    }

    public static synchronized void syncSystemUserDictionary(Context context) {
        if (context == null) return;
        Set<String> words = new LinkedHashSet<>();

        // 1. Read existing custom_words file (user's manual entries/comments)
        File markerFile = new File(context.getFilesDir(), CUSTOM_WORDS_FILE);
        if (markerFile.exists()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(markerFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        words.add(line.trim());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error reading existing custom_words marker file", e);
            }
        }

        // 2. Query Android's UserDictionary ContentProvider
        try {
            Cursor cursor = context.getContentResolver().query(
                    UserDictionary.Words.CONTENT_URI,
                    new String[]{UserDictionary.Words.WORD},
                    null,
                    null,
                    null
            );
            if (cursor != null) {
                int wordIndex = cursor.getColumnIndex(UserDictionary.Words.WORD);
                while (cursor.moveToNext()) {
                    if (wordIndex != -1) {
                        String word = cursor.getString(wordIndex);
                        if (word != null && !word.trim().isEmpty()) {
                            words.add(word.trim());
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "UserDictionary ContentProvider query skipped/unavailable", e);
        }

        // 3. Write merged list atomically into custom_words marker file
        if (words.isEmpty()) return;

        File tempFile = new File(context.getFilesDir(), CUSTOM_WORDS_FILE + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            for (String word : words) {
                fos.write((word + "\n").getBytes(StandardCharsets.UTF_8));
            }
            fos.flush();
            if (!tempFile.renameTo(markerFile)) {
                try (FileOutputStream out = new FileOutputStream(markerFile)) {
                    for (String word : words) {
                        out.write((word + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                tempFile.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write merged user dictionary to custom_words marker file", e);
        }
    }
}
