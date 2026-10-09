package ru.playsoftware.j2meloader.backup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BackupMeta {
	private String name;
	private String vendor;
	private String version;
	private long backupTime;
	private boolean hasGame;
}
