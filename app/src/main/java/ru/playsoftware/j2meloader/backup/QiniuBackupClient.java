package ru.playsoftware.j2meloader.backup;

import android.content.Context;

import java.io.BufferedInputStream;
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
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ru.playsoftware.j2meloader.util.LogUtils;

/**
 * Uploads complete backup snapshots to an S3-compatible bucket.
 * Requests are signed with AWS Signature Version 4, matching the read side in {@link QiniuRestoreClient}.
 * Call only from a worker thread.
 */
public final class QiniuBackupClient {
	private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private final Context context;
	private final QiniuBackupConfig config;

	public QiniuBackupClient(Context context, QiniuBackupConfig config) {
		this.context = context.getApplicationContext();
		this.config = config;
	}

	public void upload(File folder) throws IOException {
		LogUtils.diagnostic(context, "S3 upload start: endpoint=" + config.getEndpoint()
				+ ", bucket=" + config.getBucket() + ", key=java-save/" + folder.getName() + ".zip", null);
		try {
			uploadSnapshot(folder);
			LogUtils.diagnostic(context, "S3 upload succeeded: " + folder.getName(), null);
		} catch (IOException | RuntimeException e) {
			LogUtils.diagnostic(context, "S3 upload failed: " + folder.getName(), e);
			throw e;
		}
	}

	private void uploadSnapshot(File folder) throws IOException {
		if (!config.isValid()) {
			throw new IOException("Invalid S3 configuration");
		}
		if (!new File(folder, "meta.json").isFile()) {
			throw new IOException("Backup metadata is missing");
		}
		File archive = File.createTempFile("s3-backup-", ".zip", context.getCacheDir());
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
		LogUtils.diagnostic(context, "S3 sync finished: success=" + success + ", failed=" + failed, null);
		return new BackupSummary(success, 0, failed);
	}

	private void uploadArchive(File archive, String key) throws IOException {
		String path = "/" + config.getBucket() + "/" + key;
		String payloadHash = sha256Hex(archive);
		URL url = new URL(base(config.getEndpoint()) + path);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(120000);
			connection.setInstanceFollowRedirects(false);
			connection.setUseCaches(false);
			connection.setRequestMethod("PUT");
			connection.setDoOutput(true);
			connection.setFixedLengthStreamingMode(archive.length());
			connection.setRequestProperty("Content-Type", "application/zip");
			signRequest(connection, "PUT", path, payloadHash, new Date());
			try (OutputStream out = new BufferedOutputStream(connection.getOutputStream());
				 InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
				copy(in, out);
			}
			int status = connection.getResponseCode();
			LogUtils.diagnostic(context, "S3 upload response: HTTP " + status, null);
			if (status != HttpURLConnection.HTTP_OK) {
				try (InputStream in = connection.getErrorStream()) {
					if (in != null) {
						LogUtils.diagnostic(context, "S3 upload error response (up to 8 KiB): "
								+ readText(in, 8192), null);
					}
				} catch (IOException e) {
					LogUtils.diagnostic(context, "Cannot read S3 error response", e);
				}
				throw new IOException("S3 upload failed (HTTP " + status + ")");
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

	private void signRequest(HttpURLConnection connection, String method, String path,
							 String payloadHash, Date date) throws IOException {
		try {
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			String amzDate = dateFormat.format(date);
			String dateStamp = amzDate.substring(0, 8);

			if (payloadHash == null || payloadHash.isEmpty()) {
				payloadHash = EMPTY_SHA256;
			}

			String canonicalUri = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
			String canonicalQueryString = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
			String canonicalHeaders = "host:" + connection.getURL().getHost()
					+ "\nx-amz-content-sha256:" + payloadHash
					+ "\nx-amz-date:" + amzDate + "\n";
			String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
			String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQueryString + "\n"
					+ canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

			String algorithm = "AWS4-HMAC-SHA256";
			String credentialScope = dateStamp + "/" + config.getRegion() + "/s3/aws4_request";
			String stringToSign = algorithm + "\n" + amzDate + "\n" + credentialScope + "\n"
					+ sha256Hex(canonicalRequest);

			byte[] signingKey = getSignatureKey(config.getSecretKey(), dateStamp, config.getRegion(), "s3");
			String signature = hmacSha256Hex(stringToSign, signingKey);

			String authorizationHeader = algorithm + " Credential=" + config.getAccessKey() + "/"
					+ credentialScope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

			connection.setRequestProperty("x-amz-date", amzDate);
			connection.setRequestProperty("x-amz-content-sha256", payloadHash);
			connection.setRequestProperty("Authorization", authorizationHeader);
		} catch (Exception e) {
			throw new IOException("Cannot sign S3 request", e);
		}
	}

	private byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName)
			throws Exception {
		byte[] kSecret = ("AWS4" + key).getBytes("UTF-8");
		byte[] kDate = hmacSha256(dateStamp, kSecret);
		byte[] kRegion = hmacSha256(regionName, kDate);
		byte[] kService = hmacSha256(serviceName, kRegion);
		return hmacSha256("aws4_request", kService);
	}

	private byte[] hmacSha256(String data, byte[] key) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(data.getBytes("UTF-8"));
	}

	private String hmacSha256Hex(String data, byte[] key) throws Exception {
		return bytesToHex(hmacSha256(data, key));
	}

	private String sha256Hex(String data) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return bytesToHex(digest.digest(data.getBytes("UTF-8")));
		} catch (Exception e) {
			throw new IOException("Cannot compute SHA-256", e);
		}
	}

	private String sha256Hex(File file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
				byte[] buffer = new byte[16 * 1024];
				int count;
				while ((count = in.read(buffer)) != -1) {
					digest.update(buffer, 0, count);
				}
			}
			return bytesToHex(digest.digest());
		} catch (Exception e) {
			throw new IOException("Cannot compute SHA-256", e);
		}
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder result = new StringBuilder();
		for (byte b : bytes) {
			result.append(String.format("%02x", b));
		}
		return result.toString();
	}

	private static String base(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
