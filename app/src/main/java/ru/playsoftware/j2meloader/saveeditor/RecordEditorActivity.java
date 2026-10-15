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
 * Edits one RMS record. It opens with every value of the record parsed out (see
 * {@link RmsParser}); find/filter/go to offset narrow it down like a memory search.
 * Edits stay in memory until saved; the first save keeps the original as {@code .rsr.bak}.
 */
public class RecordEditorActivity extends BaseActivity {
	private static final String EXTRA_FILE = "file";
	private static final String EXTRA_OFFSET = "offset";
	/** Values shown by "go to offset". */
	private static final int GOTO_COUNT = 64;
	private static final int MAX_ROWS = 2000;

	/** The values listed, each with its own type; parsed fields mix ints and text. */
	private final List<RmsField> fields = new ArrayList<>();
	private final List<String> rows = new ArrayList<>();
	private ArrayAdapter<String> adapter;
	private Spinner typeSpinner;
	private EditText valueInput;
	private EditText offsetInput;
	private TextView info;
	private TextView status;
	private String[] typeNames;

	private File file;
	private RmsRecord record;
	private boolean dirty;
	/** Modification time of the file when it was last loaded or saved. */
	private long lastLoaded;
	/** True while the parsed view is shown, so a reload parses again. */
	private boolean parsedView;

	/** @param offset offset to show first, or -1 to show all parsed values */
	public static void start(Context context, String filePath, int offset) {
		Intent intent = new Intent(context, RecordEditorActivity.class);
		intent.putExtra(EXTRA_FILE, filePath);
		intent.putExtra(EXTRA_OFFSET, offset);
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

		typeNames = getResources().getStringArray(R.array.save_editor_types);
		typeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, typeNames));
		typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				valueInput.setInputType(selectedCodec() == ValueCodec.TEXT ? InputType.TYPE_CLASS_TEXT
						: InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) -> showEditDialog(fields.get(position)));

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
		if (record == null) {
			return;
		}
		int offset = getIntent().getIntExtra(EXTRA_OFFSET, -1);
		if (offset >= 0) {
			offsetInput.setText(String.valueOf(offset));
			gotoOffset(offset);
		} else {
			showParsed();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Pick up changes the game wrote meanwhile, unless there are unsaved edits.
		if (!dirty && record != null && file.lastModified() != lastLoaded) {
			load();
			if (parsedView) {
				showParsed();
			} else {
				refreshRows();
			}
		}
	}

	private void load() {
		try {
			record = RmsStorage.read(file);
			lastLoaded = file.lastModified();
			dirty = false;
			updateInfo();
		} catch (IOException e) {
			record = null;
			Toast.makeText(this, getString(R.string.save_editor_load_failed, String.valueOf(e)),
					Toast.LENGTH_LONG).show();
			finish();
		}
	}

	private ValueCodec selectedCodec() {
		return ValueCodec.values()[typeSpinner.getSelectedItemPosition()];
	}

	/** Lists every value of the record as parsed by {@link RmsParser}. */
	private void showParsed() {
		fields.clear();
		List<RmsField> parsed = RmsParser.parse(record.getData());
		fields.addAll(parsed.subList(0, Math.min(parsed.size(), MAX_ROWS)));
		parsedView = true;
		refreshRows();
		status.setText(getString(R.string.save_editor_parsed, parsed.size()));
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
		if (filter && fields.isEmpty()) {
			Toast.makeText(this, R.string.memory_search_need_results, Toast.LENGTH_SHORT).show();
			return;
		}
		List<Integer> found = ValueCodec.find(record.getData(), pattern);
		List<RmsField> result = new ArrayList<>();
		for (int off : found) {
			if (!filter || containsOffset(off)) {
				result.add(new RmsField(off, codec, pattern.length));
			}
		}
		fields.clear();
		fields.addAll(result.subList(0, Math.min(result.size(), MAX_ROWS)));
		parsedView = false;
		refreshRows();
		status.setText(getString(R.string.save_editor_found, result.size()));
	}

	private boolean containsOffset(int offset) {
		for (RmsField f : fields) {
			if (f.getOffset() == offset) {
				return true;
			}
		}
		return false;
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
		gotoOffset(start);
	}

	private void gotoOffset(int start) {
		int length = record.getData().length;
		if (start < 0 || start >= length) {
			Toast.makeText(this, R.string.save_editor_offset_out_of_range, Toast.LENGTH_SHORT).show();
			return;
		}
		ValueCodec codec = selectedCodec();
		if (codec == ValueCodec.TEXT) {
			// Text has no fixed width, so step over the bytes as Int like the parser does.
			codec = ValueCodec.INT32_BE;
		}
		int step = codec.getSize();
		fields.clear();
		for (int i = 0, off = start; i < GOTO_COUNT && off < length; i++, off += step) {
			fields.add(new RmsField(off, codec, step));
		}
		parsedView = false;
		refreshRows();
		status.setText(null);
	}

	private void refreshRows() {
		if (record == null) {
			return;
		}
		byte[] data = record.getData();
		rows.clear();
		for (RmsField f : fields) {
			String value = f.getCodec().decode(data, f.getOffset(), f.getLength());
			StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "0x%04X  %s = %s", f.getOffset(),
					typeNames[f.getCodec().ordinal()], value == null ? "?" : value));
			if (f.getCodec() == ValueCodec.INT32_BE && value != null) {
				// The same 4 bytes read as 2 shorts and 4 bytes, for games that do not write ints.
				sb.append(String.format(Locale.ROOT, "\n    short: %s, %s   byte: %d %d %d %d",
						ValueCodec.INT16_BE.decode(data, f.getOffset(), 2),
						ValueCodec.INT16_BE.decode(data, f.getOffset() + 2, 2),
						data[f.getOffset()] & 0xFF, data[f.getOffset() + 1] & 0xFF,
						data[f.getOffset() + 2] & 0xFF, data[f.getOffset() + 3] & 0xFF));
			}
			rows.add(sb.toString());
		}
		adapter.notifyDataSetChanged();
	}

	private void showEditDialog(RmsField field) {
		if (field.getCodec() != ValueCodec.INT32_BE) {
			showValueDialog(field.getOffset(), field.getCodec(), field.getLength());
			return;
		}
		// An int may really be shorts or bytes, let the user pick how to edit it.
		int off = field.getOffset();
		String[] items = {
				typeNames[ValueCodec.INT32_BE.ordinal()],
				String.format(Locale.ROOT, "%s @0x%04X", typeNames[ValueCodec.INT16_BE.ordinal()], off),
				String.format(Locale.ROOT, "%s @0x%04X", typeNames[ValueCodec.INT16_BE.ordinal()], off + 2),
				String.format(Locale.ROOT, "%s @0x%04X", typeNames[ValueCodec.INT8.ordinal()], off),
				String.format(Locale.ROOT, "%s @0x%04X", typeNames[ValueCodec.INT8.ordinal()], off + 1),
				String.format(Locale.ROOT, "%s @0x%04X", typeNames[ValueCodec.INT8.ordinal()], off + 2),
				String.format(Locale.ROOT, "%s @0x%04X", typeNames[ValueCodec.INT8.ordinal()], off + 3),
		};
		new AlertDialog.Builder(this)
				.setTitle(String.format(Locale.ROOT, "0x%04X", off))
				.setItems(items, (d, which) -> {
					if (which == 0) {
						showValueDialog(off, ValueCodec.INT32_BE, 4);
					} else if (which <= 2) {
						showValueDialog(off + (which - 1) * 2, ValueCodec.INT16_BE, 2);
					} else {
						showValueDialog(off + which - 3, ValueCodec.INT8, 1);
					}
				})
				.show();
	}

	private void showValueDialog(int offset, ValueCodec codec, int length) {
		String current = codec.decode(record.getData(), offset, length);
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
				.setTitle(String.format(Locale.ROOT, "0x%04X  %s", offset, typeNames[codec.ordinal()]))
				.setView(input)
				.setPositiveButton(R.string.memory_search_write, (d, w) -> applyValue(offset, codec, length,
						input.getText().toString()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyValue(int offset, ValueCodec codec, int length, String text) {
		byte[] bytes;
		try {
			bytes = codec.encode(text);
		} catch (NumberFormatException e) {
			Toast.makeText(this, R.string.memory_search_invalid_value, Toast.LENGTH_SHORT).show();
			return;
		}
		// Text must keep its byte length: a length prefix or the following fields would shift.
		if (codec == ValueCodec.TEXT && bytes.length != length) {
			Toast.makeText(this, getString(R.string.save_editor_text_length, length),
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
						showParsed();
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
		} else if (id == R.id.action_record_parse) {
			showParsed();
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
