package ru.playsoftware.j2meloader.memsearch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ValueType {
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

	public boolean isInteger() {
		return this == BYTE || this == SHORT || this == INT || this == LONG;
	}

	public boolean isFloating() {
		return this == FLOAT || this == DOUBLE;
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
