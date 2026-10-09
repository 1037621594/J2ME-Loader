package ru.playsoftware.j2meloader.backup;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BackupSummary {
	private int success;
	private int skipped;
	private int failed;
}
