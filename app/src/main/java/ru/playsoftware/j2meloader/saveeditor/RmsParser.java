package ru.playsoftware.j2meloader.saveeditor;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a record into values the way most J2ME games write it with DataOutputStream:
 * writeUTF strings (2-byte length + UTF-8) and big-endian ints. The record carries no type
 * information, so this is a guess; every int is also shown as two shorts and four bytes,
 * which covers games that write shorts, bytes or booleans in between.
 */
public final class RmsParser {
	/** Shorter strings are too likely to be a misread int, e.g. 0x0001 followed by 'A'. */
	private static final int MIN_TEXT = 2;

	private RmsParser() {
	}

	public static List<RmsField> parse(byte[] data) {
		List<RmsField> fields = new ArrayList<>();
		int pos = 0;
		while (pos < data.length) {
			int text = utfLength(data, pos);
			if (text > 0) {
				fields.add(new RmsField(pos + 2, ValueCodec.TEXT, text));
				pos += 2 + text;
			} else if (pos + 4 <= data.length) {
				fields.add(new RmsField(pos, ValueCodec.INT32_BE, 4));
				pos += 4;
			} else if (pos + 2 <= data.length) {
				fields.add(new RmsField(pos, ValueCodec.INT16_BE, 2));
				pos += 2;
			} else {
				fields.add(new RmsField(pos, ValueCodec.INT8, 1));
				pos++;
			}
		}
		return fields;
	}

	/** Byte length of a writeUTF string of printable characters at {@code pos}, 0 when there is none. */
	private static int utfLength(byte[] data, int pos) {
		if (pos + 2 > data.length) {
			return 0;
		}
		int n = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
		int end = pos + 2 + n;
		if (n < MIN_TEXT || end > data.length) {
			return 0;
		}
		int i = pos + 2;
		while (i < end) {
			int b = data[i] & 0xFF;
			int extra;
			if (b < 0x80) {
				if (b < 0x20 || b == 0x7F) {
					return 0;
				}
				i++;
				continue;
			} else if ((b & 0xE0) == 0xC0) {
				extra = 1;
			} else if ((b & 0xF0) == 0xE0) {
				extra = 2;
			} else {
				return 0;
			}
			if (i + extra >= end) {
				return 0;
			}
			for (int k = 1; k <= extra; k++) {
				if ((data[i + k] & 0xC0) != 0x80) {
					return 0;
				}
			}
			i += extra + 1;
		}
		return n;
	}
}
