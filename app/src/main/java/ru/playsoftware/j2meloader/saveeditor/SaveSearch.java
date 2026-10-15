package ru.playsoftware.j2meloader.saveeditor;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Searches a value in every record of a game, in every encoding at once, so the user does not
 * have to know which record or which type holds it.
 */
public final class SaveSearch {
	private static final String TAG = SaveSearch.class.getSimpleName();
	public static final int MAX_HITS = 2000;

	private SaveSearch() {
	}

	/** Every location of {@code text} as a number of any size/byte order, or as UTF-8 text. */
	public static List<SaveHit> find(List<RmsRecord> records, String text) {
		List<SaveHit> hits = new ArrayList<>();
		for (ValueCodec codec : ValueCodec.values()) {
			byte[] pattern;
			try {
				pattern = codec.encode(text);
			} catch (NumberFormatException e) {
				continue;
			}
			for (RmsRecord record : records) {
				for (int offset : ValueCodec.find(record.getData(), pattern)) {
					hits.add(new SaveHit(record, offset, codec, pattern.length));
				}
			}
		}
		return hits;
	}

	/**
	 * Keeps the hits that now hold {@code text}. Records are re-read from disk first, since
	 * the game has usually saved again in between.
	 */
	public static List<SaveHit> filter(List<SaveHit> previous, String text) {
		Map<String, RmsRecord> fresh = new HashMap<>();
		List<SaveHit> result = new ArrayList<>();
		for (SaveHit hit : previous) {
			byte[] pattern;
			try {
				pattern = hit.getCodec().encode(text);
			} catch (NumberFormatException e) {
				continue;
			}
			if (hit.getCodec() == ValueCodec.TEXT && pattern.length != hit.getLength()) {
				continue;
			}
			String path = hit.getRecord().getFile().getPath();
			RmsRecord record = fresh.get(path);
			if (record == null && !fresh.containsKey(path)) {
				try {
					record = RmsStorage.read(hit.getRecord().getFile());
				} catch (IOException e) {
					Log.w(TAG, "Can't re-read " + path, e);
				}
				fresh.put(path, record);
			}
			if (record != null && matches(record.getData(), hit.getOffset(), pattern)) {
				result.add(new SaveHit(record, hit.getOffset(), hit.getCodec(), hit.getLength()));
			}
		}
		return result;
	}

	/** Re-reads the records of the hits, keeping every hit that still fits into its record. */
	public static List<SaveHit> refresh(List<SaveHit> previous) {
		Map<String, RmsRecord> fresh = new HashMap<>();
		List<SaveHit> result = new ArrayList<>();
		for (SaveHit hit : previous) {
			String path = hit.getRecord().getFile().getPath();
			RmsRecord record = fresh.get(path);
			if (record == null && !fresh.containsKey(path)) {
				try {
					record = RmsStorage.read(hit.getRecord().getFile());
				} catch (IOException e) {
					Log.w(TAG, "Can't re-read " + path, e);
				}
				fresh.put(path, record);
			}
			if (record != null && hit.getOffset() + hit.getLength() <= record.getData().length) {
				result.add(new SaveHit(record, hit.getOffset(), hit.getCodec(), hit.getLength()));
			}
		}
		return result;
	}

	private static boolean matches(byte[] data, int offset, byte[] pattern) {
		if (offset + pattern.length > data.length) {
			return false;
		}
		for (int i = 0; i < pattern.length; i++) {
			if (data[offset + i] != pattern[i]) {
				return false;
			}
		}
		return true;
	}
}
