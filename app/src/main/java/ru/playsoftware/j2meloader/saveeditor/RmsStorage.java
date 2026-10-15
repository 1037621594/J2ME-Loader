package ru.playsoftware.j2meloader.saveeditor;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads and writes the .rsr record files of a game's RMS data directory. */
public final class RmsStorage {
	private static final String TAG = RmsStorage.class.getSimpleName();
	private static final String RECORD_SUFFIX = ".rsr";
	private static final String BACKUP_SUFFIX = ".bak";

	private RmsStorage() {
	}

	/** All records of the directory, ordered by store name, then record id. */
	public static List<RmsRecord> list(File dataDir) {
		List<RmsRecord> result = new ArrayList<>();
		File[] files = dataDir.listFiles();
		if (files == null) {
			return result;
		}
		for (File file : files) {
			if (!file.isFile() || !file.getName().endsWith(RECORD_SUFFIX)) {
				continue;
			}
			try {
				result.add(read(file));
			} catch (IOException e) {
				Log.w(TAG, "Broken record: " + file, e);
			}
		}
		Collections.sort(result, (a, b) -> {
			int c = a.getStoreName().compareTo(b.getStoreName());
			return c != 0 ? c : Integer.compare(a.getId(), b.getId());
		});
		return result;
	}

	public static RmsRecord read(File file) throws IOException {
		try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
			int id = dis.readInt();
			int tag = dis.readInt();
			int length = dis.readInt();
			if (length < 0 || length > file.length()) {
				throw new IOException("Bad record length " + length);
			}
			byte[] data = new byte[length];
			dis.readFully(data);
			return new RmsRecord(storeNameOf(file.getName()), id, tag, file, data);
		}
	}

	/**
	 * Writes the record back in the loader's format. The untouched original is kept once as
	 * {@code <file>.bak}, and the new content goes through a temp file so a failed write never
	 * leaves a truncated record behind.
	 */
	public static void write(RmsRecord record) throws IOException {
		File file = record.getFile();
		File backup = new File(file.getPath() + BACKUP_SUFFIX);
		if (!backup.exists()) {
			copy(file, backup);
		}
		File tmp = new File(file.getPath() + ".tmp");
		try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmp))) {
			byte[] data = record.getData();
			dos.writeInt(record.getId());
			dos.writeInt(record.getTag());
			dos.writeInt(data.length);
			dos.write(data);
		} catch (IOException e) {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			throw e;
		}
		if (!file.delete() || !tmp.renameTo(file)) {
			throw new IOException("Can't replace " + file);
		}
	}

	/** True when a game runs: it caches records in memory and would overwrite the edit. */
	public static boolean isMidletRunning(Context context) {
		ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
		if (processes == null) {
			return false;
		}
		String midletProcess = context.getPackageName() + ":midlet";
		for (ActivityManager.RunningAppProcessInfo info : processes) {
			if (midletProcess.equals(info.processName)) {
				return true;
			}
		}
		return false;
	}

	/** {@code <store>.<id>.rsr} -> {@code <store>}; the store name itself may contain dots. */
	private static String storeNameOf(String fileName) {
		String base = fileName.substring(0, fileName.length() - RECORD_SUFFIX.length());
		int dot = base.lastIndexOf('.');
		return dot > 0 ? base.substring(0, dot) : base;
	}

	private static void copy(File src, File dst) throws IOException {
		byte[] buf = new byte[16 * 1024];
		try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
		}
	}
}
