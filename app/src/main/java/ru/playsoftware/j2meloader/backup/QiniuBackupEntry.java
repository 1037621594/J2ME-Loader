package ru.playsoftware.j2meloader.backup;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public final class QiniuBackupEntry {
	private final String key;
	private final long size;
	private final long modifiedTime;
}
