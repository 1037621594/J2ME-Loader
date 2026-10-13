package ru.playsoftware.j2meloader.memsearch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ValueType {
	/** Matches every numeric field type, for when the user does not know which one is used. */
	ANY(Number.class, 0, 0),
	BYTE(byte.class, Byte.MIN_VALUE, Byte.MAX_VALUE),
	SHORT(short.class, Short.MIN_VALUE, Short.MAX_VALUE),
	INT(int.class, Integer.MIN_VALUE, Integer.MAX_VALUE),
	LONG(long.class, Long.MIN_VALUE, Long.MAX_VALUE),
	FLOAT(float.class, 0, 0),
	DOUBLE(double.class, 0, 0),
	BOOLEAN(boolean.class, 0, 0),
	STRING(String.class, 0, 0);

	private final Class<?> fieldClass;
	private final long min;
	private final long max;

	@Override
	public String toString() {
		return this == ANY ? "Auto" : super.toString();
	}

	/** The type a field of the given declared class carries, or null when it is not searchable. */
	public static ValueType forFieldClass(Class<?> c) {
		if (c == byte.class || c == Byte.class) {
			return BYTE;
		}
		if (c == short.class || c == Short.class) {
			return SHORT;
		}
		if (c == int.class || c == Integer.class) {
			return INT;
		}
		if (c == long.class || c == Long.class) {
			return LONG;
		}
		if (c == float.class || c == Float.class) {
			return FLOAT;
		}
		if (c == double.class || c == Double.class) {
			return DOUBLE;
		}
		if (c == boolean.class || c == Boolean.class) {
			return BOOLEAN;
		}
		if (c == String.class) {
			return STRING;
		}
		return null;
	}

	/** True when a value of the given field type counts for this search type. */
	public boolean matches(ValueType field) {
		return this == ANY ? field.isNumeric() : this == field;
	}

	public boolean isInteger() {
		return this == BYTE || this == SHORT || this == INT || this == LONG;
	}

	/** ANY accepts both integers and decimals, so its input allows a fraction. */
	public boolean isFloating() {
		return this == FLOAT || this == DOUBLE || this == ANY;
	}

	public boolean isNumeric() {
		return isInteger() || isFloating();
	}

	/**
	 * Parses user input into the normalized representation:
	 * Long for integer types, Double for float/double, Boolean, String.
	 */
	public Object parse(String text) {
		String s = text.trim();
		switch (this) {
			case BOOLEAN:
				if (s.equalsIgnoreCase("true") || s.equals("1")) {
					return Boolean.TRUE;
				}
				if (s.equalsIgnoreCase("false") || s.equals("0")) {
					return Boolean.FALSE;
				}
				throw new NumberFormatException(text);
			case STRING:
				return text;
			case ANY:
				// An integer literal stays exact, anything else is read as a double.
				try {
					return Long.parseLong(s);
				} catch (NumberFormatException e) {
					return Double.parseDouble(s);
				}
			case FLOAT:
				return (double) Float.parseFloat(s);
			case DOUBLE:
				return Double.parseDouble(s);
			default:
				long v = Long.parseLong(s);
				if (v < min || v > max) {
					throw new NumberFormatException(text);
				}
				return v;
		}
	}

	public static Object normalize(Object raw) {
		if (raw instanceof Float) {
			return ((Float) raw).doubleValue();
		}
		if (raw instanceof Double) {
			return raw;
		}
		if (raw instanceof Number) {
			return ((Number) raw).longValue();
		}
		return raw;
	}

	public Object toFieldValue(Object normalized) {
		switch (this) {
			case BYTE:
				return ((Long) normalized).byteValue();
			case SHORT:
				return ((Long) normalized).shortValue();
			case INT:
				return ((Long) normalized).intValue();
			case FLOAT:
				return ((Double) normalized).floatValue();
			default:
				return normalized;
		}
	}

	public String format(Object normalized) {
		if (normalized == null) {
			return "?";
		}
		if (this == FLOAT && normalized instanceof Double) {
			return String.valueOf(((Double) normalized).floatValue());
		}
		return String.valueOf(normalized);
	}
}
