package ru.playsoftware.j2meloader.saveeditor;

import java.io.File;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * One RMS record as stored by AndroidRecordStoreManager in {@code <store>.<id>.rsr}:
 * int recordId, int tag, int length, then {@code length} data bytes (DataOutputStream, big-endian).
 */
@Getter
@AllArgsConstructor
public class RmsRecord {
	private final String storeName;
	private final int id;
	private final int tag;
	private final File file;
	@Setter
	private byte[] data;
}
