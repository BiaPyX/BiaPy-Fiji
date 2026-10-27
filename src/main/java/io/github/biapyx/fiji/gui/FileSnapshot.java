package io.github.biapyx.fiji.gui;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The files of a folder (and its subfolders) and their modification times when a run starts, to tell the files
 * the run writes from those of previous runs with the same job name. Without comparing with the clock: a folder on
 * another drive or a network share may have its own clock, ahead or behind this computer's.
 */
final class FileSnapshot {

	private final Map<String, Long> times = new HashMap<>();

	FileSnapshot(File dir) {
		add(dir);
	}

	private void add(File dir) {
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) {
			if (f.isDirectory()) add(f);
			else times.put(f.getAbsolutePath(), f.lastModified());
		}
	}

	/** True if the file was written after the snapshot: it was not there, or it has changed since. */
	boolean isNew(File f) {
		Long t = times.get(f.getAbsolutePath());
		return f.isFile() && (t == null || t != f.lastModified());
	}

	/** True if any file directly in the folder was written after the snapshot. */
	boolean hasNew(File dir) {
		File[] files = dir.listFiles();
		if (files != null) for (File f : files) if (isNew(f)) return true;
		return false;
	}
}
