package dev.notune.transcribe;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Edit (or create) a single post-processing prompt. Driven from
 * {@link PostProcessPromptsListActivity} via an Intent extra carrying
 * the prompt id.
 *
 * <p>As of v0.8.8 the builtin prompt is editable: invoking this activity
 * with {@link Prompt#BUILTIN_ID} opens the editor pre-filled with the
 * current body (override if present, otherwise the resource-backed
 * default). Saving persists an override entry with that id. A toolbar
 * "Reset to default" menu action appears only when an override exists,
 * so the user can revert to the virtual builtin without manually pasting
 * the original prompt.
 */
public class PostProcessPromptEditActivity extends AppCompatActivity {

    public static final String EXTRA_PROMPT_ID = "prompt_id";

    private static final int MENU_ID_RESET = 0x10;

    private PromptsRepository promptsRepository;
    private Prompt prompt;
    private boolean isNew = false;
    private boolean dirty = false;

    private TextInputLayout nameLayout;
    private TextInputLayout bodyLayout;
    private TextInputEditText nameEdit;
    private TextInputEditText bodyEdit;
    private View charCount;
    private ExtendedFloatingActionButton saveFab;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process_prompt_edit);

        promptsRepository = new PromptsRepository(this);

        String id = getIntent().getStringExtra(EXTRA_PROMPT_ID);
        prompt = id == null ? null : promptsRepository.getById(id);
        // Builtin is editable as of v0.8.8: getById(BUILTIN_ID) returns
        // either the persisted override or the virtual fallback so the
        // editor opens with the body the user is currently using.
        if (prompt == null) {
            // Defensive: orphan Intent (missing EXTRA_PROMPT_ID or unknown id).
            // Build a draft prompt in memory but DO NOT add to the repository
            // — that writes a JSON entry on disk that would persist even if
            // the user backs out without saving anything. Defer until
            // saveAndFinish() actually commits valid input.
            prompt = Prompt.createNew(getString(R.string.name_prompt_default),
                    getString(R.string.body_prompt_default));
            isNew = true;
        }

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(
                isNew ? R.string.title_new_prompt : R.string.title_edit_prompt,
                prompt.getName()));
        toolbar.setNavigationOnClickListener(v -> saveAndFinish());

        nameLayout = findViewById(R.id.layout_prompt_name);
        bodyLayout = findViewById(R.id.layout_prompt_body);
        nameEdit = findViewById(R.id.edit_prompt_name);
        bodyEdit = findViewById(R.id.edit_prompt_body);
        charCount = findViewById(R.id.text_char_count);
        saveFab = findViewById(R.id.fab_save_prompt);

        nameEdit.setText(prompt.getName());
        bodyEdit.setText(prompt.getBody());
        updateCharCount();

        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                dirty = true;
                updateCharCount();
            }
        };
        nameEdit.addTextChangedListener(tw);
        bodyEdit.addTextChangedListener(tw);

        saveFab.setOnClickListener(v -> saveAndFinish());

        // Builtin entries that have been overridden offer "Reset to default"
        // from the toolbar menu. The text editor flow handles Save via FAB,
        // so the reset action lives there so the affordance is co-located with
        // the editing UI rather than hidden behind the row's overflow menu.
        if (prompt.isBuiltin() && promptsRepository.isBuiltinOverridden()) {
            toolbar.inflateMenu(R.menu.menu_post_process_prompt_edit);
            toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.menu_reset_builtin) {
                    confirmResetBuiltin();
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    public void onBackPressed() {
        saveAndFinish();
    }

    private void confirmResetBuiltin() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.btn_reset_builtin)
                .setMessage(R.string.msg_reset_builtin_confirm)
                .setPositiveButton(R.string.btn_reset_builtin, (d, w) -> {
                    promptsRepository.delete(Prompt.BUILTIN_ID);
                    Snackbar.make(findViewById(android.R.id.content),
                            R.string.msg_builtin_reset_done, Snackbar.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateCharCount() {
        int len = bodyEdit.getText() == null ? 0 : bodyEdit.getText().length();
        charCount.setVisibility(View.VISIBLE);
        ((android.widget.TextView) charCount).setText(getString(R.string.prompt_char_count, len));
    }

    private void saveAndFinish() {
        String name = nameEdit.getText() == null ? "" : nameEdit.getText().toString().trim();
        String body = bodyEdit.getText() == null ? "" : bodyEdit.getText().toString();

        if (name.isEmpty()) {
            nameLayout.setError(getString(R.string.error_prompt_name_empty));
            return;
        }
        nameLayout.setError(null);

        if (body.trim().isEmpty()) {
            bodyLayout.setError(getString(R.string.error_prompt_body_empty));
            Snackbar.make(findViewById(android.R.id.content),
                    R.string.error_prompt_body_empty, Snackbar.LENGTH_LONG).show();
            return;
        }
        bodyLayout.setError(null);

        prompt.setName(name);
        prompt.setBody(body);
        if (isNew) {
            // First save for a defensive-created draft: persist now. Note
            // that for an editing-the-builtin flow, isNew stays false: we
            // reopen the same Prompt object that was loaded from
            // getById() and call update() so the override slot is upserted.
            promptsRepository.add(prompt);
        } else {
            // update() handles the BUILTIN_ID upsert case: if
            // getBuiltin() returned the virtual fallback (no override on
            // disk yet) the prompt object has id="__builtin__" with the
            // resource-backed body, so update() will create the override.
            promptsRepository.update(prompt);
        }
        dirty = false;
        finish();
    }
}
