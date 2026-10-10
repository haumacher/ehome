/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLObject;

/**
 * Builds the values of internationalized attributes (<code>tl.model.i18n:I18NString</code>) from the
 * texts of an ETS project, one language at a time.
 */
public class I18NTexts {

	/**
	 * Name of the import variable holding the {@link Locale} of the texts of the current element (its
	 * <code>DefaultLanguage</code>).
	 */
	public static final String LANGUAGE_VAR = "language";

	/**
	 * The locale of an ETS language identifier such as <code>de-DE</code>: its language only, since
	 * the application offers languages, not regions.
	 */
	public static Locale locale(String identifier) {
		return new Locale(Locale.forLanguageTag(identifier).getLanguage());
	}

	/**
	 * Sets the text of the given language in an internationalized attribute, keeping the texts of
	 * the other languages.
	 *
	 * @param fillFallback
	 *        Whether to use the text for the application's fallback language as well, if that has no
	 *        text yet. A text read from an element's own attribute is the best available text for
	 *        every language until a translation replaces it.
	 */
	public static void set(TLObject obj, String attribute, Locale locale, String text, boolean fillFallback) {
		if (text == null || text.isEmpty()) {
			return;
		}
		ResKey.Builder builder = ResKey.builder();
		Map<Locale, String> existing = translations((ResKey) obj.tValueByName(attribute));
		for (Entry<Locale, String> entry : existing.entrySet()) {
			if (!entry.getKey().getLanguage().equals(locale.getLanguage())) {
				builder.add(entry.getKey(), entry.getValue());
			}
		}
		builder.add(locale, text);
		Locale fallback = ResourcesModule.getInstance().getFallbackLocale();
		if (fillFallback && !hasLanguage(existing, fallback) && !locale.getLanguage().equals(fallback.getLanguage())) {
			builder.add(new Locale(fallback.getLanguage()), text);
		}
		obj.tUpdateByName(attribute, builder.build());
	}

	private static Map<Locale, String> translations(ResKey value) {
		if (value instanceof LiteralKey literal) {
			return literal.getTranslations();
		}
		return Map.of();
	}

	private static boolean hasLanguage(Map<Locale, String> translations, Locale locale) {
		return translations.keySet().stream().anyMatch(l -> l.getLanguage().equals(locale.getLanguage()));
	}

}
