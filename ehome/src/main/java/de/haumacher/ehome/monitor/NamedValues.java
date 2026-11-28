/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

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

import de.haumacher.ehome.model.datapoint.DatapointMainType;
import de.haumacher.ehome.model.datapoint.DatapointSubtype;
import de.haumacher.ehome.model.datapoint.EhomeDatapointFactory;
import de.haumacher.ehome.model.datapoint.NamedValue;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;

/**
 * The named values of an enumerated datapoint subtype as the imported master data define them
 * (<code>ehome.datapoint:DatapointSubtype#values</code>), with their texts in a language.
 */
public class NamedValues {

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
		DatapointSubtype subtype = subtype(dpt);
		if (subtype == null) {
			return List.of();
		}
		return subtype.getValues().stream()
			.map(v -> {
				int number = v.getValue();
				return new Named(number, text(v.getText(), locale, () -> fallback.apply(number)));
			})
			.sorted(Comparator.comparingInt(Named::value))
			.toList();
	}

	/**
	 * The number of the value of the given subtype whose text in any language equals the given text
	 * (ignoring case), <code>null</code> if none does.
	 */
	public static Integer valueOf(Dpt dpt, String text) {
		DatapointSubtype subtype = subtype(dpt);
		if (subtype == null) {
			return null;
		}
		String wanted = text.trim();
		for (NamedValue value : subtype.getValues()) {
			if (translations(value.getText()).values().stream()
				.anyMatch(t -> t.trim().equalsIgnoreCase(wanted))) {
				return value.getValue();
			}
		}
		return null;
	}

	/**
	 * Whether the master data name values of the given subtype.
	 */
	public static boolean has(Dpt dpt) {
		DatapointSubtype subtype = subtype(dpt);
		return subtype != null && !subtype.getValues().isEmpty();
	}

	/**
	 * The size in bits of the values of the given subtype, <code>null</code> if not known.
	 */
	public static Integer sizeInBit(Dpt dpt) {
		DatapointSubtype subtype = subtype(dpt);
		return subtype != null && subtype.tContainer() instanceof DatapointMainType main ? main.getSizeInBit() : null;
	}

	private static DatapointSubtype subtype(Dpt dpt) {
		if (dpt == null || dpt.isMainType()) {
			return null;
		}
		String id = "DPST-" + dpt.main() + "-" + dpt.sub();
		TLClass type = EhomeDatapointFactory.getDatapointSubtypeType();
		for (DatapointSubtype subtype : MetaElementUtil.getAllDirectInstancesOf(type, DatapointSubtype.class)) {
			if (id.equals(subtype.getExtId())) {
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
