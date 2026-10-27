package ru.playsoftware.j2meloader.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;

public final class LogViewerDialog {
	private LogViewerDialog() {
	}

	public static void show(Context context) {
		TextView content = new TextView(context);
		content.setTextSize(12);
		content.setTypeface(Typeface.MONOSPACE);
		content.setTextIsSelectable(true);
		int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
		content.setPadding(padding, padding, padding, padding);
		ScrollView scroll = new ScrollView(context);
		scroll.addView(content);
		CompositeDisposable tasks = new CompositeDisposable();
		AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(R.string.view_log).setView(scroll)
				.setPositiveButton(android.R.string.ok, null)
				.setNeutralButton(R.string.copy_log, null)
				.setNegativeButton(R.string.refresh_log, null).create();
		Context appContext = context.getApplicationContext();
		Runnable refresh = () -> {
			content.setText(R.string.loading_log);
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
			tasks.add(Single.fromCallable(() -> LogUtils.readLog(appContext))
					.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
					.subscribe(log -> {
						if (dialog.isShowing()) {
							content.setText(log);
							scroll.scrollTo(0, 0);
							dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
							dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
						}
					}, error -> {
						if (dialog.isShowing()) {
							content.setText(R.string.load_log_failed);
							dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
						}
					}));
		};
		dialog.setOnDismissListener(ignored -> tasks.dispose());
		dialog.setOnShowListener(ignored -> {
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> refresh.run());
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
				ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
				if (clipboard != null) {
					clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.view_log), content.getText()));
					Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show();
				}
			});
			refresh.run();
		});
		dialog.show();
	}
}
