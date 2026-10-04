/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package javax.microedition.shell;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.FileUtils;

/** Persistent emulator session snapshots. */
final class SnapshotManager {
    private static final int VERSION = 1;
    private static final String SNAPSHOT_DIR = "save-states";
    private static final String SLOT_DIR = "slot0";
    private static final String METADATA_FILE = "metadata.bin";

    private SnapshotManager() {
    }

    static final class Metadata {
        final String appName;
        final String appPath;
        final String arguments;
        final String mainClass;

        Metadata(String appName, String appPath, String arguments, String mainClass) {
            this.appName = appName;
            this.appPath = appPath;
            this.arguments = arguments;
            this.mainClass = mainClass;
        }
    }

    static File getSnapshotDirectory(String appPath) {
        File appDir = new File(appPath);
        File converted = appDir.getParentFile();
        File workDir = converted == null ? null : converted.getParentFile();
        if (workDir == null) {
            throw new IllegalArgumentException("Invalid MIDlet path: " + appPath);
        }
        return new File(new File(workDir, SNAPSHOT_DIR), appDir.getName());
    }

    static boolean exists(String appPath) {
		return appPath != null && new File(getSnapshotDirectory(appPath), SLOT_DIR).isDirectory();
    }

    static void save(Metadata metadata) throws IOException {
        File root = getSnapshotDirectory(metadata.appPath);
        File parent = root.getParentFile();
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Can't create snapshot directory: " + parent);
        }
        File temporary = new File(root, SLOT_DIR + ".tmp");
        File target = new File(root, SLOT_DIR);
        File backup = new File(root, SLOT_DIR + ".bak");
        delete(temporary);
        delete(backup);
        if (!temporary.mkdirs()) {
            throw new IOException("Can't create temporary snapshot directory: " + temporary);
        }
        try {
            writeMetadata(metadata, new File(temporary, METADATA_FILE));
            copyDirectory(new File(AppClassLoader.getDataDir()), new File(temporary, "data"));
            copyDirectory(new File(Config.getFsInternalDir()), new File(temporary, "c"));
            if (!FileUtils.isExternalStorageLegacy()) {
                copyDirectory(new File(Config.getFsExternalDir()), new File(temporary, "e"));
            }
            if (target.exists() && !target.renameTo(backup)) {
                throw new IOException("Can't back up existing snapshot: " + target);
            }
            if (!temporary.renameTo(target)) {
                if (backup.exists()) {
                    backup.renameTo(target);
                }
                throw new IOException("Can't commit snapshot: " + target);
            }
            delete(backup);
        } catch (IOException | RuntimeException e) {
            delete(temporary);
            if (!target.exists() && backup.exists()) {
                backup.renameTo(target);
            }
            throw e;
        }
    }

    static Metadata load(String appPath) throws IOException {
        File slot = new File(getSnapshotDirectory(appPath), SLOT_DIR);
        if (!slot.isDirectory()) {
            throw new IOException("Snapshot does not exist");
        }
        return readMetadata(new File(slot, METADATA_FILE));
    }

    static void restore(Metadata metadata) throws IOException {
        File slot = new File(getSnapshotDirectory(metadata.appPath), SLOT_DIR);
        if (!slot.isDirectory()) {
            throw new IOException("Snapshot does not exist");
        }
        replaceDirectory(new File(slot, "data"), new File(AppClassLoader.getDataDir()));
        replaceDirectory(new File(slot, "c"), new File(Config.getFsInternalDir()));
        if (!FileUtils.isExternalStorageLegacy()) {
            replaceDirectory(new File(slot, "e"), new File(Config.getFsExternalDir()));
        }
    }

    private static void writeMetadata(Metadata metadata, File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(VERSION);
            writeString(out, metadata.appName);
            writeString(out, metadata.appPath);
            writeString(out, metadata.arguments);
            writeString(out, metadata.mainClass);
        }
    }

    private static Metadata readMetadata(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != VERSION) {
                throw new IOException("Unsupported snapshot version");
            }
            return new Metadata(readString(in), readString(in), readString(in), readString(in));
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeUTF(value == null ? "" : value);
    }

    private static String readString(DataInputStream in) throws IOException {
        String value = in.readUTF();
        return value.isEmpty() ? null : value;
    }

    private static void replaceDirectory(File source, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".snapshot-tmp");
        File backup = new File(target.getParentFile(), target.getName() + ".snapshot-bak");
        delete(temporary);
        delete(backup);
        copyDirectory(source, temporary);
        if (target.exists() && !target.renameTo(backup)) {
            delete(temporary);
            throw new IOException("Can't back up directory: " + target);
        }
        if (!temporary.renameTo(target)) {
            delete(temporary);
            if (backup.exists()) {
                backup.renameTo(target);
            }
            throw new IOException("Can't restore snapshot directory: " + target);
        }
        delete(backup);
    }

    private static void copyDirectory(File source, File target) throws IOException {
        if (!source.exists()) {
            if (!target.mkdirs() && !target.isDirectory()) {
                throw new IOException("Can't create directory: " + target);
            }
            return;
        }
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs() && !target.isDirectory()) {
                throw new IOException("Can't create directory: " + target);
            }
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    copyDirectory(file, new File(target, file.getName()));
                }
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Can't create directory: " + parent);
        }
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static void delete(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    delete(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("Can't delete: " + file);
        }
    }
}
