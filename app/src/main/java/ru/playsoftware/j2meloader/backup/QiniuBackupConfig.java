package ru.playsoftware.j2meloader.backup;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

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
	private final String downloadUrl;
	private final String listUrl;

	public static QiniuBackupConfig load(Context context) {
		SharedPreferences prefs = context.getSharedPreferences("qiniu_backup", Context.MODE_PRIVATE);
		return new QiniuBackupConfig(prefs.getBoolean("enabled", false),
				prefs.getString("bucket", ""), prefs.getString("access_key", ""),
				prefs.getString("secret_key", ""), prefs.getString("upload_url", "https://upload.qiniup.com"),
				prefs.getString("download_url", ""), prefs.getString("list_url", "https://rsf.qiniuapi.com"));
	}

	public void save(Context context) {
		context.getSharedPreferences("qiniu_backup", Context.MODE_PRIVATE).edit()
				.putBoolean("enabled", enabled).putString("bucket", bucket)
				.putString("access_key", accessKey).putString("secret_key", secretKey)
				.putString("upload_url", uploadUrl).putString("download_url", downloadUrl)
				.putString("list_url", listUrl).apply();
	}

	public String exportConfig() throws JSONException {
		return new JSONObject().put("type", "j2me-loader-qiniu-backup").put("version", 1)
				.put("enabled", enabled).put("bucket", bucket).put("accessKey", accessKey)
				.put("secretKey", secretKey).put("uploadUrl", uploadUrl)
				.put("downloadUrl", downloadUrl).put("listUrl", listUrl).toString(2);
	}

	public static QiniuBackupConfig importConfig(String text) throws JSONException {
		if (text.length() > 65536) {
			throw new JSONException("Configuration is too large");
		}
		JSONObject json = new JSONObject(text);
		if (!"j2me-loader-qiniu-backup".equals(json.get("type"))
				|| !Integer.valueOf(1).equals(json.get("version"))
				|| !(json.get("enabled") instanceof Boolean)) {
			throw new JSONException("Unsupported configuration format");
		}
		return new QiniuBackupConfig(json.getBoolean("enabled"), readString(json, "bucket"),
				readString(json, "accessKey"), readString(json, "secretKey"),
				readEndpoint(json, "uploadUrl"), readEndpoint(json, "downloadUrl"),
				readEndpoint(json, "listUrl"));
	}

	private static String readString(JSONObject json, String key) throws JSONException {
		Object value = json.get(key);
		if (!(value instanceof String)) {
			throw new JSONException("Invalid configuration field: " + key);
		}
		return ((String) value).trim();
	}

	private static String readEndpoint(JSONObject json, String key) throws JSONException {
		String value = readString(json, key);
		if (!value.isEmpty() && !isEndpoint(value)) {
			throw new JSONException("Invalid endpoint: " + key);
		}
		return value;
	}

	public boolean isValid() {
		return hasCredentials() && isEndpoint(uploadUrl);
	}

	public boolean isRestoreValid() {
		return hasCredentials() && isEndpoint(downloadUrl) && isEndpoint(listUrl);
	}

	private boolean hasCredentials() {
		return !bucket.isEmpty() && !bucket.contains(":") && !accessKey.isEmpty() && !secretKey.isEmpty();
	}

	private static boolean isEndpoint(String value) {
		try {
			URI uri = new URI(value);
			boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
					|| "https".equalsIgnoreCase(uri.getScheme());
			return supportedScheme && uri.getHost() != null
					&& uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
					&& (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
		} catch (URISyntaxException e) {
			return false;
		}
	}
}
