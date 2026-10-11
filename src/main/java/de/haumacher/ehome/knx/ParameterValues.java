/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterValue;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.model.operational.VoidType;

/**
 * Translation between the typed value of a {@link ParameterValue} holder and the notation ETS writes
 * parameter values in (the <code>Value</code> attribute of <code>Parameter</code>,
 * <code>ParameterRef</code> and <code>ParameterInstanceRef</code>).
 *
 * <p>
 * The import decodes with {@link #decode(TLObject, String)}, the derived attribute
 * <code>encodedValue</code> encodes with {@link #encode(TLObject)}: both decide by the same rules
 * which typed attribute holds the value, so that encoding a decoded value gives the text ETS wrote.
 * </p>
 *
 * <ul>
 * <li>An enumeration: the chosen entry ({@link ParameterValue#getChoice()}), written as its numeric
 * value.</li>
 * <li>An integer or time: {@link ParameterValue#getNumber()}, written as a decimal integer.</li>
 * <li>A floating point number: {@link ParameterValue#getDecimal()}, written as a plain decimal with a
 * point (<code>20.5</code>), as XML writes numbers. A value ETS writes with an exponent is read as
 * well, but written back without one.</li>
 * <li>A text, and every type whose values the app does not decode (date, IP address):
 * {@link ParameterValue#getText()}, as written.</li>
 * <li>No value ({@link VoidType}): nothing.</li>
 * </ul>
 *
 * <p>
 * A value that does not fit its type (not a number, outside the range, no entry of the enumeration)
 * is kept unchanged in the text, so that it is not lost; {@link #decode(TLObject, String)} reports
 * it.
 * </p>
 */
public class ParameterValues {

	/** Name of the attribute holding the entry of an enumeration. */
	public static final String CHOICE = "choice";

	/** Name of the attribute holding an integer or a time. */
	public static final String NUMBER = "number";

	/** Name of the attribute holding a floating point number. */
	public static final String DECIMAL = "decimal";

	/** Name of the attribute holding a text, and every value that is not decoded. */
	public static final String TEXT = "text";

	private static final String PARAMETER = "parameter";

	private static final String TYPE = "type";

	private static final String PARAMETER_TYPE = "ehome.operational:Parameter";

	/**
	 * The type of the value of the given holder: a parameter's type, the type of the parameter a
	 * placement places, the type of the parameter a setting sets.
	 *
	 * <p>
	 * This is the one definition of a holder's type: the model's <code>valueType</code> and the
	 * options, constraints and visibility of the typed attributes read it through the TL-Script
	 * function <code>parameterValueType</code> ({@link ParameterValueType}). It navigates the stored
	 * references, so that it also works where derived attributes cannot be read (an import or a
	 * commit check running without a user), and it reads the holder generically, so that it works
	 * on the overlay of a form as well, with the values entered there.
	 * </p>
	 */
	public static ParameterType valueType(TLObject holder) {
		if (holder == null) {
			return null;
		}
		if (isA(holder, PARAMETER_TYPE)) {
			return holder.tValueByName(TYPE) instanceof ParameterType type ? type : null;
		}
		if (holder.tType().getPart(PARAMETER) != null && holder.tValueByName(PARAMETER) instanceof TLObject placed) {
			// A placement places a parameter, a setting sets a placement.
			return valueType(placed);
		}
		return null;
	}

	private static boolean isA(TLObject obj, String typeName) {
		return TLModelUtil.isGeneralization((TLStructuredType) TLModelUtil.findType(typeName), obj.tType());
	}

	/**
	 * Whether the given text matches the given regular expression as a whole.
	 *
	 * <p>
	 * A pattern that cannot be compiled (ETS writes .NET expressions, some of which Java does not
	 * read) restricts nothing: the result is then <code>true</code>.
	 * </p>
	 */
	public static boolean matchesPattern(String text, String pattern) {
		if (text == null || pattern == null || pattern.isEmpty()) {
			return true;
		}
		try {
			return Pattern.compile(pattern).matcher(text).matches();
		} catch (PatternSyntaxException ex) {
			return true;
		}
	}

	/**
	 * The attribute holding the value of a holder whose type is the given one; <code>null</code> for
	 * a type without value.
	 */
	public static String valueAttribute(ParameterType type) {
		if (type instanceof EnumerationType) {
			return CHOICE;
		}
		if (type instanceof NumericType || type instanceof TimeType) {
			return NUMBER;
		}
		if (type instanceof FloatingPointType) {
			return DECIMAL;
		}
		if (type instanceof VoidType) {
			return null;
		}
		return TEXT;
	}

	/**
	 * The value of the given holder in the notation of ETS, <code>null</code> if it holds no value.
	 */
	public static String encode(TLObject holder) {
		if (holder == null) {
			return null;
		}
		String attribute = valueAttribute(valueType(holder));
		Object value = attribute == null ? null : holder.tValueByName(attribute);
		if (value instanceof EnumerationValue entry) {
			return Integer.toString(entry.getValue());
		}
		if (value instanceof Number number && attribute.equals(DECIMAL)) {
			return encodeDecimal(number.doubleValue());
		}
		if (value != null) {
			return value.toString();
		}
		// A value that could not be decoded is kept as text.
		return (String) holder.tValueByName(TEXT);
	}

	/**
	 * A floating point number in the notation of ETS: a plain decimal with a point and without
	 * trailing zeros.
	 */
	public static String encodeDecimal(double value) {
		BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
		return decimal.signum() == 0 ? "0" : decimal.toPlainString();
	}

	/**
	 * Sets the value the given ETS notation describes for the type of the given holder.
	 *
	 * @param holder
	 *        The holder whose type determines the reading. Its other value attributes are not
	 *        changed.
	 * @param encoded
	 *        The value as ETS writes it. An empty value means no value.
	 * @return <code>null</code> if the value was decoded, otherwise the problem, the value then being
	 *         kept unchanged as text.
	 */
	public static ResKey decode(TLObject holder, String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			return null;
		}
		ParameterType type = valueType(holder);
		if (type == null) {
			holder.tUpdateByName(TEXT, encoded);
			return I18NConstants.ERROR_VALUE_WITHOUT_TYPE__VALUE.fill(encoded);
		}
		String attribute = valueAttribute(type);
		if (attribute == null) {
			holder.tUpdateByName(TEXT, encoded);
			return I18NConstants.ERROR_VALUE_FOR_TYPE_WITHOUT_VALUE__VALUE_TYPE.fill(encoded, type.getName());
		}
		Object value;
		try {
			value = switch (attribute) {
				case CHOICE -> entry((EnumerationType) type, Long.parseLong(encoded.trim()));
				case NUMBER -> withinRange(type, Long.parseLong(encoded.trim()));
				case DECIMAL -> withinRange((FloatingPointType) type, Double.parseDouble(encoded.trim()));
				default -> encoded;
			};
		} catch (NumberFormatException ex) {
			value = null;
		}
		if (value == null) {
			holder.tUpdateByName(TEXT, encoded);
			return I18NConstants.ERROR_VALUE_DOES_NOT_FIT_TYPE__VALUE_TYPE.fill(encoded, type.getName());
		}
		holder.tUpdateByName(attribute, value);
		return null;
	}

	private static EnumerationValue entry(EnumerationType type, long value) {
		for (EnumerationValue entry : type.getValues()) {
			if (entry.getValue() == value) {
				return entry;
			}
		}
		return null;
	}

	private static Long withinRange(ParameterType type, long value) {
		long min;
		long max;
		if (type instanceof NumericType numeric) {
			min = numeric.getMin();
			max = numeric.getMax();
		} else {
			TimeType time = (TimeType) type;
			min = time.getMin();
			max = time.getMax();
		}
		return value < min || value > max ? null : Long.valueOf(value);
	}

	private static Double withinRange(FloatingPointType type, double value) {
		if (Double.isNaN(value) || value < type.getMin() || value > type.getMax()) {
			return null;
		}
		return Double.valueOf(value);
	}

}
