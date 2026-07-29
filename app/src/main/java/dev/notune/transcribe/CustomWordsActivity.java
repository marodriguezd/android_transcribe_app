package dev.notune.transcribe;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Editor for the custom-words dictionary — a marker file in filesDir named
 * {@code custom_words}, one term per line (lines starting with {@code #} are
 * comments, blank lines ignored). The native corrector reads it on every
 * transcription (with an mtime cache) and replaces words in the transcript
 * that sound like a dictionary term but were misrecognized, preserving the
 * speaker's capitalization context.
 *
 * The file's presence and non-emptiness is the opt-in: no separate toggle.
 * Deleting all content disables correction (the corrector no-ops on an empty
 * dictionary). This follows the project's marker-file convention
 * (AGENTS.md §4.5) so the {@code :ime} process sees the same file.
 */
public class CustomWordsActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_words);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        TextInputEditText edit = findViewById(R.id.edit_words);
        edit.setText(loadWords());

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> {
            saveWords(edit.getText().toString());
            Toast.makeText(this, R.string.cw_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private String loadWords() {
        return MarkerFileHelper.readString(this, "custom_words", "");
    }

    private void saveWords(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            MarkerFileHelper.delete(this, "custom_words");
        } else {
            MarkerFileHelper.writeString(this, "custom_words", trimmed);
        }
    }
}
