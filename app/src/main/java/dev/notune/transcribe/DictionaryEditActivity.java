package dev.notune.transcribe;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

public class DictionaryEditActivity extends AppCompatActivity {

    private DictionaryManager dictionaryManager;
    private String dictId;
    private Dictionary dictionary;
    private TextInputEditText editName;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private TextView countText;
    private WordAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary_edit);

        dictionaryManager = new DictionaryManager(this);
        dictId = getIntent().getStringExtra("dict_id");
        dictionary = dictionaryManager.getById(dictId);

        if (dictionary == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(dictionary.getName());
        toolbar.setNavigationOnClickListener(v -> saveAndFinish());

        editName = findViewById(R.id.edit_dict_name);
        editName.setText(dictionary.getName());
        recyclerView = findViewById(R.id.recycler_words);
        emptyText = findViewById(R.id.text_empty_words);
        countText = findViewById(R.id.text_word_count);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WordAdapter();
        recyclerView.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fab_add_word);
        fab.setOnClickListener(v -> showAddWordDialog());

        refreshList();
    }

    @Override
    public void onBackPressed() {
        saveAndFinish();
    }

    private void saveAndFinish() {
        String name = editName.getText().toString().trim();
        if (!name.isEmpty()) {
            dictionary.setName(name);
            dictionaryManager.updateDictionary(dictionary);
        }
        finish();
    }

    private void refreshList() {
        List<String> words = dictionary.getWords();
        adapter.setData(words);
        emptyText.setVisibility(words.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(words.isEmpty() ? View.GONE : View.VISIBLE);
        countText.setText(getString(R.string.words_count, words.size()));
    }

    private void showAddWordDialog() {
        showWordDialog(null);
    }

    private void showEditWordDialog(String currentWord) {
        showWordDialog(currentWord);
    }

    private void showWordDialog(String existingWord) {
        boolean isEdit = existingWord != null;
        int titleRes = isEdit ? R.string.title_edit_word : R.string.title_add_word;

        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(R.string.desc_word_input);
        layout.setHelperText(null);

        TextInputEditText editText = new TextInputEditText(this);
        editText.setSingleLine(true);
        if (isEdit) {
            editText.setText(existingWord);
        }
        layout.addView(editText);

        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setPadding(padding, padding, padding, padding);
        container.addView(layout);

        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String word = editText.getText().toString().trim();
                    if (word.isEmpty()) {
                        Snackbar.make(findViewById(android.R.id.content),
                                R.string.msg_word_empty, Snackbar.LENGTH_SHORT).show();
                        return;
                    }
                    if (isEdit) {
                        dictionaryManager.updateWord(dictId, existingWord, word);
                    } else {
                        if (dictionary.getWords().contains(word)) {
                            Snackbar.make(findViewById(android.R.id.content),
                                    R.string.msg_word_exists, Snackbar.LENGTH_SHORT).show();
                            return;
                        }
                        dictionaryManager.addWord(dictId, word);
                    }
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Adapter
    private class WordAdapter extends RecyclerView.Adapter<WordAdapter.ViewHolder> {
        private List<String> data = new ArrayList<>();

        void setData(List<String> newData) {
            this.data = newData;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_word, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            String word = data.get(position);
            holder.wordText.setText(word);

            holder.editBtn.setOnClickListener(v -> showEditWordDialog(word));

            holder.deleteBtn.setOnClickListener(v -> {
                dictionaryManager.removeWord(dictId, word);
                refreshList();
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView wordText;
            ImageButton editBtn;
            ImageButton deleteBtn;

            ViewHolder(View itemView) {
                super(itemView);
                wordText = itemView.findViewById(R.id.text_word);
                editBtn = itemView.findViewById(R.id.btn_edit_word);
                deleteBtn = itemView.findViewById(R.id.btn_delete_word);
            }
        }
    }
}
