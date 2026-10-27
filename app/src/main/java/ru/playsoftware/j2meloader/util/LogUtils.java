/*
 * Copyright 2019 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.util;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.backup.QiniuBackupConfig;
import ru.playsoftware.j2meloader.config.Config;

public class LogUtils {
	private static final int LOG_LIMIT = 128 * 1024;

	/** Keeps recent diagnostics in app-private storage, independent of logcat retention. */
	public static synchronized void diagnostic(Context context, String message, Throwable error) {
		String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
				+ " " + message + (error == null ? "" : "\n" + Log.getStackTraceString(error)) + "\n";
		entry = redact(context, entry);
		try {
			File file = new File(context.getFilesDir(), "backup-diagnostic.log");
			if (file.length() >= LOG_LIMIT) {
				File previous = new File(context.getFilesDir(), "backup-diagnostic.previous.log");
				if (previous.exists() && !previous.delete()) {
					return;
				}
				if (!file.renameTo(previous)) {
					return;
				}
			}
			try (FileOutputStream out = new FileOutputStream(file, true)) {
				out.write(entry.getBytes("UTF-8"));
			}
		} catch (IOException ignored) {
			// Diagnostic recording must never interrupt a backup.
		}
	}

	/** Reads a bounded snapshot on a worker thread; requires no storage permission. */
	public static String readLog(Context context) {
		StringBuilder result = new StringBuilder("J2ME Loader ").append(BuildConfig.VERSION_NAME)
				.append("\nAndroid ").append(android.os.Build.VERSION.RELEASE)
				.append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n=== Backup diagnostics ===\n");
		synchronized (LogUtils.class) {
			for (String name : new String[]{"backup-diagnostic.previous.log", "backup-diagnostic.log"}) {
				File file = new File(context.getFilesDir(), name);
				if (file.isFile()) {
					try (InputStream in = new FileInputStream(file)) {
						result.append(readLimited(in));
					} catch (IOException e) {
						result.append("Cannot read diagnostics: ").append(e).append('\n');
					}
				}
			}
		}
		result.append("\n=== Recent application logs ===\n");
		Process process = null;
		try {
			process = new ProcessBuilder("logcat", "-d", "-t", "500", "-v", "threadtime", "-s",
					"BackupManager:V", "AppsListFragment:V", "AndroidRuntime:E", "System.err:W")
					.redirectErrorStream(true).start();
			try (InputStream in = process.getInputStream()) {
				result.append(readLimited(in));
			}
		} catch (IOException e) {
			result.append("Cannot read logcat: ").append(e).append('\n');
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
		return redact(context, result.toString());
	}

	public static String redact(Context context, String text) {
		text = text.replaceAll("[A-Za-z0-9_\\-]{16,}:[A-Za-z0-9_\\-]{20,}=*:[A-Za-z0-9_\\-]+=*", "[REDACTED_TOKEN]");
		QiniuBackupConfig config = QiniuBackupConfig.load(context);
		for (String secret : new String[]{config.getAccessKey(), config.getSecretKey()}) {
			if (secret != null && !secret.isEmpty()) {
				text = text.replace(secret, "[REDACTED]");
			}
		}
		return text.replaceAll("(?i)((?:token|access_?key|secret_?key|authorization)[\\\"'\\s]*[:=][\\\"'\\s]*)[^\\s\\\"'&,}]+", "$1[REDACTED]");
	}

	private static String readLimited(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int count;
		while (out.size() < LOG_LIMIT && (count = in.read(buffer, 0, Math.min(buffer.length, LOG_LIMIT - out.size()))) != -1) {
			out.write(buffer, 0, count);
		}
		return out.toString("UTF-8");
	}

	public static void writeLog() throws IOException {
		File logFile = new File(Config.getEmulatorDir(), "log.txt");
		if (logFile.exists()) {
			logFile.delete();
		}
		Runtime.getRuntime().exec("logcat -t 500 -f " + logFile);
	}

}
