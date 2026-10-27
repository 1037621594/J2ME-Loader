package ru.playsoftware.j2meloader.backup;

import android.content.Context;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ru.playsoftware.j2meloader.util.LogUtils;

/**
 * Uploads complete backup snapshots to public or private Qiniu buckets.
 * Both use the same signed upload token; bucket visibility only affects downloads.
 * Call only from a worker thread.
 */
public final class QiniuBackupClient {
	private final Context context;
	private final QiniuBackupConfig config;

	public QiniuBackupClient(Context context, QiniuBackupConfig config) {
		this.context = context.getApplicationContext();
		this.config = config;
	}

	public void upload(File folder) throws IOException {
		LogUtils.diagnostic(context, "Qiniu upload start: endpoint=" + config.getUploadUrl()
				+ ", bucket=" + config.getBucket() + ", key=java-save/" + folder.getName() + ".zip", null);
		try {
			uploadSnapshot(folder);
			LogUtils.diagnostic(context, "Qiniu upload succeeded: " + folder.getName(), null);
		} catch (IOException | RuntimeException e) {
			LogUtils.diagnostic(context, "Qiniu upload failed: " + folder.getName(), e);
			throw e;
		}
	}

	private void uploadSnapshot(File folder) throws IOException {
		if (!config.isValid()) {
			throw new IOException("Invalid Qiniu configuration");
		}
		if (!new File(folder, "meta.json").isFile()) {
			throw new IOException("Backup metadata is missing");
		}
		File archive = File.createTempFile("qiniu-backup-", ".zip", context.getCacheDir());
		try {
			try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
				zipTree(folder, "", zip);
			}
			uploadArchive(archive, "java-save/" + folder.getName() + ".zip");
		} finally {
			if (!archive.delete()) {
				archive.deleteOnExit();
			}
		}
	}

	public BackupSummary syncAll() throws IOException {
		File root = BackupManager.getRootDir();
		if (!root.exists()) {
			return new BackupSummary(0, 0, 0);
		}
		File[] folders = root.listFiles();
		if (folders == null) {
			throw new IOException("Cannot read backup directory");
		}
		int success = 0;
		int failed = 0;
		for (File folder : folders) {
			if (!folder.isDirectory() || !new File(folder, "meta.json").isFile()) {
				continue;
			}
			try {
				upload(folder);
				success++;
			} catch (IOException e) {
				failed++;
			}
		}
		LogUtils.diagnostic(context, "Qiniu sync finished: success=" + success + ", failed=" + failed, null);
		return new BackupSummary(success, 0, failed);
	}

	private String token(String key) throws IOException {
		try {
			JSONObject policy = new JSONObject();
			policy.put("scope", config.getBucket() + ":" + key);
			policy.put("deadline", System.currentTimeMillis() / 1000 + 3600);
			String encoded = base64(policy.toString().getBytes("UTF-8"));
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(config.getSecretKey().getBytes("UTF-8"), "HmacSHA1"));
			return config.getAccessKey() + ":" + base64(mac.doFinal(encoded.getBytes("UTF-8"))) + ":" + encoded;
		} catch (GeneralSecurityException | JSONException e) {
			throw new IOException("Cannot create Qiniu upload token", e);
		}
	}

	private static String base64(byte[] bytes) {
		return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
	}

	private void uploadArchive(File archive, String key) throws IOException {
		String boundary = "J2ME" + UUID.randomUUID().toString();
		String token = token(key);
		byte[] tokenField = field(boundary, "token", token);
		byte[] keyField = field(boundary, "key", key);
		byte[] fileHeader = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"backup.zip\"\r\n"
				+ "Content-Type: application/zip\r\n\r\n").getBytes("UTF-8");
		byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
		long length = archive.length() + tokenField.length + keyField.length + fileHeader.length + ending.length;
		LogUtils.diagnostic(context, "Qiniu archive bytes=" + archive.length() + ", request bytes=" + length, null);
		if (length > Integer.MAX_VALUE) {
			throw new IOException("Backup is too large for form upload");
		}
		HttpURLConnection connection = (HttpURLConnection) new URL(config.getUploadUrl()).openConnection();
		try {
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(120000);
			connection.setInstanceFollowRedirects(false);
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setFixedLengthStreamingMode((int) length);
			connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
			try (OutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
				out.write(tokenField);
				out.write(keyField);
				out.write(fileHeader);
				try (InputStream in = new FileInputStream(archive)) {
					copy(in, out);
				}
				out.write(ending);
			}
			int status = connection.getResponseCode();
			LogUtils.diagnostic(context, "Qiniu response: HTTP " + status
					+ ", requestId=" + connection.getHeaderField("X-Reqid")
					+ ", contentType=" + connection.getContentType()
					+ ", server=" + connection.getHeaderField("Server")
					+ ", location=" + connection.getHeaderField("Location"), null);
			if (status != HttpURLConnection.HTTP_OK) {
				try (InputStream in = connection.getErrorStream()) {
					if (in != null) {
						ByteArrayOutputStream response = new ByteArrayOutputStream();
						byte[] buffer = new byte[1024];
						int count;
						while (response.size() < 8192 && (count = in.read(buffer, 0,
								Math.min(buffer.length, 8192 - response.size()))) != -1) {
							response.write(buffer, 0, count);
						}
						LogUtils.diagnostic(context, "Qiniu error response (up to 8 KiB): " + response.toString("UTF-8"), null);
					}
				} catch (IOException e) {
					LogUtils.diagnostic(context, "Cannot read Qiniu error response", e);
				}
				throw new IOException("Qiniu upload failed (HTTP " + status + ")");
			}
			try (InputStream in = connection.getInputStream()) {
				byte[] buffer = new byte[1024];
				while (in.read(buffer) != -1) {
					// Consume the response so the connection can be reused.
				}
			}
		} finally {
			connection.disconnect();
		}
	}

	private static byte[] field(String boundary, String name, String value) throws IOException {
		return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
				+ "\"\r\n\r\n" + value + "\r\n").getBytes("UTF-8");
	}

	private static void zipTree(File file, String path, ZipOutputStream zip) throws IOException {
		if (file.isDirectory()) {
			if (!path.isEmpty()) {
				zip.putNextEntry(new ZipEntry(path + "/"));
				zip.closeEntry();
			}
			File[] children = file.listFiles();
			if (children == null) {
				throw new IOException("Cannot read backup directory");
			}
			for (File child : children) {
				zipTree(child, path.isEmpty() ? child.getName() : path + "/" + child.getName(), zip);
			}
		} else {
			zip.putNextEntry(new ZipEntry(path));
			try (InputStream in = new FileInputStream(file)) {
				copy(in, zip);
			}
			zip.closeEntry();
		}
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[16 * 1024];
		int count;
		while ((count = in.read(buffer)) != -1) {
			out.write(buffer, 0, count);
		}
	}
}
