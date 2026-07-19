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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

public class DictionaryListActivity extends AppCompatActivity {

    private DictionaryManager dictionaryManager;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private DictionaryAdapter adapter;

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importDictionary(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary_list);

        dictionaryManager = new DictionaryManager(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_dictionary_list);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_import) {
                importLauncher.launch(new String[]{"application/json", "text/plain"});
                return true;
            }
            return false;
        });

        recyclerView = findViewById(R.id.recycler_dictionaries);
        emptyText = findViewById(R.id.text_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new DictionaryAdapter();
        recyclerView.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fab_add_dict);
        fab.setOnClickListener(v -> showNewDictionaryDialog());

        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        // Always show the default “My words” dictionary as the first row, even
        // when no override is on disk. The list is empty visually only when
        // (a) the user has not enabled the default AND (b) no user dictionaries exist.
        Dictionary defaultDict = dictionaryManager.getDefault();
        List<Dictionary> userDicts = new ArrayList<>();
        for (Dictionary d : dictionaryManager.getAll()) {
            if (!d.isDefault()) userDicts.add(d);
        }
        List<Dictionary> allWithDefault = new ArrayList<>();
        allWithDefault.add(defaultDict);
        allWithDefault.addAll(userDicts);
        adapter.setData(allWithDefault);
        boolean empty = userDicts.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showNewDictionaryDialog() {
        EditText editText = new EditText(this);
        editText.setHint(R.string.hint_new_dict_name);
        editText.setSingleLine(true);

        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(padding, padding, padding, padding);
        container.addView(editText);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_new_dictionary)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = editText.getText().toString().trim();
                    if (!name.isEmpty()) {
                        Dictionary dict = new Dictionary(name);
                        dictionaryManager.addDictionary(dict);
                        refreshList();
                        // Open the new dictionary for editing
                        Intent intent = new Intent(this, DictionaryEditActivity.class);
                        intent.putExtra("dict_id", dict.getId());
                        startActivity(intent);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeleteDialog(Dictionary dict) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_delete_dictionary)
                .setMessage(getString(R.string.msg_delete_dictionary, dict.getName()))
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    dictionaryManager.deleteDictionary(dict.getId());
                    refreshList();
                    Snackbar.make(findViewById(android.R.id.content),
                            R.string.msg_dictionary_deleted, Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void importDictionary(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }

        try {
            String name = dictionaryManager.importDictionary(
                    getContentResolver().openInputStream(uri));
            refreshList();
            Snackbar.make(findViewById(android.R.id.content),
                    getString(R.string.msg_dictionary_imported, name),
                    Snackbar.LENGTH_SHORT).show();
        } catch (Exception e) {
            Snackbar.make(findViewById(android.R.id.content),
                    R.string.msg_import_error, Snackbar.LENGTH_LONG).show();
        }
    }

    private String pendingExportId = "";

    private void exportDictionary(Dictionary dict) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, dict.getName() + ".json");
        exportLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            // Find which dictionary to export (last tapped)
                            dictionaryManager.exportDictionary(pendingExportId,
                                    getContentResolver().openOutputStream(uri));
                            Snackbar.make(findViewById(android.R.id.content),
                                    R.string.dictionary_exported, Snackbar.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Snackbar.make(findViewById(android.R.id.content),
                                    R.string.msg_import_error, Snackbar.LENGTH_LONG).show();
                        }
                    }
                }
            });

    // Adapter
    private class DictionaryAdapter extends RecyclerView.Adapter<DictionaryAdapter.ViewHolder> {
        private List<Dictionary> data = new ArrayList<>();

        void setData(List<Dictionary> newData) {
            this.data = newData;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dictionary, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            Dictionary dict = data.get(position);
            holder.nameText.setText(dict.getName());

            // Default row shows an inline subtitle paragraph (parallel to
            // the prompts row) so users discover that the row is the
            // editable app-default dictionary without having to open
            // overflow. The subtitle toggles between "App default
            // dictionary — tap to customize" (virtual/no override) and
            // "App default (customized)" (override on disk) before falling
            // through to a plain word count for user dictionaries.
            if (dict.isDefault()) {
                boolean overridden = dictionaryManager.isDefaultOverridden();
                holder.subtitleText.setText(overridden
                        ? R.string.desc_dictionary_override
                        : R.string.desc_default_dictionary);
                holder.subtitleText.setVisibility(View.VISIBLE);
            } else {
                holder.subtitleText.setVisibility(View.GONE);
            }
            holder.countText.setText(getString(R.string.words_count, dict.getWordCount()));

            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setChecked(dict.isEnabled());
            holder.toggle.setOnCheckedChangeListener((v, checked) -> {
                dict.setEnabled(checked);
                dictionaryManager.updateDictionary(dict);
            });

            // Inline Edit image button — always visible, parallels the
            // prompts Edit affordance. Tapping it routes to the same
            // editor activity, which now accepts the default id without
            // finishing early.
            holder.editBtn.setOnClickListener(v -> {
                Intent intent = new Intent(DictionaryListActivity.this, DictionaryEditActivity.class);
                intent.putExtra("dict_id", dict.getId());
                startActivity(intent);
            });

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(DictionaryListActivity.this, DictionaryEditActivity.class);
                intent.putExtra("dict_id", dict.getId());
                startActivity(intent);
            });

            holder.overflowBtn.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(DictionaryListActivity.this, v);
                if (!dict.isDefault()) {
                    popup.getMenu().add(0, 1, 0, R.string.btn_edit);
                    popup.getMenu().add(0, 2, 1, R.string.btn_export);
                    popup.getMenu().add(0, 3, 2, R.string.btn_delete);
                } else {
                    // Default row (v0.8.8 onward): Edit opens the editor;
                    // Export writes the id-stripped JSON; Reset reverts to
                    // the resource-backed virtual default and only appears
                    // when an override is on disk so the user can always
                    // reach the “pristine” virtual state. Delete is
                    // intentionally absent — the default slot cannot be
                    // removed, only reset.
                    popup.getMenu().add(0, 1, 0, R.string.btn_edit);
                    popup.getMenu().add(0, 2, 1, R.string.btn_export);
                    if (dictionaryManager.isDefaultOverridden()) {
                        popup.getMenu().add(0, 4, 2, R.string.btn_reset_dictionary);
                    }
                }
                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 1:
                            Intent intent = new Intent(DictionaryListActivity.this, DictionaryEditActivity.class);
                            intent.putExtra("dict_id", dict.getId());
                            startActivity(intent);
                            return true;
                        case 2:
                            pendingExportId = dict.getId();
                            exportDictionary(dict);
                            return true;
                        case 3:
                            showDeleteDialog(dict);
                            return true;
                        case 4:
                            dictionaryManager.deleteDictionary(Dictionary.DEFAULT_ID);
                            refreshList();
                            Snackbar.make(findViewById(android.R.id.content),
                                    R.string.msg_dictionary_reset_done,
                                    Snackbar.LENGTH_SHORT).show();
                            return true;
                    }
                    return false;
                });
                popup.show();
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView nameText, subtitleText, countText;
            MaterialSwitch toggle;
            ImageButton editBtn;
            ImageButton overflowBtn;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.text_dict_name);
                subtitleText = itemView.findViewById(R.id.text_dict_subtitle);
                countText = itemView.findViewById(R.id.text_dict_count);
                toggle = itemView.findViewById(R.id.switch_dict_enabled);
                editBtn = itemView.findViewById(R.id.btn_edit_dict);
                overflowBtn = itemView.findViewById(R.id.btn_dict_overflow);
            }
        }
    }
}
