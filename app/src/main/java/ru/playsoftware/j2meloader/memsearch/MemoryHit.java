package ru.playsoftware.j2meloader.memsearch;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@RequiredArgsConstructor
public class MemoryHit {
	private final ValueType type;
	/** Owning instance for instance fields, the array for array elements, null for static fields. */
	private final Object target;
	/** Null for array elements. */
	private final Field field;
	private final Class<?> ownerClass;
	private final String member;
	/** Array index, -1 for plain fields. */
	private final int index;

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
		StringBuilder sb = new StringBuilder(name).append('.').append(member);
		if (index >= 0) {
			sb.append('[').append(index).append(']');
		}
		if (isStaticField()) {
			sb.append(" (static)");
		}
		return sb.toString();
	}
}
