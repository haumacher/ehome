/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;

/**
 * The named values of an enumerated datapoint subtype as the imported master data define them
 * (<code>ehome.datapoint:DatapointSubtype#values</code>), with their texts in a language.
 */
public class NamedValues {

	private static final String SUBTYPE = "ehome.datapoint:DatapointSubtype";

	private static final String EXT_ID = "extId";

	private static final String VALUES = "values";

	private static final String VALUE = "value";

	private static final String TEXT = "text";

	/**
	 * A named value: its number and its text.
	 */
	public record Named(int value, String text) {
		// Pure data.
	}

	/**
	 * The named values of the given subtype, ordered by their numbers, each with its text in the
	 * given language, else in English, else the text of the fallback; empty if the master data name no
	 * values of the subtype (or it is not imported).
	 *
	 * @param fallback
	 *        The text of a value without a text in the language or in English, by its number.
	 */
	public static List<Named> of(Dpt dpt, Locale locale, IntFunction<String> fallback) {
		TLObject subtype = subtype(dpt);
		if (subtype == null) {
			return List.of();
		}
		return ((Collection<?>) subtype.tValueByName(VALUES)).stream()
			.map(TLObject.class::cast)
			.filter(v -> v.tValueByName(VALUE) instanceof Number)
			.map(v -> {
				int number = ((Number) v.tValueByName(VALUE)).intValue();
				return new Named(number, text((ResKey) v.tValueByName(TEXT), locale, () -> fallback.apply(number)));
			})
			.sorted(Comparator.comparingInt(Named::value))
			.toList();
	}

	/**
	 * The number of the value of the given subtype whose text in any language equals the given text
	 * (ignoring case), <code>null</code> if none does.
	 */
	public static Integer valueOf(Dpt dpt, String text) {
		TLObject subtype = subtype(dpt);
		if (subtype == null) {
			return null;
		}
		String wanted = text.trim();
		for (Object element : (Collection<?>) subtype.tValueByName(VALUES)) {
			TLObject value = (TLObject) element;
			if (value.tValueByName(VALUE) instanceof Number number
				&& translations((ResKey) value.tValueByName(TEXT)).values().stream()
					.anyMatch(t -> t.trim().equalsIgnoreCase(wanted))) {
				return number.intValue();
			}
		}
		return null;
	}

	/**
	 * Whether the master data name values of the given subtype.
	 */
	public static boolean has(Dpt dpt) {
		TLObject subtype = subtype(dpt);
		return subtype != null && !((Collection<?>) subtype.tValueByName(VALUES)).isEmpty();
	}

	/**
	 * The size in bits of the values of the given subtype, <code>null</code> if not known.
	 */
	public static Integer sizeInBit(Dpt dpt) {
		TLObject subtype = subtype(dpt);
		if (subtype == null || subtype.tContainer() == null) {
			return null;
		}
		Object size = subtype.tContainer().tValueByName("sizeInBit");
		return size instanceof Number number ? Integer.valueOf(number.intValue()) : null;
	}

	private static TLObject subtype(Dpt dpt) {
		if (dpt == null || dpt.isMainType()) {
			return null;
		}
		String id = "DPST-" + dpt.main() + "-" + dpt.sub();
		TLClass type = (TLClass) TLModelUtil.findType(SUBTYPE);
		for (TLObject subtype : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			if (id.equals(subtype.tValueByName(EXT_ID))) {
				return subtype;
			}
		}
		return null;
	}

	private static String text(ResKey key, Locale locale, Supplier<String> fallback) {
		Map<Locale, String> translations = translations(key);
		for (Locale candidate : List.of(locale, Locale.ENGLISH)) {
			for (Map.Entry<Locale, String> entry : translations.entrySet()) {
				if (entry.getKey().getLanguage().equals(candidate.getLanguage()) && !entry.getValue().isBlank()) {
					return entry.getValue();
				}
			}
		}
		return fallback.get();
	}

	private static Map<Locale, String> translations(ResKey key) {
		if (key instanceof LiteralKey literal) {
			return literal.getTranslations();
		}
		return Map.of();
	}

}
