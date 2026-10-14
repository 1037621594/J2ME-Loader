package ru.playsoftware.j2meloader.memsearch;

import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.midlet.MIDlet;
import javax.microedition.shell.AppClassLoader;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;

import ru.playsoftware.j2meloader.R;

public class MemorySearchPanel {
	private static final long REFRESH_MS = 500;
	private static final int MAX_WIDTH_DP = 420;
	private static final float HEIGHT_RATIO = 0.8f;

	private final MicroActivity activity;
	private final ViewGroup host;
	private final LockManager lockManager = new LockManager();
	private final Handler uiHandler = new Handler(Looper.getMainLooper());
	private final List<MemoryHit> hits = new ArrayList<>();

	private View panel;
	private Spinner typeSpinner;
	private Spinner modeSpinner;
	private EditText value1;
	private EditText value2;
	private Button firstButton;
	private Button nextButton;
	private TextView status;
	private CheckBox pauseGameCheck;
	private HitAdapter adapter;

	private AtomicBoolean cancelToken = new AtomicBoolean();
	private boolean scanning;
	private boolean destroyed;
	private boolean truncated;
	private boolean visitLimited;
	private long totalHits;
	private boolean gamePaused;
	private ValueType scanType;
	/** Simple name of the class whose instances are listed, null for a value search. */
	private String listingClass;

	private final Runnable refresh = new Runnable() {
		@Override
		public void run() {
			if (panel != null && panel.getVisibility() == View.VISIBLE) {
				if (!hits.isEmpty()) {
					adapter.notifyDataSetChanged();
				}
				uiHandler.postDelayed(this, REFRESH_MS);
			}
		}
	};

	public MemorySearchPanel(MicroActivity activity, ViewGroup host) {
		this.activity = activity;
		this.host = host;
	}

	public void toggle() {
		if (destroyed) {
			return;
		}
		if (panel == null) {
			create();
		}
		if (panel.getVisibility() == View.VISIBLE) {
			minimize();
		} else {
			show();
		}
	}

	public void destroy() {
		destroyed = true;
		cancelToken.set(true);
		uiHandler.removeCallbacks(refresh);
		lockManager.unlockAll();
		resumeGameIfPaused();
	}

	private void create() {
		panel = LayoutInflater.from(activity).inflate(R.layout.memory_search_panel, host, false);
		panel.setVisibility(View.GONE);
		host.addView(panel);

		typeSpinner = panel.findViewById(R.id.memory_search_type);
		modeSpinner = panel.findViewById(R.id.memory_search_mode);
		value1 = panel.findViewById(R.id.memory_search_value1);
		value2 = panel.findViewById(R.id.memory_search_value2);
		firstButton = panel.findViewById(R.id.memory_search_first);
		nextButton = panel.findViewById(R.id.memory_search_next);
		status = panel.findViewById(R.id.memory_search_status);
		pauseGameCheck = panel.findViewById(R.id.memory_search_pause_game);
		ListView list = panel.findViewById(R.id.memory_search_list);

		typeSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
				ValueType.values()));
		modeSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
				activity.getResources().getStringArray(R.array.memory_search_modes)));

		typeSpinner.setOnItemSelectedListener(new SimpleSelectionListener() {
			@Override
			void onSelected() {
				int inputType = inputTypeFor(selectedType());
				value1.setInputType(inputType);
				value2.setInputType(inputType);
			}
		});
		modeSpinner.setOnItemSelectedListener(new SimpleSelectionListener() {
			@Override
			void onSelected() {
				SearchMode mode = selectedMode();
				value1.setVisibility(mode.isPreviousBased() ? View.GONE : View.VISIBLE);
				value2.setVisibility(mode.isRanged() ? View.VISIBLE : View.GONE);
			}
		});

		adapter = new HitAdapter();
		list.setAdapter(adapter);
		list.setOnItemClickListener((parent, view, position, id) -> showEditDialog(hits.get(position)));
		list.setOnItemLongClickListener((parent, view, position, id) -> {
			showHitMenu(hits.get(position));
			return true;
		});

		panel.findViewById(R.id.memory_search_minimize).setOnClickListener(v -> minimize());
		firstButton.setOnClickListener(v -> onFirstScan());
		nextButton.setOnClickListener(v -> onNextScan());
		panel.findViewById(R.id.memory_search_reset).setOnClickListener(v -> onReset());
		panel.findViewById(R.id.memory_search_unlock_all).setOnClickListener(v -> {
			lockManager.unlockAll();
			adapter.notifyDataSetChanged();
			updateStatus();
		});
		pauseGameCheck.setOnCheckedChangeListener((view, checked) -> {
			if (checked) {
				pauseGameIfRunning();
			} else if (gamePaused) {
				resumeGameIfPaused();
			}
		});
		updateStatus();
	}

	private void show() {
		pauseGameIfRunning();
		DisplayMetrics dm = activity.getResources().getDisplayMetrics();
		int hostWidth = host.getWidth() > 0 ? host.getWidth() : dm.widthPixels;
		int hostHeight = host.getHeight() > 0 ? host.getHeight() : dm.heightPixels;
		int width = Math.min(hostWidth, (int) (MAX_WIDTH_DP * dm.density));
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, (int) (hostHeight * HEIGHT_RATIO),
				Gravity.TOP | Gravity.END);
		panel.setLayoutParams(lp);
		panel.setVisibility(View.VISIBLE);
		panel.bringToFront();
		updateStatus();
		uiHandler.removeCallbacks(refresh);
		uiHandler.postDelayed(refresh, REFRESH_MS);
	}

	private void minimize() {
		resumeGameIfPaused();
		uiHandler.removeCallbacks(refresh);
		InputMethodManager imm = (InputMethodManager) activity.getSystemService(MicroActivity.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.hideSoftInputFromWindow(panel.getWindowToken(), 0);
		}
		panel.setVisibility(View.GONE);
		panel.setAlpha(1f);
		panel.setTranslationZ(0f);
		host.invalidate();
	}

	/**
	 * Pauses the MIDlet so a scan reads a stable snapshot. This goes through the game's own
	 * pauseApp()/startApp() callbacks, so a game may show its pause menu, auto-save, or refuse
	 * to resume, and a game throwing in pauseApp() is destroyed. Hence off by default.
	 */
	private void pauseGameIfRunning() {
		if (pauseGameCheck.isChecked() && MidletThread.isRunning()) {
			MidletThread.pauseApp();
			gamePaused = true;
		}
	}

	/** Resumes only a game this panel paused, leaving a game paused by the user alone. */
	private void resumeGameIfPaused() {
		if (!gamePaused) {
			return;
		}
		gamePaused = false;
		MidletThread.resumeApp();
	}

	private ValueType selectedType() {
		return ValueType.values()[typeSpinner.getSelectedItemPosition()];
	}

	private SearchMode selectedMode() {
		return SearchMode.values()[modeSpinner.getSelectedItemPosition()];
	}

	private static int inputTypeFor(ValueType type) {
		if (type.isInteger()) {
			return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
		}
		if (type.isFloating()) {
			return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
					| InputType.TYPE_NUMBER_FLAG_DECIMAL;
		}
		return InputType.TYPE_CLASS_TEXT;
	}

	private void toast(int resId) {
		Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show();
	}

	private void onFirstScan() {
		if (scanning) {
			return;
		}
		ValueType type = selectedType();
		SearchMode mode = selectedMode();
		if (mode.isPreviousBased()) {
			toast(R.string.memory_search_need_first);
			return;
		}
		if (!type.isNumeric() && mode != SearchMode.EQUAL) {
			toast(R.string.memory_search_mode_not_supported);
			return;
		}
		Object[] values = parseValues(type, mode);
		if (values == null) {
			return;
		}
		MIDlet midlet = MidletThread.getMidlet();
		if (midlet == null) {
			toast(R.string.memory_search_not_running);
			return;
		}
		Object[] roots = {midlet, activity.getCurrent()};
		ClassLoader loader = midlet.getClass().getClassLoader();
		String dexPath = AppClassLoader.getDexPath();
		AtomicBoolean token = beginScan();
		new Thread(() -> {
			try {
				List<String> names = MemoryScanner.listClassNames(dexPath);
				ScanResult result = MemoryScanner.firstScan(loader, roots, names, type, mode,
						values[0], values[1], token);
				activity.runOnUiThread(() -> finishScan(token, result, type));
			} catch (Throwable t) {
				activity.runOnUiThread(() -> failScan(token, t));
			}
		}, "MemorySearch").start();
	}

	private void onNextScan() {
		if (scanning) {
			return;
		}
		if (hits.isEmpty() || scanType == null) {
			toast(R.string.memory_search_need_results);
			return;
		}
		ValueType type = scanType;
		SearchMode mode = selectedMode();
		boolean numericOnly = mode == SearchMode.GREATER || mode == SearchMode.LESS
				|| mode == SearchMode.RANGE || mode == SearchMode.INCREASED || mode == SearchMode.DECREASED;
		if (!type.isNumeric() && numericOnly) {
			toast(R.string.memory_search_mode_not_supported);
			return;
		}
		Object[] values = parseValues(type, mode);
		if (values == null) {
			return;
		}
		List<MemoryHit> snapshot = new ArrayList<>(hits);
		AtomicBoolean token = beginScan();
		new Thread(() -> {
			try {
				ScanResult result = MemoryScanner.rescan(snapshot, mode, values[0], values[1], token);
				activity.runOnUiThread(() -> finishScan(token, result, type));
			} catch (Throwable t) {
				activity.runOnUiThread(() -> failScan(token, t));
			}
		}, "MemorySearch").start();
	}

	/** Returns {v1, v2}, or null (after informing the user) when the input is invalid. */
	private Object[] parseValues(ValueType type, SearchMode mode) {
		Object v1 = null;
		Object v2 = null;
		if (!mode.isPreviousBased()) {
			try {
				v1 = type.parse(value1.getText().toString());
				if (mode.isRanged()) {
					v2 = type.parse(value2.getText().toString());
					if (((Number) v1).doubleValue() > ((Number) v2).doubleValue()) {
						Object tmp = v1;
						v1 = v2;
						v2 = tmp;
					}
				}
			} catch (NumberFormatException e) {
				toast(R.string.memory_search_invalid_value);
				return null;
			}
		}
		return new Object[]{v1, v2};
	}

	private AtomicBoolean beginScan() {
		cancelToken = new AtomicBoolean();
		scanning = true;
		firstButton.setEnabled(false);
		nextButton.setEnabled(false);
		status.setText(R.string.memory_search_scanning);
		return cancelToken;
	}

	private void endScan() {
		scanning = false;
		firstButton.setEnabled(true);
		nextButton.setEnabled(true);
	}

	private void finishScan(AtomicBoolean token, ScanResult result, ValueType type) {
		if (destroyed || token.get()) {
			return;
		}
		endScan();
		hits.clear();
		hits.addAll(result.getHits());
		scanType = type;
		// A next scan keeps filtering the listing, a first scan or listing sets it again.
		if (result.getVisited() > 0) {
			listingClass = null;
		}
		totalHits = result.getTotalHits();
		truncated = result.isTruncated();
		visitLimited = result.isVisitLimited();
		adapter.notifyDataSetChanged();
		updateStatus();
	}

	private void showHitMenu(MemoryHit hit) {
		Class<?> cls = hit.getOwnerClass();
		String fullName = cls.getName();
		String name = fullName.substring(fullName.lastIndexOf('.') + 1);
		String[] items = {activity.getString(R.string.memory_search_list_instances, name)};
		new AlertDialog.Builder(activity)
				.setTitle(hit.label())
				.setItems(items, (d, which) -> onListInstances(cls, name))
				.show();
	}

	/** Replaces the results with every field of every reachable instance of {@code cls}. */
	private void onListInstances(Class<?> cls, String displayName) {
		if (scanning) {
			return;
		}
		if (cls.getName().startsWith("java.")) {
			// HashMap$Node & co. are container internals, listing them is noise.
			toast(R.string.memory_search_list_instances_unsupported);
			return;
		}
		MIDlet midlet = MidletThread.getMidlet();
		if (midlet == null) {
			toast(R.string.memory_search_not_running);
			return;
		}
		Object[] roots = {midlet, activity.getCurrent()};
		ClassLoader loader = midlet.getClass().getClassLoader();
		String dexPath = AppClassLoader.getDexPath();
		AtomicBoolean token = beginScan();
		new Thread(() -> {
			try {
				List<String> names = MemoryScanner.listClassNames(dexPath);
				ScanResult result = MemoryScanner.findInstances(loader, roots, names, cls, token);
				activity.runOnUiThread(() -> {
					finishScan(token, result, ValueType.ANY);
					if (!destroyed && !token.get()) {
						listingClass = displayName;
						updateStatus();
					}
				});
			} catch (Throwable t) {
				activity.runOnUiThread(() -> failScan(token, t));
			}
		}, "MemorySearch").start();
	}

	private void failScan(AtomicBoolean token, Throwable t) {
		if (destroyed || token.get()) {
			return;
		}
		endScan();
		updateStatus();
		Toast.makeText(activity, activity.getString(R.string.memory_search_failed, String.valueOf(t)),
				Toast.LENGTH_LONG).show();
	}

	private void onReset() {
		cancelToken.set(true);
		endScan();
		hits.clear();
		scanType = null;
		listingClass = null;
		totalHits = 0;
		truncated = false;
		visitLimited = false;
		adapter.notifyDataSetChanged();
		updateStatus();
	}

	private void updateStatus() {
		String text = activity.getString(R.string.memory_search_status, hits.size(), lockManager.size());
		if (listingClass != null) {
			text = activity.getString(R.string.memory_search_listing, listingClass) + " " + text;
		}
		if (truncated) {
			text += " " + activity.getString(R.string.memory_search_truncated, totalHits - hits.size());
		}
		if (visitLimited) {
			text += " " + activity.getString(R.string.memory_search_visit_limited);
		}
		status.setText(text);
	}

	private void showEditDialog(MemoryHit hit) {
		EditText input = new EditText(activity);
		input.setInputType(inputTypeFor(hit.getType()));
		input.setSingleLine(true);
		try {
			input.setText(hit.getType().format(MemoryScanner.read(hit)));
		} catch (Throwable ignored) {
		}
		input.setSelectAllOnFocus(true);
		AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(hit.label()).setView(input);
		if (hit.isReadOnly()) {
			input.setEnabled(false);
			builder.setMessage(activity.getString(R.string.memory_search_read_only))
					.setNegativeButton(android.R.string.cancel, null);
		} else {
			builder.setPositiveButton(R.string.memory_search_write,
					(d, w) -> applyValue(hit, input.getText().toString(), false))
					.setNegativeButton(android.R.string.cancel, null);
			if (hit.isLocked()) {
				builder.setNeutralButton(R.string.memory_search_unlock, (d, w) -> {
					lockManager.unlock(hit);
					adapter.notifyDataSetChanged();
					updateStatus();
				});
			} else {
				builder.setNeutralButton(R.string.memory_search_lock,
						(d, w) -> applyValue(hit, input.getText().toString(), true));
			}
		}
		builder.show();
	}

	private void applyValue(MemoryHit hit, String text, boolean lock) {
		try {
			Object value = hit.getType().parse(text);
			MemoryScanner.write(hit, value);
			if (lock) {
				lockManager.lock(hit, value);
			}
			adapter.notifyDataSetChanged();
			updateStatus();
		} catch (NumberFormatException e) {
			toast(R.string.memory_search_invalid_value);
		} catch (Throwable t) {
			toast(R.string.memory_search_write_failed);
		}
	}

	private abstract static class SimpleSelectionListener implements AdapterView.OnItemSelectedListener {
		abstract void onSelected();

		@Override
		public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
			onSelected();
		}

		@Override
		public void onNothingSelected(AdapterView<?> parent) {
		}
	}

	private class HitAdapter extends BaseAdapter {
		@Override
		public int getCount() {
			return hits.size();
		}

		@Override
		public Object getItem(int position) {
			return hits.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View row = convertView != null ? convertView
					: LayoutInflater.from(activity).inflate(android.R.layout.simple_list_item_2, parent, false);
			MemoryHit hit = hits.get(position);
			((TextView) row.findViewById(android.R.id.text1)).setText(hit.label());
			String value;
			try {
				value = hit.getType().format(MemoryScanner.read(hit));
			} catch (Throwable t) {
				value = "?";
			}
			if (hit.isLocked()) {
				value += "  [" + activity.getString(R.string.memory_search_lock) + " = "
						+ hit.getType().format(hit.getLockValue()) + "]";
			} else if (hit.isReadOnly()) {
				value += "  [" + activity.getString(R.string.memory_search_read_only) + "]";
			}
			((TextView) row.findViewById(android.R.id.text2)).setText(value);
			return row;
		}
	}
}
