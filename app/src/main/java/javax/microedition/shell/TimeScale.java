/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell;

/**
 * Virtual clock used by converted MIDlets. Calls to the affected Java time APIs are
 * redirected here while the JAR is converted to DEX.
 */
public final class TimeScale {
	private static final Object lock = new Object();
	private static final long WAIT_RECHECK_MILLIS = 100L;

	private static long realAnchorNanos;
	private static long virtualElapsedNanos;
	private static long wallClockEpochMillis;
	private static long nanoClockEpoch;
	private static volatile float multiplier;

	static {
		reset();
	}

	private TimeScale() {
	}

	public static void reset() {
		synchronized (lock) {
			realAnchorNanos = System.nanoTime();
			virtualElapsedNanos = 0L;
			wallClockEpochMillis = System.currentTimeMillis();
			nanoClockEpoch = realAnchorNanos;
			multiplier = 1f;
		}
	}

	public static void setMultiplier(float value) {
		if (value < 1f || Float.isInfinite(value) || Float.isNaN(value)) {
			throw new IllegalArgumentException("Invalid time multiplier: " + value);
		}
		synchronized (lock) {
			long realNowNanos = System.nanoTime();
			virtualElapsedNanos = elapsedNanosLocked(realNowNanos);
			realAnchorNanos = realNowNanos;
			multiplier = value;
		}
	}

	public static float getMultiplier() {
		return multiplier;
	}

	public static long currentTimeMillis() {
		synchronized (lock) {
			return wallClockEpochMillis + elapsedNanosLocked(System.nanoTime()) / 1000000L;
		}
	}

	public static long nanoTime() {
		synchronized (lock) {
			return nanoClockEpoch + elapsedNanosLocked(System.nanoTime());
		}
	}

	public static void sleep(long millis) throws InterruptedException {
		if (millis < 0L) {
			throw new IllegalArgumentException("timeout value is negative");
		}
		Thread.sleep(scaleMillis(millis));
	}

	public static void sleep(long millis, int nanos) throws InterruptedException {
		if (millis < 0L || nanos < 0 || nanos > 999999) {
			throw new IllegalArgumentException("invalid timeout");
		}
		long requestedNanos = millis > (Long.MAX_VALUE - nanos) / 1000000L ?
				Long.MAX_VALUE : millis * 1000000L + nanos;
		long scaledNanos = scaleNanos(requestedNanos);
		Thread.sleep(scaledNanos / 1000000L, (int) (scaledNanos % 1000000L));
	}

	/** Allows the converted Timer implementation to wait in real time for virtual deadlines. */
	public static void wait(Object monitor, long millis) throws InterruptedException {
		if (millis <= 0L) {
			monitor.wait(millis);
			return;
		}
		long timeoutMillis = Math.max(1L, Math.min(scaleMillis(millis), WAIT_RECHECK_MILLIS));
		monitor.wait(timeoutMillis);
	}

	private static long elapsedNanosLocked(long realNowNanos) {
		long realElapsedNanos = realNowNanos - realAnchorNanos;
		long scaledElapsedNanos = (long) (realElapsedNanos * multiplier);
		if (Long.MAX_VALUE - virtualElapsedNanos < scaledElapsedNanos) {
			return Long.MAX_VALUE;
		}
		return virtualElapsedNanos + scaledElapsedNanos;
	}

	private static long scaleMillis(long millis) {
		long nanos = scaleNanos(millis > Long.MAX_VALUE / 1000000L ?
				Long.MAX_VALUE : millis * 1000000L);
		return nanos / 1000000L;
	}

	private static long scaleNanos(long nanos) {
		if (nanos == 0L) {
			return 0L;
		}
		float currentMultiplier = multiplier;
		long result = (long) (nanos / currentMultiplier);
		return result == 0L ? 1L : result;
	}
}
