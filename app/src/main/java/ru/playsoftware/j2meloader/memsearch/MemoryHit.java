package ru.playsoftware.j2meloader.memsearch;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@RequiredArgsConstructor
public class MemoryHit {
	/** The type of the value that was found, which may differ from the search type. */
	private final ValueType type;
	/** Owning instance for instance fields, the array for array elements, null for static fields. */
	private final Object target;
	/** Null for array elements. */
	private final Field field;
	private final Class<?> ownerClass;
	private final String member;
	/** Array index, -1 for plain fields. */
	private final int index;
	/** True when the location must not be written to, e.g. java.util container internals. */
	private final boolean readOnly;

	/** 1-based number of the owning instance in an instance listing, 0 otherwise. */
	@Setter
	private int instanceNo;
	@Setter
	private Object lastValue;
	@Setter
	private volatile boolean locked;
	@Setter
	private volatile Object lockValue;

	public boolean isStaticField() {
		return field != null && Modifier.isStatic(field.getModifiers());
	}

	public String label() {
		String name = ownerClass.getName();
		name = name.substring(name.lastIndexOf('.') + 1);
		StringBuilder sb = new StringBuilder(name);
		if (instanceNo > 0) {
			sb.append('#').append(instanceNo);
		}
		sb.append('.').append(member);
		if (index >= 0) {
			sb.append('[').append(index).append(']');
		}
		if (isStaticField()) {
			sb.append(" (static)");
		}
		return sb.toString();
	}
}
