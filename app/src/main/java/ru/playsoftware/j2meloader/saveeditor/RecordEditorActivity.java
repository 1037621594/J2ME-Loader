package ru.playsoftware.j2meloader.saveeditor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.base.BaseActivity;

/**
 * Edits one RMS record. Works like a memory search on the record bytes: find the current value,
 * change it in game, filter the offsets with the new value, then edit the one that is left.
 * Edits stay in memory until saved; the first save keeps the original as {@code .rsr.bak}.
 */
public class RecordEditorActivity extends BaseActivity {
	private static final String EXTRA_FILE = "file";
	/** Values shown by "go to offset". */
	private static final int GOTO_COUNT = 64;
	private static final int MAX_ROWS = 2000;

	private final List<Integer> offsets = new ArrayList<>();
	private final List<String> rows = new ArrayList<>();
	private ArrayAdapter<String> adapter;
	private Spinner typeSpinner;
	private EditText valueInput;
	private EditText offsetInput;
	private TextView info;
	private TextView status;

	private File file;
	private RmsRecord record;
	private boolean dirty;
	/** Modification time of the file when it was last loaded or saved. */
	private long lastLoaded;
	/** Byte length of the last text search, used to show text values. */
	private int textLength;

	public static void start(Context context, String filePath) {
		Intent intent = new Intent(context, RecordEditorActivity.class);
		intent.putExtra(EXTRA_FILE, filePath);
		context.startActivity(intent);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_record_editor);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
		file = new File(getIntent().getStringExtra(EXTRA_FILE));
		setTitle(file.getName());

		info = findViewById(R.id.record_editor_info);
		status = findViewById(R.id.record_editor_status);
		typeSpinner = findViewById(R.id.record_editor_type);
		valueInput = findViewById(R.id.record_editor_value);
		offsetInput = findViewById(R.id.record_editor_offset);
		ListView list = findViewById(R.id.record_editor_list);

		typeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
				getResources().getStringArray(R.array.save_editor_types)));
		typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				valueInput.setInputType(selectedCodec() == ValueCodec.TEXT ? InputType.TYPE_CLASS_TEXT
						: InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
				refreshRows();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) -> showEditDialog(offsets.get(position)));

		findViewById(R.id.record_editor_find).setOnClickListener(v -> onFind(false));
		findViewById(R.id.record_editor_filter).setOnClickListener(v -> onFind(true));
		findViewById(R.id.record_editor_goto).setOnClickListener(v -> onGoto());

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				confirmLeave();
			}
		});
		load();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Pick up changes the game wrote meanwhile, unless there are unsaved edits.
		if (!dirty && record != null && file.lastModified() != lastLoaded) {
			load();
			refreshRows();
		}
	}

	private void load() {
		try {
			record = RmsStorage.read(file);
			lastLoaded = file.lastModified();
			dirty = false;
			updateInfo();
		} catch (IOException e) {
			Toast.makeText(this, getString(R.string.save_editor_load_failed, String.valueOf(e)),
					Toast.LENGTH_LONG).show();
			finish();
		}
	}

	private ValueCodec selectedCodec() {
		return ValueCodec.values()[typeSpinner.getSelectedItemPosition()];
	}

	/** Find lists every offset holding the value; filter keeps only the listed ones that do. */
	private void onFind(boolean filter) {
		if (record == null) {
			return;
		}
		ValueCodec codec = selectedCodec();
		byte[] pattern;
		try {
			pattern = codec.encode(valueInput.getText().toString());
		} catch (NumberFormatException e) {
			Toast.makeText(this, R.string.memory_search_invalid_value, Toast.LENGTH_SHORT).show();
			return;
		}
		if (filter && offsets.isEmpty()) {
			Toast.makeText(this, R.string.memory_search_need_results, Toast.LENGTH_SHORT).show();
			return;
		}
		textLength = pattern.length;
		List<Integer> found = ValueCodec.find(record.getData(), pattern);
		if (filter) {
			found.retainAll(offsets);
		}
		offsets.clear();
		offsets.addAll(found.subList(0, Math.min(found.size(), MAX_ROWS)));
		refreshRows();
		status.setText(getString(R.string.save_editor_found, found.size()));
	}

	/** Lists consecutive values of the selected type from the given offset (decimal or 0x hex). */
	private void onGoto() {
		if (record == null) {
			return;
		}
		int start;
		try {
			String s = offsetInput.getText().toString().trim().toLowerCase(Locale.ROOT);
			start = s.startsWith("0x") ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s);
		} catch (NumberFormatException e) {
			Toast.makeText(this, R.string.memory_search_invalid_value, Toast.LENGTH_SHORT).show();
			return;
		}
		int length = record.getData().length;
		if (start < 0 || start >= length) {
			Toast.makeText(this, R.string.save_editor_offset_out_of_range, Toast.LENGTH_SHORT).show();
			return;
		}
		ValueCodec codec = selectedCodec();
		int step = codec == ValueCodec.TEXT ? Math.max(textLength, 1) : codec.getSize();
		offsets.clear();
		for (int i = 0, off = start; i < GOTO_COUNT && off < length; i++, off += step) {
			offsets.add(off);
		}
		refreshRows();
		status.setText(null);
	}

	private void refreshRows() {
		if (record == null) {
			return;
		}
		ValueCodec codec = selectedCodec();
		rows.clear();
		for (int off : offsets) {
			String value = codec.decode(record.getData(), off, Math.max(textLength, 1));
			rows.add(String.format(Locale.ROOT, "0x%04X (%d):  %s", off, off, value == null ? "?" : value));
		}
		adapter.notifyDataSetChanged();
	}

	private void showEditDialog(int offset) {
		ValueCodec codec = selectedCodec();
		String current = codec.decode(record.getData(), offset, Math.max(textLength, 1));
		if (current == null) {
			Toast.makeText(this, R.string.save_editor_offset_out_of_range, Toast.LENGTH_SHORT).show();
			return;
		}
		EditText input = new EditText(this);
		input.setSingleLine(true);
		input.setInputType(codec == ValueCodec.TEXT ? InputType.TYPE_CLASS_TEXT
				: InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
		input.setText(current);
		input.setSelectAllOnFocus(true);
		new AlertDialog.Builder(this)
				.setTitle(String.format(Locale.ROOT, "0x%04X", offset))
				.setView(input)
				.setPositiveButton(R.string.memory_search_write, (d, w) -> applyValue(offset, codec,
						input.getText().toString()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyValue(int offset, ValueCodec codec, String text) {
		byte[] bytes;
		try {
			bytes = codec.encode(text);
		} catch (NumberFormatException e) {
			Toast.makeText(this, R.string.memory_search_invalid_value, Toast.LENGTH_SHORT).show();
			return;
		}
		// Text must keep its byte length: a length prefix or the following fields would shift.
		if (codec == ValueCodec.TEXT && bytes.length != textLength) {
			Toast.makeText(this, getString(R.string.save_editor_text_length, textLength),
					Toast.LENGTH_LONG).show();
			return;
		}
		byte[] data = record.getData();
		if (offset + bytes.length > data.length) {
			Toast.makeText(this, R.string.save_editor_offset_out_of_range, Toast.LENGTH_SHORT).show();
			return;
		}
		System.arraycopy(bytes, 0, data, offset, bytes.length);
		dirty = true;
		updateInfo();
		refreshRows();
	}

	private void updateInfo() {
		String text = getString(R.string.save_editor_info, record.getStoreName(), record.getId(),
				record.getData().length);
		if (dirty) {
			text += "  " + getString(R.string.save_editor_unsaved);
		}
		info.setText(text);
	}

	private boolean save() {
		if (RmsStorage.isMidletRunning(this)) {
			// The game caches records in memory and would overwrite the file on its next save.
			Toast.makeText(this, R.string.save_editor_midlet_running, Toast.LENGTH_LONG).show();
			return false;
		}
		try {
			RmsStorage.write(record);
			lastLoaded = file.lastModified();
			dirty = false;
			updateInfo();
			Toast.makeText(this, R.string.save_editor_saved, Toast.LENGTH_SHORT).show();
			return true;
		} catch (IOException e) {
			Toast.makeText(this, getString(R.string.save_editor_save_failed, String.valueOf(e)),
					Toast.LENGTH_LONG).show();
			return false;
		}
	}

	private void restoreOriginal() {
		File backup = new File(file.getPath() + ".bak");
		if (!backup.isFile()) {
			Toast.makeText(this, R.string.save_editor_no_backup, Toast.LENGTH_SHORT).show();
			return;
		}
		if (RmsStorage.isMidletRunning(this)) {
			Toast.makeText(this, R.string.save_editor_midlet_running, Toast.LENGTH_LONG).show();
			return;
		}
		new AlertDialog.Builder(this)
				.setMessage(R.string.save_editor_restore_confirm)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					try {
						copy(backup, file);
						load();
						refreshRows();
						Toast.makeText(this, R.string.restore_done, Toast.LENGTH_SHORT).show();
					} catch (IOException e) {
						Toast.makeText(this, getString(R.string.save_editor_save_failed, String.valueOf(e)),
								Toast.LENGTH_LONG).show();
					}
				})
				.show();
	}

	private void showHexDump() {
		byte[] data = record.getData();
		StringBuilder sb = new StringBuilder();
		for (int row = 0; row < data.length; row += 8) {
			sb.append(String.format(Locale.ROOT, "%04X ", row));
			for (int i = row; i < row + 8; i++) {
				sb.append(i < data.length ? String.format(Locale.ROOT, " %02X", data[i] & 0xFF) : "   ");
			}
			sb.append("  ");
			for (int i = row; i < row + 8 && i < data.length; i++) {
				int c = data[i] & 0xFF;
				sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
			}
			sb.append('\n');
		}
		TextView text = new TextView(this);
		text.setTypeface(Typeface.MONOSPACE);
		text.setTextIsSelectable(true);
		text.setText(sb);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		text.setPadding(pad, pad, pad, pad);
		ScrollView scroll = new ScrollView(this);
		scroll.addView(text);
		new AlertDialog.Builder(this)
				.setTitle(R.string.save_editor_hex)
				.setView(scroll)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private void confirmLeave() {
		if (!dirty) {
			finish();
			return;
		}
		new AlertDialog.Builder(this)
				.setMessage(R.string.save_editor_unsaved_confirm)
				.setPositiveButton(R.string.save_editor_save, (d, w) -> {
					if (save()) {
						finish();
					}
				})
				.setNegativeButton(R.string.save_editor_discard, (d, w) -> finish())
				.setNeutralButton(android.R.string.cancel, null)
				.show();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.record_editor, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			confirmLeave();
			return true;
		} else if (id == R.id.action_record_save) {
			save();
			return true;
		} else if (id == R.id.action_record_hex) {
			showHexDump();
			return true;
		} else if (id == R.id.action_record_restore) {
			restoreOriginal();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private static void copy(File src, File dst) throws IOException {
		byte[] buf = new byte[16 * 1024];
		try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
		}
	}
}
