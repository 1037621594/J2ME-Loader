package ru.playsoftware.j2meloader.saveeditor;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** A value found by the search over all records of a game. */
@Getter
@AllArgsConstructor
public class SaveHit {
	private final RmsRecord record;
	private final int offset;
	private final ValueCodec codec;
	/** Byte length, needed to show and edit text hits. */
	private final int length;
}
