package ru.playsoftware.j2meloader.saveeditor;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Value encodings found in RMS records. J2ME games mostly use DataOutputStream, which is
 * big-endian, so those come first. The order must match {@code R.array.save_editor_types}.
 */
@Getter
@RequiredArgsConstructor
public enum ValueCodec {
	INT32_BE(4, true),
	INT16_BE(2, true),
	INT8(1, true),
	INT32_LE(4, false),
	INT16_LE(2, false),
	INT64_BE(8, true),
	INT64_LE(8, false),
	TEXT(0, true);

	private static final Charset UTF8 = Charset.forName("UTF-8");

	/** Encoded size in bytes, 0 for text. */
	private final int size;
	private final boolean bigEndian;

	/**
	 * Encodes user input. Integers accept the signed and the unsigned range of their size,
	 * e.g. -128..255 for a byte, since games use both.
	 */
	public byte[] encode(String text) {
		if (this == TEXT) {
			byte[] bytes = text.getBytes(UTF8);
			if (bytes.length == 0) {
				throw new NumberFormatException("empty");
			}
			return bytes;
		}
		long v = Long.parseLong(text.trim());
		if (size < 8 && (v < -(1L << (size * 8 - 1)) || v > (1L << (size * 8)) - 1)) {
			throw new NumberFormatException(text);
		}
		byte[] out = new byte[size];
		for (int i = 0; i < size; i++) {
			int shift = 8 * (bigEndian ? size - 1 - i : i);
			out[i] = (byte) (v >> shift);
		}
		return out;
	}

	/** The value at {@code offset}, or null when it does not fit into the data. */
	public String decode(byte[] data, int offset, int textLength) {
		if (this == TEXT) {
			int len = Math.min(textLength, data.length - offset);
			return len > 0 ? new String(data, offset, len, UTF8) : null;
		}
		if (offset < 0 || offset + size > data.length) {
			return null;
		}
		long v = 0;
		for (int i = 0; i < size; i++) {
			int b = data[offset + (bigEndian ? i : size - 1 - i)] & 0xFF;
			v = (v << 8) | b;
		}
		// Sign-extend so the shown value matches what the game reads with readInt()/readShort().
		int bits = 64 - size * 8;
		return String.valueOf((v << bits) >> bits);
	}

	/** Offsets of every occurrence of {@code pattern} in {@code data}. */
	public static List<Integer> find(byte[] data, byte[] pattern) {
		List<Integer> result = new ArrayList<>();
		outer:
		for (int i = 0; i + pattern.length <= data.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) {
					continue outer;
				}
			}
			result.add(i);
		}
		return result;
	}
}
