package ru.playsoftware.j2meloader.memsearch;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ScanResult {
	private final List<MemoryHit> hits;
	/** Matches found before the collection cap, can exceed {@code hits.size()}. */
	private final long totalHits;
	/** True when some matches were dropped by the collection cap. */
	private final boolean truncated;
	/** Number of visited objects, 0 for a rescan of existing hits. */
	private final long visited;
	/** True when traversal stopped early because the visit cap was reached. */
	private final boolean visitLimited;
}
