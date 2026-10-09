package ru.playsoftware.j2meloader.memsearch;

import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.shell.AppClassLoader;

import dalvik.system.DexFile;
import lombok.AllArgsConstructor;

/**
 * Searches MIDlet heap state reachable through reflection: static fields of the MIDlet classes
 * and everything referenced from them, the MIDlet instance and the current displayable.
 */
public class MemoryScanner {
	private static final String TAG = MemoryScanner.class.getSimpleName();
	private static final int MAX_HITS = 100_000;

	@AllArgsConstructor
	private static final class Node {
		final Object obj;
		final Class<?> owner;
		final String member;
	}

	private final ClassLoader loader;
	private final ValueType type;
	private final SearchMode mode;
	private final Object v1;
	private final Object v2;
	private final AtomicBoolean cancel;

	private final List<MemoryHit> hits = new ArrayList<>();
	private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
	private final ArrayDeque<Node> stack = new ArrayDeque<>();
	private final List<Node> deferredArrays = new ArrayList<>();
	private final Map<Class<?>, Field[]> instanceFields = new HashMap<>();
	private long visitedCount;
	private boolean truncated;

	private MemoryScanner(ClassLoader loader, ValueType type, SearchMode mode, Object v1, Object v2,
						  AtomicBoolean cancel) {
		this.loader = loader;
		this.type = type;
		this.mode = mode;
		this.v1 = v1;
		this.v2 = v2;
		this.cancel = cancel;
	}

	public static ScanResult firstScan(ClassLoader loader, Object[] roots, List<String> classNames,
									   ValueType type, SearchMode mode, Object v1, Object v2,
									   AtomicBoolean cancel) {
		MemoryScanner scanner = new MemoryScanner(loader, type, mode, v1, v2, cancel);
		return scanner.run(roots, classNames);
	}

	public static ScanResult rescan(List<MemoryHit> previous, SearchMode mode, Object v1, Object v2,
									AtomicBoolean cancel) {
		List<MemoryHit> result = new ArrayList<>();
		for (MemoryHit hit : previous) {
			if (cancel.get()) {
				break;
			}
			try {
				Object cur = read(hit);
				if (test(hit.getType(), mode, cur, hit.getLastValue(), v1, v2)) {
					hit.setLastValue(cur);
					result.add(hit);
				}
			} catch (Throwable ignored) {
			}
		}
		return new ScanResult(result, false, 0);
	}

	@SuppressWarnings("deprecation")
	public static List<String> listClassNames(String dexPath) {
		List<String> names = new ArrayList<>();
		if (dexPath == null) {
			return names;
		}
		try {
			DexFile dex = new DexFile(dexPath);
			try {
				Enumeration<String> entries = dex.entries();
				while (entries.hasMoreElements()) {
					names.add(entries.nextElement());
				}
			} finally {
				dex.close();
			}
		} catch (Throwable t) {
			Log.w(TAG, "Can't enumerate classes of " + dexPath, t);
		}
		return names;
	}

	public static Object read(MemoryHit hit) throws IllegalAccessException {
		Object raw = hit.getIndex() >= 0
				? Array.get(hit.getTarget(), hit.getIndex())
				: hit.getField().get(hit.getTarget());
		return ValueType.normalize(raw);
	}

	public static void write(MemoryHit hit, Object normalized) throws IllegalAccessException {
		Object value = hit.getType().toFieldValue(normalized);
		if (hit.getIndex() >= 0) {
			Array.set(hit.getTarget(), hit.getIndex(), value);
		} else {
			hit.getField().set(hit.getTarget(), value);
		}
	}

	static boolean test(ValueType type, SearchMode mode, Object cur, Object prev, Object v1, Object v2) {
		if (cur == null) {
			return false;
		}
		switch (mode) {
			case EQUAL:
				return cur.equals(v1);
			case CHANGED:
				return prev != null && !cur.equals(prev);
			case UNCHANGED:
				return cur.equals(prev);
			default:
				break;
		}
		if (!type.isNumeric()) {
			return false;
		}
		switch (mode) {
			case GREATER:
				return compare(cur, v1) > 0;
			case LESS:
				return compare(cur, v1) < 0;
			case RANGE:
				return compare(cur, v1) >= 0 && compare(cur, v2) <= 0;
			case INCREASED:
				return prev != null && compare(cur, prev) > 0;
			case DECREASED:
				return prev != null && compare(cur, prev) < 0;
			default:
				return false;
		}
	}

	private static int compare(Object a, Object b) {
		if (a instanceof Long && b instanceof Long) {
			return Long.compare((Long) a, (Long) b);
		}
		return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
	}

	private ScanResult run(Object[] roots, List<String> classNames) {
		for (String name : classNames) {
			if (cancel.get() || truncated) {
				break;
			}
			try {
				Class<?> c = loader instanceof AppClassLoader
						? ((AppClassLoader) loader).findLoaded(name)
						: Class.forName(name, false, loader);
				if (c == null) {
					continue;
				}
				for (Field f : c.getDeclaredFields()) {
					if (Modifier.isStatic(f.getModifiers())) {
						f.setAccessible(true);
						processField(null, f);
					}
				}
			} catch (Throwable ignored) {
			}
		}
		for (Object root : roots) {
			if (root != null) {
				push(root, root.getClass(), "this");
			}
		}
		drain();
		for (Node node : deferredArrays) {
			if (cancel.get() || truncated) {
				break;
			}
			scanPrimitiveArray(node);
		}
		return new ScanResult(hits, truncated, visitedCount);
	}

	private void drain() {
		while (!stack.isEmpty() && !cancel.get() && !truncated) {
			Node node = stack.pop();
			visitedCount++;
			try {
				visit(node);
			} catch (Throwable t) {
				Log.w(TAG, "visit failed: " + node.obj.getClass().getName(), t);
			}
		}
	}

	private boolean isAppClass(Class<?> c) {
		if (c.getClassLoader() != loader) {
			return false;
		}
		String name = c.getName();
		return !name.startsWith("javax.microedition.") && !name.startsWith("ru.playsoftware.")
				&& !name.startsWith("android.") && !name.startsWith("androidx.");
	}

	private boolean isTraversable(Class<?> c) {
		if (c.isArray()) {
			return !c.getComponentType().isPrimitive() || c.getComponentType() == type.getFieldClass();
		}
		return isAppClass(c) || c.getName().startsWith("java.util.");
	}

	private void push(Object obj, Class<?> owner, String member) {
		if (!isTraversable(obj.getClass())) {
			return;
		}
		if (visited.add(obj)) {
			stack.push(new Node(obj, owner, member));
		}
	}

	private void visit(Node node) throws IllegalAccessException {
		Class<?> c = node.obj.getClass();
		if (c.isArray()) {
			Class<?> comp = c.getComponentType();
			if (comp.isPrimitive()) {
				deferredArrays.add(node);
			} else if (comp == String.class) {
				if (type == ValueType.STRING) {
					scanObjectArray(node);
				}
			} else {
				Object[] arr = (Object[]) node.obj;
				String member = node.member + "[]";
				for (Object e : arr) {
					if (e != null) {
						push(e, node.owner, member);
					}
				}
			}
			return;
		}
		for (Field f : instanceFieldsOf(c)) {
			if (truncated) {
				return;
			}
			processField(node.obj, f);
		}
	}

	private Field[] instanceFieldsOf(Class<?> c) {
		Field[] cached = instanceFields.get(c);
		if (cached != null) {
			return cached;
		}
		List<Field> list = new ArrayList<>();
		for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
			if (!isAppClass(k) && !k.getName().startsWith("java.util.")) {
				break;
			}
			for (Field f : k.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers())) {
					try {
						f.setAccessible(true);
						list.add(f);
					} catch (Throwable ignored) {
					}
				}
			}
		}
		cached = list.toArray(new Field[0]);
		instanceFields.put(c, cached);
		return cached;
	}

	private void processField(Object holder, Field f) throws IllegalAccessException {
		Class<?> ft = f.getType();
		boolean isStatic = holder == null;
		if (ft.isPrimitive() || ft == String.class) {
			if (ft != type.getFieldClass() || !isAppClass(f.getDeclaringClass())) {
				return;
			}
			if (isStatic && Modifier.isFinal(f.getModifiers())) {
				return;
			}
			Object cur = ValueType.normalize(f.get(holder));
			if (test(type, mode, cur, null, v1, v2)) {
				MemoryHit hit = new MemoryHit(type, holder, f, f.getDeclaringClass(), f.getName(), -1);
				hit.setLastValue(cur);
				addHit(hit);
			}
			return;
		}
		Object value = f.get(holder);
		if (value != null) {
			push(value, f.getDeclaringClass(), f.getName());
		}
	}

	private void scanObjectArray(Node node) {
		Object[] arr = (Object[]) node.obj;
		for (int i = 0; i < arr.length && !truncated; i++) {
			Object cur = arr[i];
			if (cur != null && test(type, mode, cur, null, v1, v2)) {
				MemoryHit hit = new MemoryHit(type, arr, null, node.owner, node.member, i);
				hit.setLastValue(cur);
				addHit(hit);
			}
		}
	}

	private void scanPrimitiveArray(Node node) {
		Object arr = node.obj;
		int len = Array.getLength(arr);
		for (int i = 0; i < len && !truncated; i++) {
			Object cur = ValueType.normalize(Array.get(arr, i));
			if (test(type, mode, cur, null, v1, v2)) {
				MemoryHit hit = new MemoryHit(type, arr, null, node.owner, node.member, i);
				hit.setLastValue(cur);
				addHit(hit);
			}
		}
	}

	private void addHit(MemoryHit hit) {
		if (hits.size() >= MAX_HITS) {
			truncated = true;
			return;
		}
		hits.add(hit);
	}
}
