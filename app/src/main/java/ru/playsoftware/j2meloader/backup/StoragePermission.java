package ru.playsoftware.j2meloader.backup;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public final class StoragePermission {
	private static final String TAG = StoragePermission.class.getSimpleName();
	private static final int REQUEST_CODE = 0x4A47;

	private StoragePermission() {
	}

	public static boolean hasAccess(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			return Environment.isExternalStorageManager();
		}
		return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				== PackageManager.PERMISSION_GRANTED;
	}

	public static void request(Activity activity) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			ActivityCompat.requestPermissions(activity,
					new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE);
			return;
		}
		try {
			Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
					Uri.parse("package:" + activity.getPackageName()));
			activity.startActivity(intent);
		} catch (Exception e) {
			Log.w(TAG, "Can't open per-app settings, fallback to global list", e);
			try {
				activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
			} catch (Exception e2) {
				Log.e(TAG, "Can't open all files access settings", e2);
			}
		}
	}
}
