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
        List<Dictionary> dictionaries = dictionaryManager.getAll();
        adapter.setData(dictionaries);
        emptyText.setVisibility(dictionaries.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(dictionaries.isEmpty() ? View.GONE : View.VISIBLE);
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
                                    "Dictionary exported", Snackbar.LENGTH_SHORT).show();
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
            holder.countText.setText(getString(R.string.words_count, dict.getWordCount()));

            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setChecked(dict.isEnabled());
            holder.toggle.setOnCheckedChangeListener((v, checked) -> {
                dict.setEnabled(checked);
                dictionaryManager.updateDictionary(dict);
            });

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(DictionaryListActivity.this, DictionaryEditActivity.class);
                intent.putExtra("dict_id", dict.getId());
                startActivity(intent);
            });

            holder.overflowBtn.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(DictionaryListActivity.this, v);
                popup.getMenu().add(0, 1, 0, R.string.btn_edit);
                popup.getMenu().add(0, 2, 1, R.string.btn_export);
                popup.getMenu().add(0, 3, 2, R.string.btn_delete);
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
                    }
                    return false;
                });
                popup.show();
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView nameText, countText;
            MaterialSwitch toggle;
            ImageButton overflowBtn;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.text_dict_name);
                countText = itemView.findViewById(R.id.text_dict_count);
                toggle = itemView.findViewById(R.id.switch_dict_enabled);
                overflowBtn = itemView.findViewById(R.id.btn_dict_overflow);
            }
        }
    }
}
