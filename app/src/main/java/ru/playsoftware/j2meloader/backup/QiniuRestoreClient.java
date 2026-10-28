package ru.playsoftware.j2meloader.backup;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.UnsupportedEncodingException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.util.LogUtils;

/** Lists and restores the same java-save/*.zip snapshots using S3 protocol. */
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
			throw new IOException("Configure bucket, keys and endpoint before restoring");
		}
		LogUtils.diagnostic(context, "S3 list start: endpoint=" + config.getEndpoint()
				+ ", bucket=" + config.getBucket() + ", prefix=" + PREFIX, null);
		try {
			List<QiniuBackupEntry> result = new ArrayList<>();
			Set<String> keys = new HashSet<>();
			String marker = null;
			do {
				String path = "/" + config.getBucket() + "/?list-type=2&prefix=" + Uri.encode(PREFIX) + "&max-keys=1000";
				if (marker != null && !marker.isEmpty()) {
					path += "&continuation-token=" + Uri.encode(marker);
				}
				URL url = new URL(base(config.getEndpoint()) + path);
				HttpURLConnection connection = open(url);
				try {
					signRequest(connection, "GET", path, "", new Date());
					requireSuccess(connection, "list");
					String xml;
					try (InputStream in = connection.getInputStream()) {
						xml = readText(in, 4 * 1024 * 1024);
					}
					// 简单的XML解析，提取Contents节点
					int pos = 0;
					while ((pos = xml.indexOf("<Contents>", pos)) >= 0) {
						int end = xml.indexOf("</Contents>", pos);
						if (end < 0) break;
						String content = xml.substring(pos, end);
						String key = extractXmlTag(content, "Key");
						String sizeStr = extractXmlTag(content, "Size");
						String lastModified = extractXmlTag(content, "LastModified");
						if (key != null && isBackupKey(key) && keys.add(key)) {
							long size = -1;
							try {
								if (sizeStr != null) size = Long.parseLong(sizeStr);
							} catch (NumberFormatException ignored) {}
							long modifiedTime = 0;
							try {
								if (lastModified != null) {
									SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
									sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
									modifiedTime = sdf.parse(lastModified.substring(0, Math.min(19, lastModified.length()))).getTime();
								}
							} catch (Exception ignored) {}
							result.add(new QiniuBackupEntry(key, size, modifiedTime));
						}
						pos = end;
					}
					marker = extractXmlTag(xml, "NextContinuationToken");
				} finally {
					connection.disconnect();
				}
			} while (marker != null && !marker.isEmpty());
			java.util.Collections.sort(result, (left, right) -> left.getModifiedTime() == right.getModifiedTime()
					? 0 : (left.getModifiedTime() < right.getModifiedTime() ? 1 : -1));
			LogUtils.diagnostic(context, "S3 list finished: backups=" + result.size(), null);
			return result;
		} catch (IOException | RuntimeException e) {
			LogUtils.diagnostic(context, "S3 list failed", e);
			throw new IOException("Cannot list S3 backups; see logs", e);
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
			File work = new File(context.getCacheDir(), "s3-restore-" + UUID.randomUUID());
			try {
				if (!isBackupKey(entry.getKey())) {
					throw new IOException("Invalid backup object key");
				}
				if (!work.mkdirs()) {
					throw new IOException("Cannot create restore staging directory");
				}
				LogUtils.diagnostic(context, "S3 restore start: key=" + entry.getKey()
						+ ", endpoint=" + config.getEndpoint(), null);
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
					LogUtils.diagnostic(context, "S3 restore succeeded: " + entry.getKey(), null);
				} else {
					skipped++;
					LogUtils.diagnostic(context, "S3 restore skipped: game not installed and snapshot has no game: "
							+ entry.getKey(), null);
				}
			} catch (IOException | RuntimeException e) {
				failed++;
				LogUtils.diagnostic(context, "S3 restore failed: " + entry.getKey(), e);
			} finally {
				deleteTemporary(work);
			}
		}
		LogUtils.diagnostic(context, "S3 restore finished: success=" + success + ", skipped=" + skipped + ", failed=" + failed, null);
		return new BackupSummary(success, skipped, failed);
	}

	private void download(QiniuBackupEntry entry, File target) throws IOException {
		String path = "/" + config.getBucket() + "/" + entry.getKey();
		URL url = new URL(base(config.getEndpoint()) + path);
		HttpURLConnection connection = open(url);
		try {
			signRequest(connection, "GET", path, "", new Date());
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
			LogUtils.diagnostic(context, "S3 download complete: bytes=" + size, null);
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
		LogUtils.diagnostic(context, "S3 " + operation + " response: HTTP " + status, null);
		if (status != HttpURLConnection.HTTP_OK) {
			try (InputStream in = connection.getErrorStream()) {
				if (in != null) {
					LogUtils.diagnostic(context, "S3 " + operation + " error: " + readText(in, 8192), null);
				}
			}
			throw new IOException("S3 " + operation + " failed (HTTP " + status + ")");
		}
	}

	private void signRequest(HttpURLConnection connection, String method, String path, String payloadHash, Date date) throws IOException {
		try {
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			String amzDate = dateFormat.format(date);
			String dateStamp = amzDate.substring(0, 8);

			if (payloadHash.isEmpty()) {
				payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // empty SHA256
			}

			String canonicalUri = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
			String canonicalQueryString = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
			String canonicalHeaders = "host:" + connection.getURL().getHost() + "\nx-amz-content-sha256:" + payloadHash + "\nx-amz-date:" + amzDate + "\n";
			String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
			String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQueryString + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

			String algorithm = "AWS4-HMAC-SHA256";
			String credentialScope = dateStamp + "/" + config.getRegion() + "/s3/aws4_request";
			String stringToSign = algorithm + "\n" + amzDate + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);

			byte[] signingKey = getSignatureKey(config.getSecretKey(), dateStamp, config.getRegion(), "s3");
			String signature = hmacSha256Hex(stringToSign, signingKey);

			String authorizationHeader = algorithm + " Credential=" + config.getAccessKey() + "/" + credentialScope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

			connection.setRequestProperty("x-amz-date", amzDate);
			connection.setRequestProperty("x-amz-content-sha256", payloadHash);
			connection.setRequestProperty("Authorization", authorizationHeader);
		} catch (Exception e) {
			throw new IOException("Cannot sign S3 request", e);
		}
	}

	private byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) throws Exception {
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

	private String sha256Hex(String data) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		return bytesToHex(digest.digest(data.getBytes("UTF-8")));
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder result = new StringBuilder();
		for (byte b : bytes) {
			result.append(String.format("%02x", b));
		}
		return result.toString();
	}

	private String extractXmlTag(String xml, String tag) {
		String startTag = "<" + tag + ">";
		String endTag = "</" + tag + ">";
		int start = xml.indexOf(startTag);
		if (start < 0) return null;
		start += startTag.length();
		int end = xml.indexOf(endTag, start);
		if (end < 0) return null;
		return xml.substring(start, end);
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
