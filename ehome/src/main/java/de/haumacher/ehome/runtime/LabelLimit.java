/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.rules.RuleSet;

/**
 * The limit of the labels the controller shows (haumacher/ehome #116, homecontrol
 * <code>spec/rules.md</code> 2.2): each text of a label has at most {@value #MAX_BYTES} bytes in
 * UTF-8, an umlaut counts twice.
 *
 * <p>
 * The labels with this limit are the name of a rule, the titles of its parameters and of their
 * choices, the label of an application datapoint and the label of a schedule entry. A text above
 * the limit is an error of the input ({@link #problem(Object, Object)}, the TL-Script function
 * <code>runtimeLabelProblem(value, obj)</code>), and a label stored before is named by its object
 * where the image is refused ({@link #problem(TLObject, String)}): the message names the object,
 * the language and the length.
 * </p>
 */
public final class LabelLimit {

	/** The most bytes of a text of a label. */
	public static final int MAX_BYTES = RuleSet.MAX_LABEL;

	private LabelLimit() {
		// Static only.
	}

	/**
	 * The attribute of the given object that holds a label with this limit: <code>name</code> of a
	 * rule, <code>title</code> of a parameter or choice, <code>label</code> of an application
	 * datapoint or schedule entry; <code>null</code> for another object.
	 */
	public static String attribute(TLObject obj) {
		if (obj == null || !(obj.tType() instanceof TLStructuredType type)
			|| !"ehome.runtime".equals(type.getModule().getName())) {
			return null;
		}
		switch (type.getName()) {
			case "Rule":
				return "name";
			case "RuleParameter":
			case "RuleChoice":
				return "title";
			case "ApplicationDatapoint":
				return "label";
			default:
				return TLModelUtil.isGeneralization((TLStructuredType) TLModelUtil.findType("ehome.runtime:ScheduleEntry"), type)
					? "label" : null;
		}
	}

	/**
	 * Why the label entered for the given object is too long, an error of its input: the message
	 * naming the object, the language and the length; <code>null</code> if every text of it has at
	 * most {@value #MAX_BYTES} bytes.
	 *
	 * @param value
	 *        The label entered (an internationalized text, or a plain one).
	 * @param obj
	 *        The object, in a form the form's object (in the create dialog a draft in its container).
	 */
	public static ResKey problem(Object value, Object obj) {
		return obj instanceof TLObject form ? problem(form, value, null) : null;
	}

	/**
	 * Why the stored label of the given object is too long: as {@link #problem(Object, Object)}, the
	 * text of the given language first.
	 *
	 * @param language
	 *        The language tag of the text the image refused, <code>null</code> for the first text that
	 *        is too long.
	 */
	public static ResKey problem(TLObject obj, String language) {
		String attribute = attribute(obj);
		return attribute == null ? null : problem(obj, obj.tValueByName(attribute), language);
	}

	/**
	 * @param form
	 *        The object whose values are read (in a form the form's object, with the values entered).
	 */
	private static ResKey problem(TLObject form, Object value, String language) {
		if (attribute(form) == null) {
			return null;
		}
		Map<Locale, String> texts = texts(value);
		Locale tooLong = null;
		for (Map.Entry<Locale, String> entry : texts.entrySet()) {
			if (bytes(entry.getValue()) > MAX_BYTES
				&& (tooLong == null || entry.getKey().getLanguage().equalsIgnoreCase(language))) {
				tooLong = entry.getKey();
			}
		}
		if (tooLong == null) {
			return null;
		}
		Integer bytes = Integer.valueOf(bytes(texts.get(tooLong)));
		String lang = tooLong.getLanguage();
		String other = tooLong.getDisplayLanguage(Resources.getCurrentLocale());
		TLObject self = self(form);
		TLObject container = valid(self) ? self.tContainer() : null;
		switch (form.tType().getName()) {
			case "Rule": {
				String rule = name(value, form, "identifier");
				return switch (lang) {
					case "de" -> I18NConstants.LABEL_TOO_LONG_RULE_DE__RULE_BYTES.fill(rule, bytes);
					case "en" -> I18NConstants.LABEL_TOO_LONG_RULE_EN__RULE_BYTES.fill(rule, bytes);
					default -> I18NConstants.LABEL_TOO_LONG_RULE__RULE_BYTES_LANGUAGE.fill(rule, bytes, other);
				};
			}
			case "RuleParameter": {
				String parameter = name(value, form, "identifier");
				String rule = ruleName(container);
				return switch (lang) {
					case "de" -> I18NConstants.LABEL_TOO_LONG_PARAMETER_DE__PARAMETER_RULE_BYTES.fill(parameter, rule, bytes);
					case "en" -> I18NConstants.LABEL_TOO_LONG_PARAMETER_EN__PARAMETER_RULE_BYTES.fill(parameter, rule, bytes);
					default -> I18NConstants.LABEL_TOO_LONG_PARAMETER__PARAMETER_RULE_BYTES_LANGUAGE.fill(parameter, rule,
						bytes, other);
				};
			}
			case "RuleChoice": {
				String choice = name(value, form, "value");
				String parameter = valid(container) ? name(container.tValueByName("title"), container, "identifier") : "?";
				String rule = ruleName(valid(container) ? container.tContainer() : null);
				return switch (lang) {
					case "de" -> I18NConstants.LABEL_TOO_LONG_CHOICE_DE__CHOICE_PARAMETER_RULE_BYTES.fill(choice, parameter,
						rule, bytes);
					case "en" -> I18NConstants.LABEL_TOO_LONG_CHOICE_EN__CHOICE_PARAMETER_RULE_BYTES.fill(choice, parameter,
						rule, bytes);
					default -> I18NConstants.LABEL_TOO_LONG_CHOICE__CHOICE_PARAMETER_RULE_BYTES_LANGUAGE.fill(choice,
						parameter, rule, bytes, other);
				};
			}
			case "ApplicationDatapoint": {
				// A datapoint in a display text: Name (id), haumacher/ehome #113.
				String datapoint = DatapointNames.format(text(value), text(form.tValueByName("identifier")));
				datapoint = datapoint == null ? "?" : datapoint;
				return switch (lang) {
					case "de" -> I18NConstants.LABEL_TOO_LONG_DATAPOINT_DE__DATAPOINT_BYTES.fill(datapoint, bytes);
					case "en" -> I18NConstants.LABEL_TOO_LONG_DATAPOINT_EN__DATAPOINT_BYTES.fill(datapoint, bytes);
					default -> I18NConstants.LABEL_TOO_LONG_DATAPOINT__DATAPOINT_BYTES_LANGUAGE.fill(datapoint, bytes, other);
				};
			}
			default: {
				String entry = name(value, form, "identifier");
				return switch (lang) {
					case "de" -> I18NConstants.LABEL_TOO_LONG_SCHEDULE_DE__ENTRY_BYTES.fill(entry, bytes);
					case "en" -> I18NConstants.LABEL_TOO_LONG_SCHEDULE_EN__ENTRY_BYTES.fill(entry, bytes);
					default -> I18NConstants.LABEL_TOO_LONG_SCHEDULE__ENTRY_BYTES_LANGUAGE.fill(entry, bytes, other);
				};
			}
		}
	}

	/**
	 * The texts of a label in the languages of the application, in their order; a plain text in the
	 * first of them.
	 */
	private static Map<Locale, String> texts(Object value) {
		Map<Locale, String> result = new LinkedHashMap<>();
		if (value == null) {
			return result;
		}
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			String text;
			if (value instanceof LiteralKey literal) {
				Map<Locale, String> translations = literal.getTranslations();
				text = translations.get(locale);
				if (text == null) {
					text = translations.get(new Locale(locale.getLanguage()));
				}
			} else if (value instanceof ResKey key) {
				text = Resources.getInstance(locale).getString(key, null);
			} else {
				text = value.toString();
			}
			if (text != null && !text.isBlank()) {
				result.putIfAbsent(new Locale(locale.getLanguage()), text);
			}
			if (!(value instanceof ResKey)) {
				break;
			}
		}
		return result;
	}

	/**
	 * The given label with each text shortened to at most {@value #MAX_BYTES} bytes: for a label
	 * E-Home derives itself (from the name of a Zigbee device and its function), which the input check
	 * would otherwise refuse.
	 */
	public static ResKey fit(ResKey label) {
		if (!(label instanceof LiteralKey literal)) {
			return label;
		}
		ResKey.Builder builder = ResKey.builder();
		for (Map.Entry<Locale, String> entry : literal.getTranslations().entrySet()) {
			builder.add(entry.getKey(), RuntimeImage.truncate(entry.getValue(), MAX_BYTES));
		}
		return builder.build();
	}

	/** The number of bytes of the text in UTF-8, as the controller counts them. */
	public static int bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

	/**
	 * The name of an object in a message: its label in the language of the reader, else the value of
	 * the given attribute.
	 */
	private static String name(Object label, TLObject obj, String fallback) {
		String text = text(label);
		if (text == null) {
			text = text(obj.tValueByName(fallback));
		}
		return text == null ? "?" : text;
	}

	private static String ruleName(TLObject rule) {
		return valid(rule) ? name(rule.tValueByName("name"), rule, "identifier") : "?";
	}

	private static String text(Object value) {
		if (value instanceof ResKey key) {
			String text = Resources.getInstance().getString(key, null);
			return text == null || text.isBlank() ? null : text.trim();
		}
		return value == null || value.toString().isBlank() ? null : value.toString().trim();
	}

	/**
	 * The object edited by the given one: the stored object of an edit form, a draft in its container
	 * (the create dialog) or the object itself.
	 */
	private static TLObject self(TLObject obj) {
		return obj instanceof TLFormObjectBase form && form.getEditedObject() != null ? form.getEditedObject() : obj;
	}

	private static boolean valid(TLObject obj) {
		return obj != null && obj.tValid();
	}

}
