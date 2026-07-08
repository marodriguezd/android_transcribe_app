package dev.notune.transcribe;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Applies the saved dark-mode choice before any activity is created, and enables
 * Material You dynamic color on Android 12+. Runs in every process (including the
 * ":ime" keyboard process).
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemePrefs.apply(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
