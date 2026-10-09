package ru.playsoftware.j2meloader.memsearch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The declaration order MUST match the R.array.memory_search_modes resource.
 */
@Getter
@RequiredArgsConstructor
public enum SearchMode {
	EQUAL(false, false),
	GREATER(false, false),
	LESS(false, false),
	RANGE(false, true),
	CHANGED(true, false),
	UNCHANGED(true, false),
	INCREASED(true, false),
	DECREASED(true, false);

	private final boolean previousBased;
	private final boolean ranged;
}
