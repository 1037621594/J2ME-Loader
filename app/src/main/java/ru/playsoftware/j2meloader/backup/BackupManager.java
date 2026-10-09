package ru.playsoftware.j2meloader.backup;

import android.os.Environment;
import android.util.Log;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;

import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

public final class BackupManager {
	private static final String TAG = BackupManager.class.getSimpleName();
	private static final String META_FILE = "meta.json";
	private static final String GAME_DIR = "game";
	private static final String DATA_DIR = "data";
	private static final String CONFIG_DIR = "config";
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final Gson GSON = new Gson();

	private BackupManager() {
	}

	public static File getRootDir() {
		return new File(Environment.getExternalStorageDirectory(), "JavaGame/Save");
	}

	public static String folderName(String name, String vendor) {
		String raw = (name == null ? "" : name) + "_" + (vendor == null ? "" : vendor);
		String clean = raw.replaceAll(FileUtils.ILLEGAL_FILENAME_CHARS, "").replaceAll("\\s+", " ").trim();
		return clean.isEmpty() || clean.equals("_") ? "app" : clean;
	}

	public static boolean backupExists(File appDir) {
		try {
			return new File(backupFolder(appDir), META_FILE).isFile();
		} catch (Exception e) {
			Log.w(TAG, "backupExists failed for " + appDir, e);
			return false;
		}
	}

	/** Backs up RMS data and settings of the game, plus the game itself when requested. */
	public static BackupMeta backupApp(File appDir, boolean includeGame) throws IOException {
		Descriptor d = new Descriptor(new File(appDir, Config.MIDLET_MANIFEST_FILE), false);
		File folder = backupFolder(d);
		if (!folder.isDirectory() && !folder.mkdirs()) {
			throw new IOException("Can't create backup directory: " + folder);
		}
		Log.i(TAG, "backup start: " + appDir + " -> " + folder + ", includeGame=" + includeGame);
		if (includeGame) {
			replaceDir(appDir, new File(folder, GAME_DIR));
		}
		replaceDir(new File(Config.getDataDir(), appDir.getName()), new File(folder, DATA_DIR));
		replaceDir(new File(Config.getConfigsDir(), appDir.getName()), new File(folder, CONFIG_DIR));
		BackupMeta meta = new BackupMeta(d.getName(), d.getVendor(), d.getVersion(),
				System.currentTimeMillis(), new File(folder, GAME_DIR).isDirectory());
		writeMeta(folder, meta);
		Log.i(TAG, "backup done: " + folder);
		return meta;
	}

	/** Restores RMS data and settings of an installed game from its backup folder. */
	public static void restoreApp(File appDir) throws IOException {
		File folder = backupFolder(appDir);
		if (!new File(folder, META_FILE).isFile()) {
			throw new FileNotFoundException("No backup in " + folder);
		}
		Log.i(TAG, "restore start: " + folder + " -> " + appDir);
		restoreInto(folder, appDir);
		Log.i(TAG, "restore done: " + appDir);
	}

	public static BackupSummary exportAll() {
		int success = 0;
		int failed = 0;
		File[] dirs = new File(Config.getAppDir()).listFiles();
		if (dirs != null) {
			for (File dir : dirs) {
				if (!isInstalledApp(dir)) {
					continue;
				}
				try {
					backupApp(dir, true);
					success++;
				} catch (Exception e) {
					Log.e(TAG, "export failed: " + dir, e);
					failed++;
				}
			}
		}
		Log.i(TAG, "export finished: success=" + success + ", failed=" + failed);
		return new BackupSummary(success, 0, failed);
	}

	public static BackupSummary importAll(AppRepository repository) {
		int success = 0;
		int skipped = 0;
		int failed = 0;
		File[] folders = getRootDir().listFiles();
		if (folders != null) {
			for (File folder : folders) {
				BackupMeta meta = readMeta(folder);
				if (meta == null) {
					continue;
				}
				try {
					File installed = findInstalled(meta.getName(), meta.getVendor());
					if (installed == null) {
						File game = new File(folder, GAME_DIR);
						if (!new File(game, Config.MIDLET_DEX_FILE).isFile()) {
							Log.w(TAG, "import skipped, game is not installed and backup has no game: " + folder);
							skipped++;
							continue;
						}
						installed = installGame(game, meta, repository);
					}
					restoreInto(folder, installed);
					success++;
				} catch (Exception e) {
					Log.e(TAG, "import failed: " + folder, e);
					failed++;
				}
			}
		}
		Log.i(TAG, "import finished: success=" + success + ", skipped=" + skipped + ", failed=" + failed);
		return new BackupSummary(success, skipped, failed);
	}

	private static File backupFolder(File appDir) throws IOException {
		return backupFolder(new Descriptor(new File(appDir, Config.MIDLET_MANIFEST_FILE), false));
	}

	private static File backupFolder(Descriptor d) {
		return new File(getRootDir(), folderName(d.getName(), d.getVendor()));
	}

	private static void restoreInto(File folder, File appDir) throws IOException {
		replaceDir(new File(folder, DATA_DIR), new File(Config.getDataDir(), appDir.getName()));
		replaceDir(new File(folder, CONFIG_DIR), new File(Config.getConfigsDir(), appDir.getName()));
	}

	private static boolean isInstalledApp(File dir) {
		return dir.isDirectory() && !dir.getName().startsWith(".")
				&& new File(dir, Config.MIDLET_DEX_FILE).isFile();
	}

	private static File findInstalled(String name, String vendor) {
		if (name == null) {
			return null;
		}
		File[] dirs = new File(Config.getAppDir()).listFiles();
		if (dirs == null) {
			return null;
		}
		for (File dir : dirs) {
			if (!isInstalledApp(dir)) {
				continue;
			}
			try {
				Descriptor d = new Descriptor(new File(dir, Config.MIDLET_MANIFEST_FILE), false);
				if (name.equalsIgnoreCase(d.getName())
						&& (vendor == null || vendor.equalsIgnoreCase(d.getVendor()))) {
					return dir;
				}
			} catch (Exception e) {
				Log.w(TAG, "findInstalled: skip " + dir, e);
			}
		}
		return null;
	}

	private static File installGame(File game, BackupMeta meta, AppRepository repository) throws IOException {
		File appsDir = new File(Config.getAppDir());
		if (!appsDir.isDirectory() && !appsDir.mkdirs()) {
			throw new IOException("Can't create directory: " + appsDir);
		}
		File tmp = new File(appsDir, ".tmp");
		deleteTree(tmp);
		copyTree(game, tmp);
		String base = meta.getName() == null ? "" : meta.getName().replaceAll(FileUtils.ILLEGAL_FILENAME_CHARS, "").trim();
		if (base.isEmpty()) {
			base = "app";
		}
		File target = new File(appsDir, base);
		for (int i = 1; target.exists(); i++) {
			target = new File(appsDir, base + "_" + i);
		}
		if (!tmp.renameTo(target)) {
			deleteTree(tmp);
			throw new IOException("Can't move " + tmp + " to " + target);
		}
		AppItem item = AppUtils.getApp(target);
		repository.insert(item);
		Log.i(TAG, "game installed from backup: " + target);
		return target;
	}

	private static BackupMeta readMeta(File folder) {
		File file = new File(folder, META_FILE);
		if (!file.isFile()) {
			return null;
		}
		try (Reader reader = new InputStreamReader(new FileInputStream(file), UTF8)) {
			BackupMeta meta = GSON.fromJson(reader, BackupMeta.class);
			return meta == null || meta.getName() == null ? null : meta;
		} catch (Exception e) {
			Log.w(TAG, "Broken meta: " + file, e);
			return null;
		}
	}

	private static void writeMeta(File folder, BackupMeta meta) throws IOException {
		File file = new File(folder, META_FILE);
		try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF8)) {
			GSON.toJson(meta, writer);
		}
	}

	/** Copies src next to dst first, then swaps it in, so a failed copy never destroys the old dst. */
	private static void replaceDir(File src, File dst) throws IOException {
		File parent = dst.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Can't create directory: " + parent);
		}
		if (!src.isDirectory()) {
			deleteTree(dst);
			return;
		}
		File tmp = new File(parent, dst.getName() + ".tmp");
		deleteTree(tmp);
		try {
			copyTree(src, tmp);
			deleteTree(dst);
			if (!tmp.renameTo(dst)) {
				throw new IOException("Can't move " + tmp + " to " + dst);
			}
		} catch (IOException | RuntimeException e) {
			deleteTree(tmp);
			throw e;
		}
	}

	private static void copyTree(File src, File dst) throws IOException {
		if (src.isDirectory()) {
			if (!dst.isDirectory() && !dst.mkdirs()) {
				throw new IOException("Can't create directory: " + dst);
			}
			File[] children = src.listFiles();
			if (children != null) {
				for (File child : children) {
					copyTree(child, new File(dst, child.getName()));
				}
			}
			return;
		}
		byte[] buf = new byte[16 * 1024];
		try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
		}
	}

	private static void deleteTree(File file) throws IOException {
		if (!file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteTree(child);
				}
			}
		}
		if (!file.delete() && file.exists()) {
			throw new IOException("Can't delete: " + file);
		}
	}
}
