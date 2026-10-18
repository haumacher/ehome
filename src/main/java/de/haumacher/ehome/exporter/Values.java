/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.etsImport.importer.format.Base64Format;
import de.haumacher.ehome.etsImport.importer.format.DecimalsFormat;
import de.haumacher.ehome.etsImport.importer.format.HexBinaryFormat;
import de.haumacher.ehome.etsImport.importer.format.MediumFormat;
import de.haumacher.ehome.etsImport.importer.format.ObjectSizeFormat;
import de.haumacher.ehome.knx.GA;

/**
 * The values of the model in the notation of an ETS project file: the inverse of the formats the
 * import (<code>etsImporter.xml</code>) reads them with.
 */
final class Values {

	private static final ObjectSizeFormat OBJECT_SIZE = new ObjectSizeFormat();

	private static final MediumFormat MEDIUM = new MediumFormat();

	private static final DecimalsFormat DECIMALS = new DecimalsFormat();

	private static final Base64Format BASE64 = new Base64Format();

	private static final HexBinaryFormat HEX = new HexBinaryFormat();

	/**
	 * Classifiers whose name differs from the ETS value, because the ETS value is no identifier
	 * (<code>TLEnumerationFormat</code> reads it without its other characters).
	 */
	private static final Map<String, String> ETS_NAMES = Map.of(
		"DPT9", "DPT 9",
		"IEEE754Single", "IEEE-754 Single",
		"IEEE754Double", "IEEE-754 Double",
		"fullpar", "full,par",
		"fullgrp", "full,grp",
		"pargrp", "par,grp");

	private static final Pattern NUMBER_PARTS = Pattern.compile("(\\d+)|(\\D+)");

	private Values() {
		// Static helpers.
	}

	/**
	 * The attribute of the given object, by name.
	 */
	static Object get(TLObject obj, String attribute) {
		return obj.tValueByName(attribute);
	}

	/**
	 * A reference of the given object, by name; <code>null</code> if empty.
	 */
	static TLObject ref(TLObject obj, String attribute) {
		return (TLObject) obj.tValueByName(attribute);
	}

	/**
	 * A multiple reference of the given object, by name, in its order.
	 */
	static List<TLObject> list(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				result.add((TLObject) element);
			}
		}
		return result;
	}

	/**
	 * A multiple reference whose order the model does not keep, sorted by the external IDs of its
	 * elements (numbers within the IDs by their value).
	 */
	static List<TLObject> sorted(TLObject obj, String attribute) {
		return sortedById(list(obj, attribute));
	}

	/**
	 * The given objects sorted by their external IDs.
	 */
	static List<TLObject> sortedById(Collection<? extends TLObject> objects) {
		// Each ID read once, not once per comparison: reading an attribute is expensive.
		List<Map.Entry<String, TLObject>> keyed = new ArrayList<>(objects.size());
		for (TLObject obj : objects) {
			keyed.add(new AbstractMap.SimpleImmutableEntry<>(extId(obj), obj));
		}
		keyed.sort(Map.Entry.comparingByKey(Values::compareIds));
		List<TLObject> result = new ArrayList<>(keyed.size());
		for (Map.Entry<String, TLObject> entry : keyed) {
			result.add(entry.getValue());
		}
		return result;
	}

	/**
	 * Compares IDs with the numbers within them by their value.
	 */
	static int compareIds(String left, String right) {
		if (left == null || right == null) {
			return left == null ? (right == null ? 0 : -1) : 1;
		}
		Matcher l = NUMBER_PARTS.matcher(left);
		Matcher r = NUMBER_PARTS.matcher(right);
		while (true) {
			boolean hasLeft = l.find();
			boolean hasRight = r.find();
			if (!hasLeft || !hasRight) {
				return Boolean.compare(hasLeft, hasRight);
			}
			int result;
			if (l.group(1) != null && r.group(1) != null) {
				result = Long.compare(Long.parseLong(l.group(1)), Long.parseLong(r.group(1)));
			} else {
				result = l.group().compareTo(r.group());
			}
			if (result != 0) {
				return result;
			}
		}
	}

	/**
	 * The external ID (ETS <code>Id</code>) of the given object; <code>null</code> for none.
	 */
	static String extId(TLObject obj) {
		return obj == null ? null : (String) obj.tValueByName("extId");
	}

	/**
	 * The external IDs of the given objects, separated by spaces (an XML Schema list); <code>null</code>
	 * for none.
	 */
	static String extIds(List<TLObject> objects) {
		if (objects.isEmpty()) {
			return null;
		}
		return objects.stream().map(Values::extId).collect(Collectors.joining(" "));
	}

	/**
	 * A string attribute, <code>null</code> if empty.
	 */
	static String string(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value == null ? null : value.toString();
	}

	/**
	 * A number attribute in decimal notation.
	 */
	static String number(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value == null ? null : value.toString();
	}

	/**
	 * A floating point number as an <code>xs:double</code>.
	 */
	static String decimal(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		if (value == null) {
			return null;
		}
		double number = ((Number) value).doubleValue();
		if (number == Math.rint(number) && Math.abs(number) < 1e15) {
			return Long.toString((long) number);
		}
		return Double.toString(number);
	}

	/**
	 * A boolean as ETS writes it in project and manufacturer data: <code>1</code> or <code>0</code>.
	 */
	static String flag(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value == null ? null : (Boolean.TRUE.equals(value) ? "1" : "0");
	}

	/**
	 * A boolean as ETS writes it in load procedures, master data and the dynamic part:
	 * <code>true</code> or <code>false</code>.
	 */
	static String bool(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value == null ? null : value.toString();
	}

	/**
	 * A boolean whose schema default is <code>false</code>, written only if it is <code>true</code>
	 * (the model reads a missing value as <code>false</code>).
	 *
	 * @param notation
	 *        How ETS writes <code>true</code> there: <code>1</code> or <code>true</code>.
	 */
	static String trueOnly(TLObject obj, String attribute, String notation) {
		return Boolean.TRUE.equals(obj.tValueByName(attribute)) ? notation : null;
	}

	/**
	 * A classifier of an enumeration by its ETS value.
	 */
	static String classifier(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		if (value == null) {
			return null;
		}
		String name = ((TLClassifier) value).getName();
		return ETS_NAMES.getOrDefault(name, name);
	}

	/**
	 * A set of classifiers as a list of names separated by spaces, in the order of the enumeration.
	 */
	static String classifiers(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
			return null;
		}
		return collection.stream()
			.map(TLClassifier.class::cast)
			.sorted(Comparator.comparingInt(TLClassifier::getIndex))
			.map(TLClassifier::getName)
			.collect(Collectors.joining(" "));
	}

	/**
	 * The size of a communication object (bits) as ETS writes it: <code>1 Bit</code>,
	 * <code>2 Bytes</code>.
	 */
	static String objectSize(TLObject obj, String attribute) {
		Integer value = (Integer) obj.tValueByName(attribute);
		return value == null ? null : OBJECT_SIZE.getSpecification(value);
	}

	/**
	 * A medium as the ID of the ETS medium type (<code>MT-0</code>).
	 */
	static String medium(TLObject obj, String attribute) {
		TLClassifier value = (TLClassifier) obj.tValueByName(attribute);
		return value == null ? null : MEDIUM.getSpecification(value);
	}

	/**
	 * A number of decimal places as an ETS display format (<code>0.00</code>).
	 */
	static String decimals(TLObject obj, String attribute) {
		Integer value = (Integer) obj.tValueByName(attribute);
		return value == null ? null : DECIMALS.getSpecification(value);
	}

	/**
	 * Binary data in base 64.
	 */
	static String base64(TLObject obj, String attribute) {
		BinaryData value = (BinaryData) obj.tValueByName(attribute);
		return value == null ? null : BASE64.getSpecification(value);
	}

	/**
	 * Binary data in hexadecimal digits.
	 */
	static String hex(TLObject obj, String attribute) {
		BinaryData value = (BinaryData) obj.tValueByName(attribute);
		return value == null ? null : HEX.getSpecification(value);
	}

	/**
	 * A point in time as ETS writes it, without time zone; the import reads it as UTC.
	 */
	static String date(TLObject obj, String attribute) {
		Date value = (Date) obj.tValueByName(attribute);
		if (value == null) {
			return null;
		}
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(value);
	}

	/**
	 * A group address (or the start or end of a group range) as its number.
	 */
	static String groupAddress(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		if (value instanceof GA ga) {
			return Integer.toString(ga.getValue());
		}
		return value == null ? null : value.toString();
	}

}
