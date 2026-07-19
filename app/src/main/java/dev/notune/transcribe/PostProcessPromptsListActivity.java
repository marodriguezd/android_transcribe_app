package dev.notune.transcribe;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class PostProcessPromptsListActivity extends AppCompatActivity {

    private PromptsRepository promptsRepository;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private PromptAdapter adapter;
    private String pendingExportId;

    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && pendingExportId != null) {
                        exportPrompt(pendingExportId, uri);
                    }
                }
            });

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importPrompt(uri);
            });

    private final ActivityResultLauncher<Intent> editLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                // Any result means the editor activity has finished; refresh whether saved or cancelled
                // (cheap, idempotent).
                refreshList();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_process_prompts_list);

        promptsRepository = new PromptsRepository(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_post_process_prompts_list);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_import_prompt) {
                importLauncher.launch(new String[]{"application/json", "text/plain"});
                return true;
            } else if (id == R.id.menu_export_prompt) {
                String active = promptsRepository.getActiveId();
                if (Prompt.BUILTIN_ID.equals(active)) {
                    Snackbar.make(findViewById(android.R.id.content),
                            R.string.msg_prompts_export_builtin, Snackbar.LENGTH_SHORT).show();
                    return true;
                }
                pendingExportId = active;
                launchExportDialog(active);
                return true;
            }
            return false;
        });

        recyclerView = findViewById(R.id.recycler_prompts);
        emptyText = findViewById(R.id.text_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new PromptAdapter();
        recyclerView.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fab_new_prompt);
        fab.setOnClickListener(v -> createNewPrompt());

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<Prompt> all = promptsRepository.getAllWithBuiltin();
        adapter.setData(all);
        boolean empty = promptsRepository.getUserPrompts().isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void createNewPrompt() {
        Prompt p = Prompt.createNew(
                getString(R.string.name_prompt_default),
                getString(R.string.body_prompt_default));
        promptsRepository.add(p);
        // Default to active immediately so the user can just press back and use it.
        promptsRepository.setActiveId(p.getId());
        openEditor(p.getId());
    }

    private void openEditor(String id) {
        Intent intent = new Intent(this, PostProcessPromptEditActivity.class);
        intent.putExtra(PostProcessPromptEditActivity.EXTRA_PROMPT_ID, id);
        editLauncher.launch(intent);
    }

    private void showDeleteDialog(Prompt p) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_delete_prompt)
                .setMessage(getString(R.string.msg_delete_prompt, p.getName()))
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    promptsRepository.delete(p.getId());
                    refreshList();
                    Snackbar.make(findViewById(android.R.id.content),
                            R.string.msg_prompt_deleted, Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void duplicatePrompt(Prompt source) {
        Prompt copy = Prompt.createNew(
                getString(R.string.name_prompt_copy_suffix, source.getName()),
                source.getBody());
        promptsRepository.add(copy);
        refreshList();
        Snackbar.make(findViewById(android.R.id.content),
                getString(R.string.msg_prompt_duplicated, copy.getName()),
                Snackbar.LENGTH_SHORT).show();
    }

    private void launchExportDialog(String id) {
        Prompt p = promptsRepository.getById(id);
        String safeName = (p == null ? "prompt" : p.getName().replaceAll("[^A-Za-z0-9._-]", "_"))
                + ".json";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, safeName);
        exportLauncher.launch(intent);
    }

    private void exportPrompt(String id, Uri uri) {
        try {
            String json = promptsRepository.exportToJson(id);
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                }
            }
            Snackbar.make(findViewById(android.R.id.content),
                    R.string.msg_prompts_exported, Snackbar.LENGTH_SHORT).show();
        } catch (Exception e) {
            Snackbar.make(findViewById(android.R.id.content),
                    R.string.msg_prompts_export_failed, Snackbar.LENGTH_LONG).show();
        }
    }

    private void importPrompt(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Null input stream");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            String name = promptsRepository.importFromJson(sb.toString());
            refreshList();
            Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.msg_prompts_imported, name),
                    Snackbar.LENGTH_SHORT).show();
        } catch (Exception e) {
            Snackbar.make(findViewById(android.R.id.content),
                    R.string.msg_prompts_import_failed, Snackbar.LENGTH_LONG).show();
        }
    }

    // ----- RecyclerView adapter -----

    private class PromptAdapter extends RecyclerView.Adapter<PromptAdapter.ViewHolder> {
        private final List<Prompt> data = new ArrayList<>();

        void setData(List<Prompt> newData) {
            data.clear();
            data.addAll(newData);
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_prompt, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Prompt p = data.get(position);
            holder.bind(p);
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView nameText;
            final TextView bodyText;
            final TextView subtitleText;
            final MaterialRadioButton activeRadio;
            final ImageButton editBtn;
            final ImageButton overflowBtn;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.text_prompt_name);
                bodyText = itemView.findViewById(R.id.text_prompt_body);
                subtitleText = itemView.findViewById(R.id.text_prompt_subtitle);
                activeRadio = itemView.findViewById(R.id.radio_active_prompt);
                editBtn = itemView.findViewById(R.id.btn_edit_prompt);
                overflowBtn = itemView.findViewById(R.id.btn_overflow_prompt);
            }

            void bind(Prompt p) {
                nameText.setText(p.getName());
                bodyText.setText(previewBody(p.getBody()));
                boolean isActive = p.getId().equals(promptsRepository.getActiveId());
                // Avoid triggering listener when refreshing the radio state.
                activeRadio.setOnCheckedChangeListener(null);
                activeRadio.setChecked(isActive);
                activeRadio.setOnCheckedChangeListener((v, checked) -> {
                    if (checked) {
                        promptsRepository.setActiveId(p.getId());
                        notifyDataSetChanged(); // refresh each row's subtitle
                    }
                });

                if (p.isBuiltin()) {
                    subtitleText.setText(R.string.label_builtin_prompt_desc);
                    subtitleText.setVisibility(View.VISIBLE);
                    editBtn.setVisibility(View.GONE);
                    overflowBtn.setVisibility(View.GONE);
                } else {
                    subtitleText.setVisibility(View.GONE);
                    editBtn.setVisibility(View.VISIBLE);
                    overflowBtn.setVisibility(View.VISIBLE);
                }

                // Tap row → set active; on builtin, just toggles active back to builtin.
                itemView.setOnClickListener(v -> {
                    promptsRepository.setActiveId(p.getId());
                    notifyDataSetChanged();
                });

                editBtn.setOnClickListener(v -> openEditor(p.getId()));

                overflowBtn.setOnClickListener(v -> {
                    PopupMenu popup = new PopupMenu(itemView.getContext(), v);
                    if (!p.isBuiltin()) {
                        popup.getMenu().add(0, 1, 0, R.string.btn_edit);
                        popup.getMenu().add(0, 2, 1, R.string.prompt_duplicate);
                        popup.getMenu().add(0, 3, 2, R.string.btn_export);
                        popup.getMenu().add(0, 4, 3, R.string.btn_delete);
                    } else {
                        popup.getMenu().add(0, 5, 0, R.string.btn_export_disabled_for_builtin);
                        // Disable: we render it but tap is a no-op.
                    }
                    popup.setOnMenuItemClickListener(item -> {
                        switch (item.getItemId()) {
                            case 1: openEditor(p.getId()); return true;
                            case 2: duplicatePrompt(p); return true;
                            case 3:
                                pendingExportId = p.getId();
                                launchExportDialog(p.getId());
                                return true;
                            case 4: showDeleteDialog(p); return true;
                            case 5: /* builtin export hint */ return true;
                        }
                        return false;
                    });
                    popup.show();
                });
            }
        }

        /** Trim and cap a prompt body for the row preview. */
        private String previewBody(String body) {
            if (body == null) return "";
            String trimmed = body.trim();
            int max = 240;
            if (trimmed.length() <= max) return trimmed;
            return trimmed.substring(0, max) + "…";
        }
    }
}
