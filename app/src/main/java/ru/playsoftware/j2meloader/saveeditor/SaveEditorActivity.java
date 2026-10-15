package ru.playsoftware.j2meloader.saveeditor;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.base.BaseActivity;
import ru.playsoftware.j2meloader.config.Config;

/** Lists the RMS records (.rsr files) of one game; a record opens in {@link RecordEditorActivity}. */
public class SaveEditorActivity extends BaseActivity {
	private static final String EXTRA_APP_DIR = "appDir";
	private static final String EXTRA_TITLE = "title";

	private final List<RmsRecord> records = new ArrayList<>();
	private ArrayAdapter<String> adapter;
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
		setContentView(R.layout.activity_profiles);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
		String title = getIntent().getStringExtra(EXTRA_TITLE);
		setTitle(getString(R.string.save_editor_title, title == null ? "" : title));
		File appDir = new File(getIntent().getStringExtra(EXTRA_APP_DIR));
		dataDir = new File(Config.getDataDir(), appDir.getName());

		ListView listView = findViewById(R.id.list_view);
		TextView emptyView = findViewById(R.id.empty_view);
		emptyView.setText(R.string.save_editor_empty);
		listView.setEmptyView(emptyView);
		adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
		listView.setAdapter(adapter);
		listView.setOnItemClickListener((parent, view, position, id) -> {
			if (RmsStorage.isMidletRunning(this)) {
				Toast.makeText(this, R.string.save_editor_midlet_running, Toast.LENGTH_LONG).show();
				return;
			}
			RecordEditorActivity.start(this, records.get(position).getFile().getPath());
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Reload, the record may have been changed in the editor or by the game.
		records.clear();
		records.addAll(RmsStorage.list(dataDir));
		adapter.clear();
		for (RmsRecord r : records) {
			adapter.add(getString(R.string.save_editor_record, r.getStoreName(), r.getId(), r.getData().length));
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
