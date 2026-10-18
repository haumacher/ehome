/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * The translatable texts of one exported file: the text of the file's default language for the
 * attribute of an element, and the texts of the other application languages for its
 * <code>Languages</code> section.
 *
 * <p>
 * The model holds a text per application language (<code>I18NString</code>), not the language a
 * manufacturer wrote first; so every file gets a fixed default language, and a translation is
 * written for each other application language whose text differs from the default one.
 * </p>
 *
 * <p>
 * An application program records the languages of its texts (<code>Program#languages</code>, see
 * <code>ProgramLanguages</code>); in another language, its text is a stand-in, the text of the base
 * language of the file it was imported from. Its file is written with translations of its own
 * languages only; if it lacks the application's fallback language, the attribute holds the stand-in
 * text of that language, which is the base text of the original file.
 * </p>
 */
final class Texts {

	/**
	 * The ETS language identifier of each application language.
	 */
	private static final Map<String, String> IDENTIFIERS = Map.of("de", "de-DE", "en", "en-US");

	private final String _defaultLanguage;

	/**
	 * The languages the texts are written in, <code>null</code> for all application languages.
	 */
	private final Set<String> _languages;

	/** Language identifier to translation unit to element ID to attribute name and text pairs. */
	private final Map<String, Map<String, Map<String, List<String[]>>>> _translations = new LinkedHashMap<>();

	private String _unit;

	/**
	 * Creates a {@link Texts} for a file whose texts are written in the given language
	 * (<code>de</code> or <code>en</code>).
	 */
	Texts(String defaultLanguage) {
		this(defaultLanguage, null);
	}

	/**
	 * Creates a {@link Texts} for a file whose texts are written in the given language, with
	 * translations into the given languages only.
	 *
	 * @param languages
	 *        The languages of the texts (of an application program), <code>null</code> or empty for
	 *        all application languages.
	 */
	Texts(String defaultLanguage, Set<String> languages) {
		_defaultLanguage = defaultLanguage;
		_languages = languages == null || languages.isEmpty() ? null : languages;
	}

	/**
	 * The ETS identifier of the default language of the file (<code>de-DE</code>).
	 */
	String defaultLanguage() {
		return IDENTIFIERS.get(_defaultLanguage);
	}

	/**
	 * Sets the translation unit the following texts are filed under (an ETS translation unit is
	 * named by the ID of the element it translates, a catalog section, a product or a program).
	 */
	void unit(String refId) {
		_unit = refId;
	}

	/**
	 * The text of the default language of an internationalized attribute
	 * (<code>tl.model.i18n:I18NString</code>), recording the texts of the other languages as
	 * translations of the XML attribute of the given element.
	 *
	 * @return The text to write as the attribute value; <code>null</code> if the attribute has no
	 *         text in any language.
	 */
	String text(TLObject obj, String attribute, String xmlAttribute) {
		Map<String, String> texts = texts(obj.tValueByName(attribute));
		if (texts.isEmpty()) {
			return null;
		}
		String text = baseText(texts);
		String id = Values.extId(obj);
		for (Map.Entry<String, String> entry : texts.entrySet()) {
			String identifier = IDENTIFIERS.get(entry.getKey());
			if (identifier == null || entry.getValue().equals(text) || id == null
				|| (_languages != null && !_languages.contains(entry.getKey()))) {
				continue;
			}
			_translations.computeIfAbsent(identifier, x -> new LinkedHashMap<>())
				.computeIfAbsent(_unit == null ? id : _unit, x -> new LinkedHashMap<>())
				.computeIfAbsent(id, x -> new ArrayList<>())
				.add(new String[] { xmlAttribute, entry.getValue() });
		}
		return text;
	}

	/**
	 * The text of the default language of an internationalized attribute, for an XML attribute that
	 * has no translations.
	 */
	String untranslated(TLObject obj, String attribute) {
		Map<String, String> texts = texts(obj.tValueByName(attribute));
		if (texts.isEmpty()) {
			return null;
		}
		return baseText(texts);
	}

	/**
	 * The text of an attribute: the one of the default language; of a program lacking the
	 * application's fallback language, the stand-in in that language (the base text of the original
	 * file); else the first.
	 */
	private String baseText(Map<String, String> texts) {
		String text = null;
		if (_languages != null) {
			String fallback = ResourcesModule.getInstance().getFallbackLocale().getLanguage();
			if (!_languages.contains(fallback)) {
				text = texts.get(fallback);
			}
		}
		if (text == null) {
			text = texts.get(_defaultLanguage);
		}
		return text != null ? text : texts.values().iterator().next();
	}

	/**
	 * The texts of an internationalized value per language (the language part of the locale only).
	 */
	private static Map<String, String> texts(Object value) {
		Map<String, String> result = new LinkedHashMap<>();
		if (value instanceof LiteralKey literal) {
			for (Map.Entry<Locale, String> entry : literal.getTranslations().entrySet()) {
				if (entry.getValue() != null && !entry.getValue().isEmpty()) {
					result.putIfAbsent(entry.getKey().getLanguage(), entry.getValue());
				}
			}
		} else if (value instanceof ResKey key) {
			for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
				String text = Resources.getInstance(locale).getString(key, null);
				if (text != null && !text.isEmpty()) {
					result.putIfAbsent(locale.getLanguage(), text);
				}
			}
		} else if (value instanceof String text && !text.isEmpty()) {
			result.put(ResourcesModule.getInstance().getFallbackLocale().getLanguage(), text);
		}
		return result;
	}

	/**
	 * Whether texts of other languages were recorded.
	 */
	boolean hasTranslations() {
		return !_translations.isEmpty();
	}

	/**
	 * Writes the <code>Languages</code> section with the recorded translations.
	 */
	void writeLanguages(XmlOut out) throws XMLStreamException {
		if (_translations.isEmpty()) {
			return;
		}
		out.open("Languages");
		for (Map.Entry<String, Map<String, Map<String, List<String[]>>>> language : _translations.entrySet()) {
			out.open("Language").attr("Identifier", language.getKey());
			for (Map.Entry<String, Map<String, List<String[]>>> unit : language.getValue().entrySet()) {
				out.open("TranslationUnit").attr("RefId", unit.getKey()).attr("Version", "0");
				for (Map.Entry<String, List<String[]>> element : unit.getValue().entrySet()) {
					out.open("TranslationElement").attr("RefId", element.getKey());
					for (String[] translation : element.getValue()) {
						out.open("Translation").attr("AttributeName", translation[0]).attr("Text", translation[1]).close();
					}
					out.close();
				}
				out.close();
			}
			out.close();
		}
		out.close();
	}

}
