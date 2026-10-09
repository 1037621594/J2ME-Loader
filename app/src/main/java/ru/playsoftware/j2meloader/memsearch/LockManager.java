package ru.playsoftware.j2meloader.memsearch;

import android.os.Handler;
import android.os.HandlerThread;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LockManager {
	private static final long INTERVAL_MS = 30;

	private final List<MemoryHit> locked = new CopyOnWriteArrayList<>();
	private Handler handler;
	private boolean running;

	public synchronized void lock(MemoryHit hit, Object value) {
		hit.setLockValue(value);
		hit.setLocked(true);
		if (!locked.contains(hit)) {
			locked.add(hit);
		}
		if (handler == null) {
			HandlerThread thread = new HandlerThread("MemoryLock");
			thread.start();
			handler = new Handler(thread.getLooper());
		}
		if (!running) {
			running = true;
			handler.post(this::tick);
		}
	}

	public void unlock(MemoryHit hit) {
		locked.remove(hit);
		hit.setLocked(false);
	}

	public int size() {
		return locked.size();
	}

	public void unlockAll() {
		for (MemoryHit hit : locked) {
			hit.setLocked(false);
		}
		locked.clear();
	}

	private void tick() {
		for (MemoryHit hit : locked) {
			try {
				MemoryScanner.write(hit, hit.getLockValue());
			} catch (Throwable t) {
				unlock(hit);
			}
		}
		synchronized (this) {
			if (locked.isEmpty()) {
				running = false;
				return;
			}
		}
		handler.postDelayed(this::tick, INTERVAL_MS);
	}
}
