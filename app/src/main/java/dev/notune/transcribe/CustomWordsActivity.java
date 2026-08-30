package dev.notune.transcribe;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_words);

        View rootView = findViewById(R.id.custom_words_root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

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
