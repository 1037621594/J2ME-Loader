package ru.playsoftware.j2meloader.saveeditor;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One value of a parsed record. */
@Getter
@AllArgsConstructor
public class RmsField {
	/** Offset of the value; for text the first byte after the 2-byte length prefix. */
	private final int offset;
	private final ValueCodec codec;
	/** Byte length of the value. */
	private final int length;
}
