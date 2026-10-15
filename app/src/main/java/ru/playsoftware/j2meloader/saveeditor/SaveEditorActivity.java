package ru.playsoftware.j2meloader.saveeditor;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.base.BaseActivity;
import ru.playsoftware.j2meloader.config.Config;

/**
 * Lists the RMS records (.rsr files) of one game, and searches a value across all of them in
 * every encoding at once. A record or a hit opens in {@link RecordEditorActivity}.
 */
public class SaveEditorActivity extends BaseActivity {
	private static final String EXTRA_APP_DIR = "appDir";
	private static final String EXTRA_TITLE = "title";

	private final List<RmsRecord> records = new ArrayList<>();
	/** Current search results, null when the record list is shown. */
	private List<SaveHit> hits;
	private ArrayAdapter<String> adapter;
	private EditText valueInput;
	private TextView status;
	private File dataDir;

	public static void start(Context context, String appDirPath, String title) {
		Intent intent = new Intent(context, SaveEditorActivity.class);
		intent.putExtra(EXTRA_APP_DIR, appDirPath);
		intent.putExtra(EXTRA_TITLE, title);
		context.startActivity(intent);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_save_editor);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
		String title = getIntent().getStringExtra(EXTRA_TITLE);
		setTitle(getString(R.string.save_editor_title, title == null ? "" : title));
		File appDir = new File(getIntent().getStringExtra(EXTRA_APP_DIR));
		dataDir = new File(Config.getDataDir(), appDir.getName());

		valueInput = findViewById(R.id.save_editor_value);
		status = findViewById(R.id.save_editor_status);
		ListView listView = findViewById(R.id.list_view);
		listView.setEmptyView(findViewById(R.id.empty_view));
		adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
		listView.setAdapter(adapter);
		listView.setOnItemClickListener((parent, view, position, id) -> {
			if (RmsStorage.isMidletRunning(this)) {
				Toast.makeText(this, R.string.save_editor_midlet_running, Toast.LENGTH_LONG).show();
				return;
			}
			if (hits != null) {
				SaveHit hit = hits.get(position);
				RecordEditorActivity.start(this, hit.getRecord().getFile().getPath(), hit.getOffset());
			} else {
				RecordEditorActivity.start(this, records.get(position).getFile().getPath(), -1);
			}
		});

		findViewById(R.id.save_editor_search).setOnClickListener(v -> onSearch(false));
		findViewById(R.id.save_editor_filter).setOnClickListener(v -> onSearch(true));
		findViewById(R.id.save_editor_clear).setOnClickListener(v -> {
			hits = null;
			showRows();
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Reload, the records may have been changed in the editor or by the game.
		records.clear();
		records.addAll(RmsStorage.list(dataDir));
		if (hits != null) {
			// Refresh the shown values without dropping hits whose value changed.
			hits = SaveSearch.refresh(hits);
		}
		showRows();
	}

	private void onSearch(boolean filter) {
		String text = valueInput.getText().toString();
		if (text.isEmpty()) {
			Toast.makeText(this, R.string.memory_search_invalid_value, Toast.LENGTH_SHORT).show();
			return;
		}
		if (filter && hits == null) {
			Toast.makeText(this, R.string.memory_search_need_results, Toast.LENGTH_SHORT).show();
			return;
		}
		hits = filter ? SaveSearch.filter(hits, text) : SaveSearch.find(records, text);
		showRows();
	}

	private void showRows() {
		adapter.clear();
		if (hits == null) {
			for (RmsRecord r : records) {
				adapter.add(getString(R.string.save_editor_record, r.getStoreName(), r.getId(), r.getData().length));
			}
			status.setText(getString(R.string.save_editor_records, records.size()));
		} else {
			String[] types = getResources().getStringArray(R.array.save_editor_types);
			int shown = Math.min(hits.size(), SaveSearch.MAX_HITS);
			for (int i = 0; i < shown; i++) {
				SaveHit h = hits.get(i);
				RmsRecord r = h.getRecord();
				String value = h.getCodec().decode(r.getData(), h.getOffset(), h.getLength());
				adapter.add(String.format(Locale.ROOT, "%s #%d  0x%04X  %s\n%s = %s", r.getStoreName(), r.getId(),
						h.getOffset(), types[h.getCodec().ordinal()], getString(R.string.memory_search_value),
						value == null ? "?" : value));
			}
			status.setText(getString(R.string.save_editor_found, hits.size()));
		}
		adapter.notifyDataSetChanged();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
