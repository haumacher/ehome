/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.top_logic.basic.util.ResKey;
import com.top_logic.util.Resources;

import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;

/**
 * The values of the parameters of a virtual device as people read and enter them (haumacher/ehome
 * #78), and the conversion to the number the model stores (<code>BlockParameter#value</code>, the
 * number the block sees, <code>spec/blocks.md</code> B6).
 *
 * <p>
 * A choice is shown and chosen by its label in the user's language ({@link BlockTexts}); a duration,
 * stored in ms, is shown and entered in the unit the manifest gives it (<code>min</code>,
 * <code>s</code>, <code>h</code>; none means ms); a time of day, stored as the seconds since
 * midnight, is a time of day; a truth value, stored as 0 or 1, is yes or no.
 * </p>
 */
public final class BlockValues {

	/** How a parameter is entered. */
	public enum Kind {
		/** A truth value. */
		BOOLEAN("boolean"),
		/** One of the choices the type declares, by its label. */
		CHOICE("choice"),
		/** A duration in the unit of the manifest. */
		DURATION("duration"),
		/** A time of day. */
		TIME("time"),
		/** A number in the unit of the manifest. */
		NUMBER("number");

		private final String _name;

		Kind(String name) {
			_name = name;
		}

		/** The name the views decide on. */
		public String externalName() {
			return _name;
		}
	}

	private BlockValues() {
		// Static helpers.
	}

	/** How the parameter is entered. */
	public static Kind kind(Param decl) {
		if (!decl.choices().isEmpty()) {
			return Kind.CHOICE;
		}
		switch (decl.type()) {
			case BOOLEAN:
				return Kind.BOOLEAN;
			case DURATION:
				return Kind.DURATION;
			case TIME_OF_DAY:
				return Kind.TIME;
			default:
				return Kind.NUMBER;
		}
	}

	/**
	 * The milliseconds of one unit of a duration: <code>ms</code> (or none) 1, <code>s</code> 1000,
	 * <code>min</code> 60000, <code>h</code> 3600000, <code>d</code> 86400000.
	 */
	public static long msPerUnit(String unit) {
		switch (unit == null ? "" : unit.trim()) {
			case "s":
				return 1000;
			case "min":
				return 60000;
			case "h":
				return 3600000;
			case "d":
				return 86400000;
			default:
				return 1;
		}
	}

	/** The unit a value of the parameter is shown and entered in; empty for none. */
	public static String unit(Param decl) {
		if (decl.type() == ParamType.DURATION) {
			return msPerUnit(decl.unit()) == 1 ? "ms" : decl.unit().trim();
		}
		if (decl.type() == ParamType.BOOLEAN || decl.type() == ParamType.TIME_OF_DAY || !decl.choices().isEmpty()) {
			return "";
		}
		return decl.unit();
	}

	/** A duration in ms in the unit of the parameter. */
	public static double toUnit(Param decl, double ms) {
		return ms / msPerUnit(decl.unit());
	}

	/** A duration in the unit of the parameter in ms (whole ms, as the block counts them). */
	public static double toMs(Param decl, double value) {
		return Math.round(value * msPerUnit(decl.unit()));
	}

	/** The label of a choice of the parameter of the block type in the user's language. */
	public static String choiceLabel(String type, Param decl, Choice choice) {
		return BlockTexts.label(type, decl.id() + "." + choice.id(), choice.label(), language());
	}

	/** The labels of the choices of the parameter in the user's language, in manifest order. */
	public static List<String> choiceLabels(String type, Param decl) {
		List<String> result = new ArrayList<>();
		for (Choice choice : decl.choices()) {
			result.add(choiceLabel(type, decl, choice));
		}
		return result;
	}

	/** The choice of the parameter with the label in the user's language, <code>null</code> for none. */
	public static Choice choiceOfLabel(String type, Param decl, String label) {
		for (Choice choice : decl.choices()) {
			if (choiceLabel(type, decl, choice).equals(label)) {
				return choice;
			}
		}
		return null;
	}

	/**
	 * The value of the parameter for the user: a choice by its label, a duration in the unit of the
	 * manifest, a time of day as <code>hh:mm</code> (<code>hh:mm:ss</code> with seconds), a truth
	 * value as yes or no, a number with its unit.
	 */
	public static String display(String type, Param decl, double value) {
		switch (kind(decl)) {
			case BOOLEAN:
				return text(value != 0 ? I18NConstants.BLOCK_VALUE_YES : I18NConstants.BLOCK_VALUE_NO);
			case CHOICE: {
				Choice choice = decl.choice(value);
				return choice != null ? choiceLabel(type, decl, choice) : format(value);
			}
			case TIME: {
				long s = (long) value;
				return s % 60 == 0 ? String.format("%02d:%02d", Long.valueOf(s / 3600), Long.valueOf(s % 3600 / 60))
					: String.format("%02d:%02d:%02d", Long.valueOf(s / 3600), Long.valueOf(s % 3600 / 60),
						Long.valueOf(s % 60));
			}
			case DURATION:
				return format(toUnit(decl, value)) + " " + unit(decl);
			default: {
				String unit = unit(decl);
				return unit.isEmpty() ? format(value) : format(value) + " " + unit;
			}
		}
	}

	/** The bounds or the choices of the parameter for the user. */
	public static String range(String type, Param decl) {
		if (!decl.choices().isEmpty()) {
			return String.join(", ", choiceLabels(type, decl));
		}
		if (decl.type() == ParamType.BOOLEAN) {
			return text(I18NConstants.BLOCK_VALUE_YES) + ", " + text(I18NConstants.BLOCK_VALUE_NO);
		}
		if (decl.min() == null && decl.max() == null) {
			return "";
		}
		return (decl.min() == null ? "" : display(type, decl, decl.min().doubleValue())) + " .. "
			+ (decl.max() == null ? "" : display(type, decl, decl.max().doubleValue()));
	}

	/**
	 * The value the input of the parameter shows for the stored value: a {@link Boolean}, the label
	 * of a choice, a duration in the unit of the manifest, a time of day as a {@link Date} on
	 * 1970-01-01 in the given zone (as the time input reads it), a number.
	 */
	public static Object editValue(String type, Param decl, double value, TimeZone zone) {
		switch (kind(decl)) {
			case BOOLEAN:
				return Boolean.valueOf(value != 0);
			case CHOICE: {
				Choice choice = decl.choice(value);
				return choice == null ? null : choiceLabel(type, decl, choice);
			}
			case TIME: {
				Calendar calendar = Calendar.getInstance(zone);
				calendar.clear();
				calendar.set(1970, Calendar.JANUARY, 1, 0, 0, 0);
				long s = (long) value;
				calendar.set(Calendar.HOUR_OF_DAY, (int) (s / 3600));
				calendar.set(Calendar.MINUTE, (int) (s % 3600 / 60));
				calendar.set(Calendar.SECOND, (int) (s % 60));
				return calendar.getTime();
			}
			case DURATION:
				return Double.valueOf(toUnit(decl, value));
			default:
				return Double.valueOf(value);
		}
	}

	/**
	 * The stored value of an entered one (see {@link #editValue(String, Param, double, TimeZone)});
	 * <code>null</code> for an empty input.
	 *
	 * @throws IllegalArgumentException
	 *         For a value the parameter cannot take (an unknown choice, a value of another type).
	 */
	public static Double parse(String type, Param decl, Object entered, TimeZone zone) {
		if (entered == null || (entered instanceof String text && text.isBlank())) {
			return null;
		}
		switch (kind(decl)) {
			case BOOLEAN:
				if (entered instanceof Boolean bool) {
					return Double.valueOf(bool.booleanValue() ? 1 : 0);
				}
				break;
			case CHOICE: {
				Choice choice = choiceOfLabel(type, decl, entered.toString());
				if (choice != null) {
					return Double.valueOf(choice.value());
				}
				break;
			}
			case TIME:
				if (entered instanceof Date date) {
					Calendar calendar = Calendar.getInstance(zone);
					calendar.setTime(date);
					return Double.valueOf(calendar.get(Calendar.HOUR_OF_DAY) * 3600 + calendar.get(Calendar.MINUTE) * 60
						+ calendar.get(Calendar.SECOND));
				}
				break;
			case DURATION:
				if (entered instanceof Number number) {
					return Double.valueOf(toMs(decl, number.doubleValue()));
				}
				break;
			default:
				if (entered instanceof Number number) {
					return Double.valueOf(number.doubleValue());
				}
				break;
		}
		throw new IllegalArgumentException(text(I18NConstants.BLOCK_VALUE_INVALID__VALUE_PARAMETER.fill(entered,
			BlockTexts.label(type, decl.id(), decl.label(), language()))));
	}

	/** The label of an aggregation in the user's language. */
	public static String aggregationLabel(Aggregation aggregation) {
		switch (aggregation) {
			case MEAN:
				return text(I18NConstants.BLOCK_AGGREGATION_MEAN);
			case MIN:
				return text(I18NConstants.BLOCK_AGGREGATION_MIN);
			case MAX:
				return text(I18NConstants.BLOCK_AGGREGATION_MAX);
			case ANY:
				return text(I18NConstants.BLOCK_AGGREGATION_ANY);
			case ALL:
				return text(I18NConstants.BLOCK_AGGREGATION_ALL);
			default:
				return aggregation.externalName();
		}
	}

	/** The aggregation of the given ones with the label in the user's language, <code>null</code> for none. */
	public static Aggregation aggregationOfLabel(List<Aggregation> aggregations, String label) {
		for (Aggregation aggregation : aggregations) {
			if (aggregationLabel(aggregation).equals(label)) {
				return aggregation;
			}
		}
		return null;
	}

	/**
	 * A number for the user: at most six decimals, without trailing zeros (21, 1.5, 0.333333).
	 */
	public static String format(double value) {
		if (!Double.isFinite(value)) {
			return Double.toString(value);
		}
		return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
	}

	/** The language of the user. */
	static String language() {
		return Resources.getInstance().getLocale().getLanguage();
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

}
