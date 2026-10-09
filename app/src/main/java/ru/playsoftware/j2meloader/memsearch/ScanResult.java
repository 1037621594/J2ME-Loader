package ru.playsoftware.j2meloader.memsearch;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ScanResult {
	private final List<MemoryHit> hits;
	private final boolean truncated;
	/** Number of visited objects, 0 for a rescan of existing hits. */
	private final long visited;
}
