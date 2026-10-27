package ru.playsoftware.j2meloader.backup;

import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import ru.playsoftware.j2meloader.R;

public final class QiniuBackupDialog {
	private QiniuBackupDialog() {
	}

	public static void show(Context context) {
		QiniuBackupConfig config = QiniuBackupConfig.load(context);
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding / 2, padding, padding / 2);
		TextView help = new TextView(context);
		help.setText(R.string.qiniu_help);
		layout.addView(help);
		CheckBox enabled = new CheckBox(context);
		enabled.setText(R.string.qiniu_enabled);
		enabled.setChecked(config.isEnabled());
		layout.addView(enabled);
		EditText bucket = field(context, layout, R.string.qiniu_bucket, config.getBucket(), false);
		EditText accessKey = field(context, layout, R.string.qiniu_access_key, config.getAccessKey(), true);
		EditText secretKey = field(context, layout, R.string.qiniu_secret_key, config.getSecretKey(), true);
		EditText uploadUrl = field(context, layout, R.string.qiniu_upload_url, config.getUploadUrl(), false);
		ScrollView scroll = new ScrollView(context);
		scroll.addView(layout);
		AlertDialog dialog = new AlertDialog.Builder(context)
				.setTitle(R.string.qiniu_settings).setView(scroll)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, null).create();
		dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
			QiniuBackupConfig updated = new QiniuBackupConfig(enabled.isChecked(),
					bucket.getText().toString().trim(), accessKey.getText().toString().trim(),
					secretKey.getText().toString().trim(), uploadUrl.getText().toString().trim());
			if (updated.isEnabled() && !updated.isValid()) {
				Toast.makeText(context, R.string.qiniu_invalid_config, Toast.LENGTH_LONG).show();
				return;
			}
			updated.save(context);
			dialog.dismiss();
		}));
		dialog.show();
	}

	private static EditText field(Context context, LinearLayout layout, int label, String value, boolean secret) {
		TextView title = new TextView(context);
		title.setText(label);
		layout.addView(title);
		EditText input = new EditText(context);
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
				? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
		input.setText(value);
		input.setSaveEnabled(false);
		layout.addView(input);
		return input;
	}
}
