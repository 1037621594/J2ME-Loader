package ru.playsoftware.j2meloader.backup;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.util.LogUtils;

/** Lists and restores the same java-save/*.zip snapshots created by QiniuBackupClient. */
public final class QiniuRestoreClient {
	private static final String PREFIX = "java-save/";
	private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
	private final Context context;
	private final QiniuBackupConfig config;

	public QiniuRestoreClient(Context context, QiniuBackupConfig config) {
		this.context = context.getApplicationContext();
		this.config = config;
	}

	public List<QiniuBackupEntry> listBackups() throws IOException {
		if (!config.isRestoreValid()) {
			throw new IOException("Configure bucket, keys, download domain and list endpoint before restoring");
		}
		LogUtils.diagnostic(context, "Qiniu list start: endpoint=" + config.getListUrl()
				+ ", bucket=" + config.getBucket() + ", prefix=" + PREFIX, null);
		try {
			List<QiniuBackupEntry> result = new ArrayList<>();
			Set<String> keys = new HashSet<>();
			Set<String> markers = new HashSet<>();
			String marker = "";
			do {
				String query = "bucket=" + Uri.encode(config.getBucket()) + "&prefix=" + Uri.encode(PREFIX) + "&limit=1000";
				if (!marker.isEmpty()) {
					query += "&marker=" + Uri.encode(marker);
				}
				URL url = new URL(base(config.getListUrl()) + "/list?" + query);
				HttpURLConnection connection = open(url);
				try {
					String contentType = "application/x-www-form-urlencoded";
					String signingData = "GET " + url.getFile() + "\nHost: " + url.getAuthority()
							+ "\nContent-Type: " + contentType + "\n\n";
					connection.setRequestProperty("Content-Type", contentType);
					connection.setRequestProperty("Authorization", "Qiniu " + sign(signingData));
					requireSuccess(connection, "list");
					JSONObject page;
					try (InputStream in = connection.getInputStream()) {
						page = new JSONObject(readText(in, 4 * 1024 * 1024));
					}
					JSONArray items = page.optJSONArray("items");
					if (items != null) {
						for (int i = 0; i < items.length(); i++) {
							JSONObject item = items.getJSONObject(i);
							String key = item.getString("key");
							if (isBackupKey(key) && keys.add(key)) {
								result.add(new QiniuBackupEntry(key, item.optLong("fsize", -1),
										item.optLong("putTime", 0) / 10000));
							}
						}
					}
					marker = page.optString("marker", "");
					if (!marker.isEmpty() && !markers.add(marker)) {
						throw new IOException("Qiniu returned a repeated pagination marker");
					}
				} finally {
					connection.disconnect();
				}
			} while (!marker.isEmpty());
			java.util.Collections.sort(result, (left, right) -> left.getModifiedTime() == right.getModifiedTime()
					? 0 : (left.getModifiedTime() < right.getModifiedTime() ? 1 : -1));
			LogUtils.diagnostic(context, "Qiniu list finished: backups=" + result.size(), null);
			return result;
		} catch (JSONException | IOException | RuntimeException e) {
			LogUtils.diagnostic(context, "Qiniu list failed", e);
			throw new IOException("Cannot list Qiniu backups; see logs", e);
		}
	}

	public BackupSummary restore(List<QiniuBackupEntry> entries, AppRepository repository) throws IOException {
		if (!config.isRestoreValid()) {
			throw new IOException("Invalid Qiniu restore configuration");
		}
		int success = 0;
		int skipped = 0;
		int failed = 0;
		for (QiniuBackupEntry entry : entries) {
			File work = new File(context.getCacheDir(), "qiniu-restore-" + UUID.randomUUID());
			try {
				if (!isBackupKey(entry.getKey())) {
					throw new IOException("Invalid backup object key");
				}
				if (!work.mkdirs()) {
					throw new IOException("Cannot create restore staging directory");
				}
				LogUtils.diagnostic(context, "Qiniu restore start: key=" + entry.getKey()
						+ ", downloadDomain=" + config.getDownloadUrl(), null);
				File archive = new File(work, "backup.zip");
				download(entry, archive);
				File extracted = new File(work, "snapshot");
				extract(archive, extracted);
				BackupMeta meta = BackupManager.readMeta(extracted);
				if (meta == null || !entry.getKey().equals(PREFIX
						+ BackupManager.folderName(meta.getName(), meta.getVendor()) + ".zip")) {
					throw new IOException("Backup metadata does not match the selected object");
				}
				if (BackupManager.importBackup(extracted, repository, true)) {
					success++;
					LogUtils.diagnostic(context, "Qiniu restore succeeded: " + entry.getKey(), null);
				} else {
					skipped++;
					LogUtils.diagnostic(context, "Qiniu restore skipped: game not installed and snapshot has no game: "
							+ entry.getKey(), null);
				}
			} catch (IOException | RuntimeException e) {
				failed++;
				LogUtils.diagnostic(context, "Qiniu restore failed: " + entry.getKey(), e);
			} finally {
				deleteTemporary(work);
			}
		}
		LogUtils.diagnostic(context, "Qiniu restore finished: success=" + success + ", skipped=" + skipped + ", failed=" + failed, null);
		return new BackupSummary(success, skipped, failed);
	}

	private void download(QiniuBackupEntry entry, File target) throws IOException {
		String unsignedUrl = base(config.getDownloadUrl()) + "/" + Uri.encode(entry.getKey(), "/")
				+ "?e=" + (System.currentTimeMillis() / 1000 + 3600);
		// Signing every download also works for public buckets, without requiring a visibility setting.
		URL url = new URL(unsignedUrl + "&token=" + Uri.encode(sign(unsignedUrl)));
		HttpURLConnection connection = open(url);
		try {
			requireSuccess(connection, "download");
			long size = 0;
			try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
				byte[] buffer = new byte[16 * 1024];
				int count;
				while ((count = in.read(buffer)) != -1) {
					size += count;
					if (size > MAX_BYTES) {
						throw new IOException("Backup archive exceeds the 2 GiB restore limit");
					}
					out.write(buffer, 0, count);
				}
			}
			if (entry.getSize() >= 0 && size != entry.getSize()) {
				throw new IOException("Downloaded backup size does not match cloud listing; refresh and retry");
			}
			LogUtils.diagnostic(context, "Qiniu download complete: bytes=" + size, null);
		} finally {
			connection.disconnect();
		}
	}

	private static void extract(File archive, File destination) throws IOException {
		if (!destination.mkdirs()) {
			throw new IOException("Cannot create extraction directory");
		}
		String root = destination.getCanonicalPath() + File.separator;
		long total = 0;
		int entries = 0;
		Set<String> paths = new HashSet<>();
		try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
			ZipEntry entry;
			byte[] buffer = new byte[16 * 1024];
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				if (++entries > 100000 || name.contains("\\") || name.startsWith("/") || name.contains(":")
						|| !(name.equals("meta.json") || name.startsWith("data/") || name.startsWith("config/") || name.startsWith("game/"))) {
					throw new IOException("Invalid backup archive entry");
				}
				File file = new File(destination, name);
				String path = file.getCanonicalPath();
				if (!path.startsWith(root) || !paths.add(path)) {
					throw new IOException("Unsafe or duplicate backup archive path");
				}
				if (entry.isDirectory()) {
					if (!file.isDirectory() && !file.mkdirs()) {
						throw new IOException("Cannot create backup directory");
					}
				} else {
					File parent = file.getParentFile();
					if (!parent.isDirectory() && !parent.mkdirs()) {
						throw new IOException("Cannot create backup directory");
					}
					try (FileOutputStream out = new FileOutputStream(file)) {
						int count;
						while ((count = zip.read(buffer)) != -1) {
							total += count;
							if (total > MAX_BYTES) {
								throw new IOException("Extracted backup exceeds the 2 GiB restore limit");
							}
							out.write(buffer, 0, count);
						}
					}
				}
				zip.closeEntry();
			}
		}
	}

	private void requireSuccess(HttpURLConnection connection, String operation) throws IOException {
		int status = connection.getResponseCode();
		LogUtils.diagnostic(context, "Qiniu " + operation + " response: HTTP " + status
				+ ", requestId=" + connection.getHeaderField("X-Reqid"), null);
		if (status != HttpURLConnection.HTTP_OK) {
			try (InputStream in = connection.getErrorStream()) {
				if (in != null) {
					LogUtils.diagnostic(context, "Qiniu " + operation + " error: " + readText(in, 8192), null);
				}
			}
			throw new IOException("Qiniu " + operation + " failed (HTTP " + status + ")");
		}
	}

	private String sign(String value) throws IOException {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(config.getSecretKey().getBytes("UTF-8"), "HmacSHA1"));
			return config.getAccessKey() + ":" + Base64.encodeToString(mac.doFinal(value.getBytes("UTF-8")),
					Base64.URL_SAFE | Base64.NO_WRAP);
		} catch (GeneralSecurityException e) {
			throw new IOException("Cannot sign Qiniu request", e);
		}
	}

	private static HttpURLConnection open(URL url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(30000);
		connection.setReadTimeout(120000);
		connection.setInstanceFollowRedirects(false);
		connection.setUseCaches(false);
		return connection;
	}

	private static String readText(InputStream in, int limit) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int count;
		while (out.size() < limit && (count = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()))) != -1) {
			out.write(buffer, 0, count);
		}
		return out.toString("UTF-8");
	}

	private static String base(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private static boolean isBackupKey(String key) {
		return key.startsWith(PREFIX) && key.endsWith(".zip") && key.length() > PREFIX.length() + 4
				&& key.indexOf('/', PREFIX.length()) == -1 && !key.contains("\\");
	}

	private void deleteTemporary(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteTemporary(child);
			}
		}
		if (file.exists() && !file.delete()) {
			LogUtils.diagnostic(context, "Cannot delete restore temporary file: " + file.getName(), null);
		}
	}
}
