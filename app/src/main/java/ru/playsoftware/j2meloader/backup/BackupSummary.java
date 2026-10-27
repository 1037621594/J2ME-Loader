package ru.playsoftware.j2meloader.backup;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BackupSummary {
	private int success;
	private int skipped;
	private int failed;
	private int cloudSuccess;
	private int cloudFailed;

	public BackupSummary(int success, int skipped, int failed) {
		this(success, skipped, failed, 0, 0);
	}
}
