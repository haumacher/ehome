/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Type;

/**
 * The canonical form of the texts of the rule language a part of a rule or a schedule entry holds
 * (haumacher/ehome #60): the form {@link RuleImport} stores and {@link RuleExport} writes, so that what
 * is stored after the user entered a text is what the image carries (<code>3 min</code> becomes
 * <code>3min</code>, <code>@"1/2/3"=true</code> becomes <code>@"1/2/3" = true</code>).
 *
 * <p>
 * Applied to every entered value by {@link RuleTextNormalizer}, the value normalizer of the rule texts
 * in the model. A text that does not parse is kept as it was typed: its field shows the syntax error.
 * </p>
 */
public final class RuleNormalization {

	/** The time-of-day fields of the schedule entries. */
	private static final Set<String> TIMES = Set.of("time", "notBefore", "notAfter", "from", "until");

	/** The duration fields of the schedule entries. */
	private static final Set<String> DURATIONS = Set.of("every", "offset");

	private RuleNormalization() {
		// Static utility.
	}

	/**
	 * The canonical form of the given text of the given attribute of the given object.
	 *
	 * @param obj
	 *        The part of a rule or the schedule entry, or the overlay of a form editing it; its other
	 *        values decide the type of a literal (the type of a parameter).
	 * @return The canonical text, or the text itself when it is empty, does not parse, or is no text of
	 *         the rule language.
	 */
	public static String canonical(TLObject obj, String attribute, String text) {
		if (obj == null || text == null || text.isBlank()
			|| !"ehome.runtime".equals(obj.tType().getModule().getName())) {
			return text;
		}
		try {
			switch (obj.tType().getName()) {
				case "RuleParameter": {
					Type type = parameterType(obj);
					if (type == null) {
						return text;
					}
					switch (attribute) {
						case "default":
						case "min":
						case "max":
							return literal(text, type);
						case "step":
							return step(text, type);
						default:
							return text;
					}
				}
				case "RuleChoice": {
					Type type = obj.tContainer() == null ? null : parameterType(obj.tContainer());
					return type != null && "value".equals(attribute) ? literal(text, type) : text;
				}
				case "RuleTimer":
				case "StartTimerAction":
				case "RestartTimerAction":
					return "duration".equals(attribute) ? expression(text) : text;
				case "Reaction":
				case "MatchTrigger":
					return "condition".equals(attribute) ? expression(text) : text;
				case "SilenceTrigger":
					return "after".equals(attribute) ? expression(text) : text;
				case "WriteAction":
				case "SetParameterAction":
					return "value".equals(attribute) ? expression(text) : text;
				case "LogAction":
					return "message".equals(attribute) ? RuleText.printMessage(RuleText.parseMessage(text)) : text;
				default:
					return obj.tType().getName().endsWith("Schedule") ? schedule(attribute, text) : text;
			}
		} catch (SyntaxError ex) {
			// Kept as typed, the field shows the error.
			return text;
		}
	}

	private static String schedule(String attribute, String text) throws SyntaxError {
		if (TIMES.contains(attribute)) {
			return literal(text, Type.TIME_OF_DAY);
		}
		if (DURATIONS.contains(attribute)) {
			return literal(text, Type.DURATION);
		}
		switch (attribute) {
			case "at":
				return literal(text, Type.DATE_TIME);
			case "dates": {
				List<String> dates = new ArrayList<>();
				for (String part : text.split("[,;\\s]")) {
					if (!part.isEmpty()) {
						dates.add(literal(part, Type.DATE));
					}
				}
				return String.join(", ", dates);
			}
			default:
				return text;
		}
	}

	private static Type parameterType(TLObject parameter) {
		return parameter.tValueByName("type") instanceof TLClassifier type ? Type.fromName(type.getName()) : null;
	}

	private static String expression(String text) throws SyntaxError {
		return RuleText.print(RuleText.parse(text));
	}

	private static String literal(String text, Type type) throws SyntaxError {
		return RuleText.printValue(RuleText.parseValue(text, type), type);
	}

	/** The step of a parameter: a duration for a duration parameter, else a number. */
	private static String step(String text, Type type) throws SyntaxError {
		if (type == Type.DURATION) {
			return literal(text, Type.DURATION);
		}
		Map<String, Object> node = RuleText.parse(text);
		if ("literal".equals(node.get("kind")) && node.get("value") instanceof Number number) {
			return RuleText.printValue(number, number instanceof Double ? Type.REAL : Type.INTEGER);
		}
		return text;
	}

}
