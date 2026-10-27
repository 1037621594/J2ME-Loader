package ru.playsoftware.j2meloader.backup;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.net.URISyntaxException;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public final class QiniuBackupConfig {
	private final boolean enabled;
	private final String bucket;
	private final String accessKey;
	private final String secretKey;
	private final String uploadUrl;

	public static QiniuBackupConfig load(Context context) {
		SharedPreferences prefs = context.getSharedPreferences("qiniu_backup", Context.MODE_PRIVATE);
		return new QiniuBackupConfig(prefs.getBoolean("enabled", false),
				prefs.getString("bucket", ""), prefs.getString("access_key", ""),
				prefs.getString("secret_key", ""), prefs.getString("upload_url", "https://upload.qiniup.com"));
	}

	public void save(Context context) {
		context.getSharedPreferences("qiniu_backup", Context.MODE_PRIVATE).edit()
				.putBoolean("enabled", enabled).putString("bucket", bucket)
				.putString("access_key", accessKey).putString("secret_key", secretKey)
				.putString("upload_url", uploadUrl).apply();
	}

	public boolean isValid() {
		if (bucket.isEmpty() || bucket.contains(":") || accessKey.isEmpty() || secretKey.isEmpty()) {
			return false;
		}
		try {
			URI uri = new URI(uploadUrl);
			return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
					&& uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
					&& (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
		} catch (URISyntaxException e) {
			return false;
		}
	}
}
