/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Type;

/**
 * Stores the texts of the rule language a part of a rule or a schedule entry holds in their canonical
 * form (haumacher/ehome #60): the form {@link RuleImport} stores and {@link RuleExport} writes, so that
 * what is stored after the user saved is what the image carries (<code>3 min</code> becomes
 * <code>3min</code>, <code>@"1/2/3"=true</code> becomes <code>@"1/2/3" = true</code>).
 *
 * <p>
 * Called by the save of the forms of the rule views, in the transaction of the save (the engine has no
 * hook of a field that normalizes its input, TL #29888). A text that does not parse is kept as it was
 * typed: its field shows the syntax error.
 * </p>
 */
public final class RuleNormalization {

	/** The time-of-day fields of the schedule entries. */
	private static final String[] TIMES = { "time", "notBefore", "notAfter", "from", "until" };

	/** The duration fields of the schedule entries. */
	private static final String[] DURATIONS = { "every", "offset" };

	private RuleNormalization() {
		// Static utility.
	}

	/**
	 * Stores the canonical form of every text of the rule language of the object; must run in a
	 * transaction.
	 *
	 * @return The object.
	 */
	public static TLObject normalize(TLObject obj) {
		if (obj == null || !obj.tValid() || !"ehome.runtime".equals(obj.tType().getModule().getName())) {
			return obj;
		}
		switch (obj.tType().getName()) {
			case "RuleParameter": {
				Type type = parameterType(obj);
				if (type != null) {
					for (String attribute : new String[] { "default", "min", "max" }) {
						literal(obj, attribute, type);
					}
					step(obj, type);
				}
				break;
			}
			case "RuleChoice": {
				Type type = obj.tContainer() == null ? null : parameterType(obj.tContainer());
				if (type != null) {
					literal(obj, "value", type);
				}
				break;
			}
			case "RuleTimer":
			case "StartTimerAction":
			case "RestartTimerAction":
				expression(obj, "duration");
				break;
			case "Reaction":
			case "MatchTrigger":
				expression(obj, "condition");
				break;
			case "WriteAction":
			case "SetParameterAction":
				expression(obj, "value");
				break;
			case "LogAction":
				message(obj);
				break;
			default:
				if (obj.tType().getName().endsWith("Schedule")) {
					schedule(obj);
				}
				break;
		}
		return obj;
	}

	private static void schedule(TLObject entry) {
		for (String attribute : TIMES) {
			if (has(entry, attribute)) {
				literal(entry, attribute, Type.TIME_OF_DAY);
			}
		}
		for (String attribute : DURATIONS) {
			if (has(entry, attribute)) {
				literal(entry, attribute, Type.DURATION);
			}
		}
		if (has(entry, "at")) {
			literal(entry, "at", Type.DATE_TIME);
		}
		String text = has(entry, "dates") ? text(entry, "dates") : null;
		if (text != null) {
			List<String> dates = new ArrayList<>();
			for (String part : text.split("[,;\\s]")) {
				if (!part.isEmpty()) {
					try {
						dates.add(RuleText.printValue(RuleText.parseValue(part, Type.DATE), Type.DATE));
					} catch (SyntaxError ex) {
						return;
					}
				}
			}
			update(entry, "dates", text, String.join(", ", dates));
		}
	}

	private static boolean has(TLObject obj, String attribute) {
		return obj.tType().getPart(attribute) != null;
	}

	private static Type parameterType(TLObject parameter) {
		return parameter.tValueByName("type") instanceof TLClassifier type ? Type.fromName(type.getName()) : null;
	}

	private static void expression(TLObject obj, String attribute) {
		String text = text(obj, attribute);
		if (text != null) {
			try {
				update(obj, attribute, text, RuleText.print(RuleText.parse(text)));
			} catch (SyntaxError ex) {
				// Kept as typed, the field shows the error.
			}
		}
	}

	private static void literal(TLObject obj, String attribute, Type type) {
		String text = text(obj, attribute);
		if (text != null) {
			try {
				update(obj, attribute, text, RuleText.printValue(RuleText.parseValue(text, type), type));
			} catch (SyntaxError ex) {
				// Kept as typed, the field shows the error.
			}
		}
	}

	/** The step of a parameter: a duration for a duration parameter, else a number. */
	private static void step(TLObject parameter, Type type) {
		String text = text(parameter, "step");
		if (text == null) {
			return;
		}
		try {
			if (type == Type.DURATION) {
				update(parameter, "step", text, RuleText.printValue(RuleText.parseValue(text, Type.DURATION), Type.DURATION));
				return;
			}
			Map<String, Object> node = RuleText.parse(text);
			if ("literal".equals(node.get("kind")) && node.get("value") instanceof Number number) {
				update(parameter, "step", text,
					RuleText.printValue(number, number instanceof Double ? Type.REAL : Type.INTEGER));
			}
		} catch (SyntaxError ex) {
			// Kept as typed, the field shows the error.
		}
	}

	private static void message(TLObject action) {
		String text = text(action, "message");
		if (text != null) {
			try {
				update(action, "message", text, RuleText.printMessage(RuleText.parseMessage(text)));
			} catch (SyntaxError ex) {
				// Kept as typed, the field shows the error.
			}
		}
	}

	private static String text(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value == null || value.toString().isBlank() ? null : value.toString();
	}

	private static void update(TLObject obj, String attribute, String before, String canonical) {
		if (!canonical.equals(before)) {
			obj.tUpdateByName(attribute, canonical);
		}
	}

}
