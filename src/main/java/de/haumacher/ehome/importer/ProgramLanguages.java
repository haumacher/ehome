/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;

/**
 * The languages of the texts of the application programs an import reads (haumacher/ehome #38).
 *
 * <p>
 * A program records the languages of its texts (<code>ehome.operational:Program#languages</code>):
 * the base language of the file it is imported from (<code>DefaultLanguage</code>) and the languages
 * of the file's translations. In another language, a text of the program is the one of the base
 * language (see {@link I18NTexts#set(TLObject, String, Locale, String, boolean)}), so the texts alone
 * do not tell which languages a program has.
 * </p>
 *
 * <p>
 * A known program is skipped (see {@link UnlessKnown}), since it never changes under its ID; but ETS
 * writes a program in the language of the installation that exports it, so another file may bring
 * the same program in another language. The import adds the texts of the languages the stored
 * program lacks: from the attributes of the program's elements if the file's base language is one of
 * them (see {@link TranslationsImport#importBaseTexts(ImportContext, javax.xml.stream.XMLStreamReader, Entry, Locale)}),
 * and from the file's translations. The texts of a language the program has stay as they are.
 * </p>
 */
public class ProgramLanguages {

	/**
	 * The {@link ProgramLanguages} of an import in its {@link ImportContext}.
	 */
	public static final Property<ProgramLanguages> PROPERTY =
		TypedAnnotatable.property(ProgramLanguages.class, "programLanguages");

	/**
	 * The attribute of a program holding the languages of its texts.
	 */
	public static final String LANGUAGES = "languages";

	/**
	 * A program of the import with the languages of its texts.
	 */
	public static final class Entry {

		private final TLObject _program;

		/**
		 * The languages the program had before the import; <code>null</code> for a program the
		 * import creates.
		 */
		private final Set<String> _before;

		private final Set<Locale> _added;

		Entry(TLObject program, Set<String> before, Set<Locale> added) {
			_program = program;
			_before = before;
			_added = added;
		}

		/**
		 * Whether the import sets the texts of the given language: every language of a new program,
		 * the application languages a known program did not have before the import.
		 *
		 * <p>
		 * A program that records no languages (created in the application) counts as having all.
		 * </p>
		 */
		public boolean lacks(Locale locale) {
			if (_before == null) {
				return true;
			}
			return isApplicationLanguage(locale) && !_before.isEmpty() && !_before.contains(locale.getLanguage());
		}

		/**
		 * Sets a text of the program in a language it {@link #lacks(Locale) lacks}, recording the
		 * language.
		 */
		public void set(TLObject target, String attribute, Locale locale, String text) {
			if (text == null || text.isEmpty()) {
				return;
			}
			I18NTexts.set(target, attribute, locale, text, false);
			addLanguage(locale);
		}

		void addLanguage(Locale locale) {
			if (!isApplicationLanguage(locale)) {
				return;
			}
			Set<String> languages = languages(_program);
			if (languages.add(locale.getLanguage())) {
				_program.tUpdateByName(LANGUAGES, String.join(" ", languages));
				if (_before != null) {
					_added.add(new Locale(locale.getLanguage()));
				}
			}
		}

	}

	/** The programs of the import, by their ID (which names the translation unit of their texts). */
	private final Map<String, Entry> _programs = new HashMap<>();

	/** The languages added per known program. */
	private final Map<TLObject, Set<Locale>> _added = new LinkedHashMap<>();

	/**
	 * The {@link ProgramLanguages} of the given import, <code>null</code> if it records none.
	 */
	public static ProgramLanguages get(ImportContext context) {
		return context.get(PROPERTY);
	}

	/**
	 * Records a program the import creates, written in the given base language.
	 */
	public Entry created(String id, TLObject program, Locale base) {
		Entry result = new Entry(program, null, null);
		_programs.put(id, result);
		result.addLanguage(base);
		return result;
	}

	/**
	 * Records a known program the import finds.
	 */
	public Entry known(String id, TLObject program) {
		Entry result =
			new Entry(program, languages(program), _added.computeIfAbsent(program, x -> new LinkedHashSet<>()));
		_programs.put(id, result);
		return result;
	}

	/**
	 * The program of the import with the given ID, <code>null</code> if there is none.
	 */
	public Entry get(String id) {
		return id == null ? null : _programs.get(id);
	}

	/**
	 * The languages the import added to known programs, per program; programs without a new
	 * language are left out.
	 */
	public Map<TLObject, Set<Locale>> getAdded() {
		Map<TLObject, Set<Locale>> result = new LinkedHashMap<>();
		for (Map.Entry<TLObject, Set<Locale>> entry : _added.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				result.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
			}
		}
		return result;
	}

	/**
	 * Whether the given locale is one of the languages the application offers; texts of other
	 * languages are not modeled (they are not exported) and not recorded.
	 */
	static boolean isApplicationLanguage(Locale locale) {
		for (Locale supported : ResourcesModule.getInstance().getSupportedLocales()) {
			if (supported.getLanguage().equals(locale.getLanguage())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The languages a program records, sorted; empty if it records none.
	 */
	public static Set<String> languages(TLObject program) {
		Set<String> result = new TreeSet<>();
		String value = (String) program.tValueByName(LANGUAGES);
		if (value != null) {
			for (String language : value.trim().split("\\s+")) {
				if (!language.isEmpty()) {
					result.add(language);
				}
			}
		}
		return result;
	}

}
