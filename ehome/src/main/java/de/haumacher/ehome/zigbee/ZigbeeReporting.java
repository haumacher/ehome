/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * The reporting of the functions of Zigbee devices (haumacher/ehome #64, homecontrol
 * <code>spec/zigbee.md</code> "Reporting overrides"): the installer's override of a function
 * (<code>ehome.zigbee:ZigbeeFunction#reportingMin</code>, <code>reportingMax</code>,
 * <code>reportingChange</code>), which the runtime image carries as the binding's
 * <code>reporting</code>, and what the controller does (<code>reportedMin</code>,
 * <code>reportedMax</code>, <code>reportedChange</code> from its export, else the defaults of
 * {@link ZigbeeFunctionTable}).
 *
 * <p>
 * {@link #check(ZigbeeFunctionTable.Info, Long, Long, Double)} is the one check of an override,
 * the controller's <code>resolve_reporting</code> (<code>core/src/zigbee/cluster_mapping.cpp</code>):
 * the {@link de.haumacher.ehome.runtime.ImageValidator} refuses a binding's <code>reporting</code>
 * with its {@link Refusal#text() text}, the controller's reason, and the constraint of the
 * function's attributes ({@link ReportingConstraint}) refuses the edit of an override with its
 * {@link Refusal#reason() reason}, whose English text is the same.
 * </p>
 */
public final class ZigbeeReporting {

	/** The largest minimum interval (ZCL). */
	public static final int MAX_MIN = 0xFFFE;

	/** The maximum interval meaning no periodic reports: the device reports on a change only. */
	public static final int NO_PERIODIC = 0;

	/** The maximum interval meaning no reports at all: the value comes from reads only. */
	public static final int NO_REPORTS = 0xFFFF;

	/** The attribute of the installer's minimum interval. */
	public static final String MIN = "reportingMin";

	/** The attribute of the installer's maximum interval. */
	public static final String MAX = "reportingMax";

	/** The attribute of the installer's reportable change. */
	public static final String CHANGE = "reportingChange";

	/**
	 * The member of an override a refusal concerns.
	 */
	public enum Member {
		/** The override as a whole: the function takes none. */
		WHOLE("", null),
		/** The minimum interval. */
		MIN(".min", ZigbeeReporting.MIN),
		/** The maximum interval. */
		MAX(".max", ZigbeeReporting.MAX),
		/** The reportable change. */
		CHANGE(".change", ZigbeeReporting.CHANGE);

		private final String _path;

		private final String _attribute;

		Member(String path, String attribute) {
			_path = path;
			_attribute = attribute;
		}

		/** The segment of the path of the image after <code>reporting</code>. */
		public String path() {
			return _path;
		}

		/** The attribute of the function holding the member; <code>null</code> for the whole. */
		public String attribute() {
			return _attribute;
		}
	}

	/**
	 * How a function's reportable change relates to the datapoint's unit.
	 */
	public enum ChangeKind {
		/** None: a discrete value reports every change (<code>on_off</code>, <code>occupancy</code>). */
		NONE,
		/**
		 * The controller's fixed attribute step, which has no fixed step in the datapoint's unit
		 * (<code>illuminance</code>, <code>color_temperature</code>).
		 */
		FIXED,
		/** A number in the datapoint's unit, which an override may set. */
		UNIT,
	}

	/**
	 * Why an override is refused.
	 *
	 * @param member
	 *        The member it concerns.
	 * @param text
	 *        The controller's reason, as its loader gives it.
	 * @param reason
	 *        The same for the user, in both languages (its English text is {@link #text()}).
	 */
	public record Refusal(Member member, String text, ResKey reason) {
		// A record.
	}

	private ZigbeeReporting() {
		// Static helpers.
	}

	/**
	 * How the reportable change of the function with the given key relates to the datapoint's unit;
	 * <code>null</code> for a function without reporting or an unknown key.
	 */
	public static ChangeKind changeKind(String key) {
		if (key == null) {
			return null;
		}
		switch (key) {
			case "on_off":
			case "occupancy":
				return ChangeKind.NONE;
			case "illuminance":
			case "color_temperature":
				return ChangeKind.FIXED;
			case "level":
			case "temperature":
			case "humidity":
			case "battery":
				return ChangeKind.UNIT;
			default:
				return null;
		}
	}

	/**
	 * Why the function takes no override at all, even an empty one; <code>null</code> if it takes
	 * one (the controller configures its reporting).
	 */
	public static Refusal wholeRefusal(ZigbeeFunctionTable.Info function) {
		if (function.reporting() != null) {
			return null;
		}
		switch (function.key()) {
			case ZigbeeFunctionTable.ACTION:
				return new Refusal(Member.WHOLE,
					"the function action (a client binding, button commands) configures no reporting",
					I18NConstants.REPORTING_NONE_ACTION);
			case ZigbeeFunctionTable.MULTISTATE:
				return new Refusal(Member.WHOLE, "the function multistate configures no reporting (its reports are events)",
					I18NConstants.REPORTING_NONE_MULTISTATE);
			case ZigbeeFunctionTable.IAS_ZONE:
				return new Refusal(Member.WHOLE,
					"the function ias_zone configures no reporting (zone status change notifications)",
					I18NConstants.REPORTING_NONE_IAS_ZONE);
			default:
				return new Refusal(Member.WHOLE, "the function configures no reporting", I18NConstants.REPORTING_NONE);
		}
	}

	/**
	 * Why the override of the function is refused, <code>null</code> if it is valid (the
	 * controller's <code>resolve_reporting</code>, in its order): the function takes none, the
	 * minimum is outside 0..65534, the maximum outside 0..65535, the effective maximum (a member not
	 * given is the function's default) is below the effective minimum (except the maxima 0 and
	 * 65535), or the change is not allowed for the function or outside 0 and the range of the
	 * attribute. An override without any member is valid where the function takes one.
	 *
	 * @param min
	 *        The minimum interval in seconds, <code>null</code> if not given.
	 * @param max
	 *        The maximum interval in seconds, <code>null</code> if not given.
	 * @param change
	 *        The reportable change in the datapoint's unit, <code>null</code> if not given.
	 */
	public static Refusal check(ZigbeeFunctionTable.Info function, Long min, Long max, Double change) {
		if (min == null && max == null && change == null) {
			return null;
		}
		Refusal whole = wholeRefusal(function);
		if (whole != null) {
			return whole;
		}
		if (min != null && (min.longValue() < 0 || min.longValue() > MAX_MIN)) {
			return new Refusal(Member.MIN, "min must be 0.." + MAX_MIN, I18NConstants.REPORTING_MIN_RANGE);
		}
		if (max != null && (max.longValue() < 0 || max.longValue() > NO_REPORTS)) {
			return new Refusal(Member.MAX, "max must be 0.." + NO_REPORTS, I18NConstants.REPORTING_MAX_RANGE);
		}
		long effectiveMin = min != null ? min.longValue() : function.reporting().min();
		long effectiveMax = max != null ? max.longValue() : function.reporting().max();
		boolean periodic = effectiveMax != NO_PERIODIC && effectiveMax != NO_REPORTS;
		if (periodic && effectiveMax < effectiveMin) {
			if (max != null) {
				return new Refusal(Member.MAX, "max must not be below min (an absent min is the function's default); "
					+ "0: no periodic reports, 65535: no reports", I18NConstants.REPORTING_MAX_BELOW_MIN);
			}
			return new Refusal(Member.MIN, "min must not exceed max (an absent max is the function's default); "
				+ "max 0: no periodic reports, max 65535: no reports", I18NConstants.REPORTING_MIN_ABOVE_MAX);
		}
		if (change != null) {
			return changeRefusal(function.key(), change.doubleValue());
		}
		return null;
	}

	/**
	 * The controller's <code>change_to_raw</code>: the change is refused for a function without a
	 * change in the datapoint's unit, and when it is negative or rounds to more steps than the
	 * attribute holds.
	 */
	private static Refusal changeRefusal(String key, double change) {
		double scale;
		long maxRaw;
		Refusal range;
		switch (key) {
			case "level":
				scale = 2.54;
				maxRaw = 254;
				range = percentRange();
				break;
			case "temperature":
				scale = 100.0;
				maxRaw = 32767;
				range = new Refusal(Member.CHANGE, "change must be 0..327.67 (°C)", I18NConstants.REPORTING_CHANGE_TEMPERATURE);
				break;
			case "humidity":
				scale = 100.0;
				maxRaw = 10000;
				range = percentRange();
				break;
			case "battery":
				scale = 2.0;
				maxRaw = 200;
				range = percentRange();
				break;
			case "on_off":
			case "occupancy":
				return new Refusal(Member.CHANGE,
					"the function " + key + " has no reportable change (a discrete value reports every change)",
					I18NConstants.REPORTING_NO_CHANGE_DISCRETE__FUNCTION.fill(key));
			case "illuminance":
				return new Refusal(Member.CHANGE,
					"the function illuminance has no reportable change in lux (its attribute is logarithmic: a lux "
						+ "step has no fixed attribute step); min and max are allowed",
					I18NConstants.REPORTING_NO_CHANGE_ILLUMINANCE);
			case "color_temperature":
				return new Refusal(Member.CHANGE,
					"the function color_temperature has no reportable change in K (its attribute counts mireds, "
						+ "1000000 / K: a kelvin step has no fixed attribute step); min and max are allowed",
					I18NConstants.REPORTING_NO_CHANGE_COLOR_TEMPERATURE);
			default:
				return new Refusal(Member.CHANGE, "the function has no reportable change", I18NConstants.REPORTING_NO_CHANGE);
		}
		// std::round rounds half away from zero, Math.round half up: the same for the non-negative.
		if (!(change >= 0) || Math.round(change * scale) > maxRaw) {
			return range;
		}
		return null;
	}

	private static Refusal percentRange() {
		return new Refusal(Member.CHANGE, "change must be 0..100 (%)", I18NConstants.REPORTING_CHANGE_PERCENT);
	}

	/**
	 * Why the installer's override of the function (<code>ehome.zigbee:ZigbeeFunction</code>, or a
	 * form object over it) is refused; <code>null</code> if it is valid or the function's key is
	 * unknown (a newer controller's: E-Home does not know its reporting).
	 */
	public static Refusal check(TLObject function) {
		ZigbeeFunctionTable.Info info = ZigbeeFunctionTable.byKey(String.valueOf(function.tValueByName("function")));
		if (info == null) {
			return null;
		}
		return check(info, integer(function.tValueByName(MIN)), integer(function.tValueByName(MAX)),
			decimal(function.tValueByName(CHANGE)));
	}

	/**
	 * The <code>reporting</code> member of the binding of the function in the runtime image: the
	 * members the installer set, <code>null</code> if none is set.
	 */
	public static Map<String, Object> json(TLObject function) {
		Long min = integer(function.tValueByName(MIN));
		Long max = integer(function.tValueByName(MAX));
		Double change = decimal(function.tValueByName(CHANGE));
		if (min == null && max == null && change == null) {
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		if (min != null) {
			result.put("min", min);
		}
		if (max != null) {
			result.put("max", max);
		}
		if (change != null) {
			result.put("change", change);
		}
		return result;
	}

	/**
	 * What the controller does with the reporting of the function, for the user: "Reports: every
	 * 30..600 s, change 0.1 °C (default)" — the effective values of the last export where it carried
	 * them, else the defaults of the function table; <code>null</code> for an unknown key.
	 */
	public static String summary(TLObject function) {
		Object key = function.tValueByName("function");
		ZigbeeFunctionTable.Info info = key == null ? null : ZigbeeFunctionTable.byKey(key.toString());
		if (info == null) {
			return null;
		}
		Resources resources = Resources.getInstance();
		if (info.reporting() == null) {
			return resources.getString(I18NConstants.REPORTING_NOT_CONFIGURED);
		}
		Long reportedMin = integer(function.tValueByName("reportedMin"));
		Long reportedMax = integer(function.tValueByName("reportedMax"));
		boolean reported = reportedMin != null || reportedMax != null;
		long min = reportedMin != null ? reportedMin.longValue() : info.reporting().min();
		long max = reportedMax != null ? reportedMax.longValue() : info.reporting().max();
		Double change = reported ? decimal(function.tValueByName("reportedChange")) : info.reporting().change();

		ResKey reports;
		if (max == NO_REPORTS) {
			reports = I18NConstants.REPORTING_OFF;
		} else {
			reports = max == NO_PERIODIC ? I18NConstants.REPORTING_ON_CHANGE__MIN.fill(Long.toString(min))
				: I18NConstants.REPORTING_EVERY__MIN_MAX.fill(Long.toString(min), Long.toString(max));
			ChangeKind kind = changeKind(info.key());
			if (kind == ChangeKind.FIXED || (kind == ChangeKind.UNIT && change == null)) {
				reports = I18NConstants.REPORTING_CHANGE_DEFAULT__REPORTS.fill(reports);
			} else if (kind == ChangeKind.UNIT) {
				Object unit = function.tValueByName("unit");
				reports = I18NConstants.REPORTING_CHANGE__REPORTS_CHANGE_UNIT.fill(reports, format(change.doubleValue()),
					unit == null ? "" : unit.toString());
			}
		}
		return resources.getString(reported ? I18NConstants.REPORTING_CONTROLLER__REPORTS.fill(reports)
			: I18NConstants.REPORTING_DEFAULT__REPORTS.fill(reports));
	}

	private static String format(double value) {
		return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Resources.getCurrentLocale())).format(value);
	}

	/** An integer attribute value as {@link Long}, <code>null</code> if not set. */
	static Long integer(Object value) {
		return value instanceof Number number ? Long.valueOf(number.longValue()) : null;
	}

	/** A decimal attribute value as {@link Double}, <code>null</code> if not set. */
	static Double decimal(Object value) {
		return value instanceof Number number ? Double.valueOf(number.doubleValue()) : null;
	}

}
