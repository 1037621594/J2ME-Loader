package ru.playsoftware.j2meloader.backup;

import com.google.gson.annotations.SerializedName;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BackupMeta {
	@SerializedName("name")
	private String name;
	@SerializedName("vendor")
	private String vendor;
	@SerializedName("version")
	private String version;
	@SerializedName("backupTime")
	private long backupTime;
	@SerializedName("hasGame")
	private boolean hasGame;
}
