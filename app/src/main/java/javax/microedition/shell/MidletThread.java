/*
 *  Copyright 2020 Yury Kharchenko
 *  Copyright 2022-2023 Arman Jussupgaliyev
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.microedition.shell;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import java.io.File;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;
import javax.microedition.util.ContextHolder;

import androidx.annotation.NonNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import ru.playsoftware.j2meloader.backup.BackupManager;
import ru.playsoftware.j2meloader.config.Config;

public class MidletThread extends HandlerThread implements Handler.Callback {
	private static final String TAG = MidletThread.class.getName();
	private static final UncaughtExceptionHandler uncaughtExceptionHandler = (t, e) ->
			Log.e(TAG, "Error in thread: \"" + t + "\" after destroy app called", e);

	private static final int INIT = 0;
	private static final int START = 1;
	private static final int PAUSE = 2;
	private static final int DESTROY = 3;
	private static final int RESTORE_BACKUP = 4;
	private static final int UNINITIALIZED = 0;
	private static final int STARTED = 1;
	private static final int PAUSED = 2;
	private static final int DESTROYED = 3;
	public static String[] startAfterDestroy;
	private static MidletThread instance;
	private final MicroLoader microLoader;
	private final String mainClass;
	private MIDlet midlet;
	private final Handler handler;
	private int state;

	interface RestoreCallback {
		void onFailed(Throwable error);
	}

	@Data
	@AllArgsConstructor
	private static final class RestoreRequest {
		private final File appDir;
		private final String[] restartArgs;
		private final RestoreCallback callback;
	}

	private MidletThread(MicroLoader microLoader, String mainClass) {
		super("MidletMain");
		this.microLoader = microLoader;
		this.mainClass = mainClass;
		start();
		handler = new Handler(getLooper(), this);
		handler.obtainMessage(INIT).sendToTarget();
	}

	static void create(MicroLoader microLoader, String mainClass) {
		instance = new MidletThread(microLoader, mainClass);
	}

	public static void notifyDestroyed() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		if (instance != null) {
			instance.state = DESTROYED;
		}
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			activity.finish();
		}
		if (startAfterDestroy != null) {
			String mainClass = startAfterDestroy.length > 3 ? startAfterDestroy[3] : null;
			Config.startApp(ContextHolder.getActivity(), startAfterDestroy[0], startAfterDestroy[1], false,
					startAfterDestroy[2], mainClass);
		}
		Process.killProcess(Process.myPid());
	}

	public static MIDlet getMidlet() {
		return instance != null ? instance.midlet : null;
	}

	public static void notifyPaused() {
		instance.state = PAUSED;
	}

	static void pauseApp() {
		if (instance != null)
			instance.handler.obtainMessage(PAUSE).sendToTarget();
	}

	/**
	 * Restores the backup while the MIDlet is stopped, then restarts the whole process.
	 * RecordStore keeps static in-memory caches, so a restart is the only way to make
	 * the game read the restored files.
	 */
	static void restoreBackup(File appDir, String[] restartArgs, RestoreCallback callback) {
		if (instance == null) {
			callback.onFailed(new IllegalStateException("MIDlet is not running"));
			return;
		}
		instance.handler.obtainMessage(RESTORE_BACKUP, new RestoreRequest(appDir, restartArgs, callback))
				.sendToTarget();
	}

	public static void resumeApp() {
		MicroActivity activity = ContextHolder.getActivity();
		if (instance != null && activity != null && activity.isVisible())
			instance.handler.obtainMessage(START).sendToTarget();
	}

	static void destroyApp() {
		Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Process.killProcess(Process.myPid());
		}, "ForceDestroyTimer").start();
		MicroActivity activity = ContextHolder.getActivity();
		if (activity != null) {
			Displayable current = activity.getCurrent();
			if (current instanceof Canvas) {
				Canvas canvas = (Canvas) current;
				canvas.postKeyPressed(Canvas.KEY_END);
				canvas.postKeyReleased(Canvas.KEY_END);
			}
		}
		if (instance != null) {
			instance.handler.obtainMessage(DESTROY).sendToTarget();
		}
	}

	@Override
	public boolean handleMessage(@NonNull Message msg) {
		switch (msg.what) {
			case INIT:
				if (state != UNINITIALIZED) {
					break;
				}
				try {
					midlet = microLoader.loadMIDlet(this.mainClass);
					state = PAUSED;
				} catch (Throwable t) {
					throw new RuntimeException("Init midlet failed", t);
				}
				break;
			case START:
				if (state != PAUSED) {
					break;
				}
				try {
					state = STARTED;
					midlet.startApp();
				} catch (MIDletStateChangeException e) {
					state = PAUSED;
					Log.w(TAG, "Midlet doesn't want to start!", e);
				} catch (Throwable t) {
					state = DESTROYED;
					throw new RuntimeException("Failed startApp", t);
				}
				break;
			case PAUSE:
				if (state != STARTED) {
					break;
				}
				try {
					midlet.pauseApp();
					state = PAUSED;
				} catch (Throwable t) {
					state = DESTROYED;
					try {
						midlet.destroyApp(true);
					} catch (MIDletStateChangeException ignored) {}
					throw new RuntimeException("Filed pauseApp", t);
				}
				break;
			case DESTROY:
				if (state == DESTROYED) {
					notifyDestroyed();
					break;
				}
				state = DESTROYED;
				try {
					midlet.destroyApp(true);
				} catch (MIDletStateChangeException e) {
					Log.w(TAG, "Midlet didn't want to die!", e);
				} catch (Throwable t) {
					Log.e(TAG, "Filed destroyApp:", t);
				}
				notifyDestroyed();
				break;
			case RESTORE_BACKUP:
				handleRestoreBackup((RestoreRequest) msg.obj);
				break;
		}
		return true;
	}

	private void handleRestoreBackup(RestoreRequest request) {
		Log.i(TAG, "restore backup requested, state=" + state);
		try {
			if (state == STARTED) {
				midlet.pauseApp();
				state = PAUSED;
			}
			try {
				midlet.destroyApp(true);
			} catch (MIDletStateChangeException e) {
				Log.w(TAG, "Midlet refused destruction while restoring backup", e);
			}
			state = DESTROYED;
			BackupManager.restoreApp(request.getAppDir());
			startAfterDestroy = request.getRestartArgs();
			Log.i(TAG, "backup restored, restarting process");
			notifyDestroyed();
		} catch (Throwable t) {
			Log.e(TAG, "Can't restore backup", t);
			if (state == DESTROYED) {
				try {
					state = STARTED;
					midlet.startApp();
				} catch (Throwable restartError) {
					state = DESTROYED;
					Log.e(TAG, "Can't resume MIDlet after restore failure", restartError);
				}
			}
			new Handler(Looper.getMainLooper()).post(() -> request.getCallback().onFailed(t));
		}
	}
}
