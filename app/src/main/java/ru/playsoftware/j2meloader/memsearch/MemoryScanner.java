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
 * Searches MIDlet heap state reachable through reflection: static fields of the MIDlet classes and
 * everything referenced from them, the MIDlet instance and the current displayable.
 *
 * <p>Values are collected from primitive and boxed fields, from boxed or String values reached by
 * reference (a map entry value, a Vector element), and from array elements. Values owned by
 * java.util internals are reported read-only, because writing them can break the container.
 */
public class MemoryScanner {
	private static final String TAG = MemoryScanner.class.getSimpleName();
	/** Hits kept in the result; further matches are only counted, never lost. */
	private static final int MAX_HITS = 100_000;
	/** Objects to visit before giving up; bounds the memory the visited set holds. */
	private static final long MAX_VISITS = 500_000;

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
	private long totalHits;
	private boolean visitLimited;

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
		return new ScanResult(result, result.size(), false, 0, false);
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
				// With ANY the value and the field can have different types, so compare numerically.
				return type == ValueType.ANY ? compare(cur, v1) == 0 : cur.equals(v1);
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
			if (cancel.get() || visitLimited) {
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
			if (cancel.get() || visitLimited) {
				break;
			}
			scanPrimitiveArray(node);
		}
		return new ScanResult(hits, totalHits, totalHits > hits.size(), visitedCount, visitLimited);
	}

	private void drain() {
		while (!stack.isEmpty() && !cancel.get() && !visitLimited) {
			Node node = stack.pop();
			visitedCount++;
			if (visitedCount > MAX_VISITS) {
				visitLimited = true;
				break;
			}
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
			Class<?> comp = c.getComponentType();
			if (!comp.isPrimitive()) {
				return true;
			}
			// Reading a primitive array only pays off for its own element type.
			return type == ValueType.ANY || comp == type.getFieldClass();
		}
		return isAppClass(c) || c.getName().startsWith("java.util.");
	}

	/**
	 * Field values are accepted from app classes and from java.util container internals
	 * (HashMap$Node, TreeMap$Entry &amp; co.), which is where values stored in maps live. Top-level
	 * java.util classes are excluded so structural fields such as Vector.size are not reported.
	 */
	private boolean isFieldValueSource(Class<?> c) {
		if (isAppClass(c)) {
			return true;
		}
		String name = c.getName();
		return name.startsWith("java.util.") && name.indexOf('$') >= 0;
	}

	/** Array elements are accepted from app classes and from any java.util backing array. */
	private boolean isArrayValueSource(Class<?> c) {
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
				return;
			}
			Object[] arr = (Object[]) node.obj;
			for (int i = 0; i < arr.length && !visitLimited; i++) {
				Object e = arr[i];
				if (e == null) {
					continue;
				}
				checkArrayValue(arr, node.owner, node.member, i, e);
				push(e, node.owner, node.member + "[]");
			}
			return;
		}
		for (Field f : instanceFieldsOf(c)) {
			if (visitLimited) {
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
		if (f.getType().isPrimitive()) {
			// Field.get boxes primitives, so primitive and boxed fields share one path.
			checkFieldValue(holder, f, f.get(holder));
			return;
		}
		Object value = f.get(holder);
		if (value == null) {
			return;
		}
		// A boxed or String value reached by reference, e.g. a Vector element or a map value.
		checkFieldValue(holder, f, value);
		push(value, f.getDeclaringClass(), f.getName());
	}

	private void checkFieldValue(Object holder, Field f, Object value) {
		Class<?> declaring = f.getDeclaringClass();
		if (!isFieldValueSource(declaring)
				|| (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()))) {
			return;
		}
		MemoryHit hit = valueHit(holder, f, declaring, f.getName(), -1, value);
		if (hit != null) {
			addHit(hit);
		}
	}

	private void checkArrayValue(Object array, Class<?> owner, String member, int index, Object value) {
		if (!isArrayValueSource(owner)) {
			return;
		}
		MemoryHit hit = valueHit(array, null, owner, member, index, value);
		if (hit != null) {
			addHit(hit);
		}
	}

	/** Builds a hit when {@code value} matches the current search, or null when it does not. */
	private MemoryHit valueHit(Object target, Field field, Class<?> owner, String member, int index,
							   Object value) {
		if (value == null) {
			return null;
		}
		ValueType fieldType = ValueType.forFieldClass(value.getClass());
		if (fieldType == null || !type.matches(fieldType)) {
			return null;
		}
		Object cur = ValueType.normalize(value);
		if (!test(type, mode, cur, null, v1, v2)) {
			return null;
		}
		MemoryHit hit = new MemoryHit(fieldType, target, field, owner, member, index, !isAppClass(owner));
		hit.setLastValue(cur);
		return hit;
	}

	private void scanPrimitiveArray(Node node) {
		if (!isArrayValueSource(node.owner)) {
			return;
		}
		Object arr = node.obj;
		int len = Array.getLength(arr);
		for (int i = 0; i < len && !visitLimited; i++) {
			MemoryHit hit = valueHit(arr, null, node.owner, node.member, i, Array.get(arr, i));
			if (hit != null) {
				addHit(hit);
			}
		}
	}

	private void addHit(MemoryHit hit) {
		totalHits++;
		if (hits.size() < MAX_HITS) {
			hits.add(hit);
		}
	}
}
