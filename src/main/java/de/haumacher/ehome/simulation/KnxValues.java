/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.DatapointValues;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.Color;
import de.haumacher.ehome.rules.Val.Date;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.ValueType;

/**
 * The values of the rules ({@link Val}) as the data of group telegrams, by the datapoint type of
 * the address: the value types of the DPTs and their coding as the controller's KNX adapter has
 * them (homecontrol <code>core/src/dpt.cpp</code>), the octets by {@link DatapointValues} (the DPT
 * conformance corpus E-Home shares with HomeControl).
 *
 * <ul>
 * <li>DPT 1: boolean; 2, 3, 18: controlled (the control bit, the value); 4, 16: string.</li>
 * <li>DPT 5, 7, 12, 17: unsigned; 6, 8, 13: signed; 5.001 (percent), 5.003 (degrees), 8.010
 * (percent), 9, 14: real; the durations 7.003, 7.004, 8.003, 8.004 in milliseconds.</li>
 * <li>DPT 10: time of day with the day of the week; 11: date; 19: date and time, its flags as bits
 * in the order of {@link DatapointValues#DATE_TIME_FLAGS} from bit 0; 20: enumeration; 232.600,
 * 251.600: color.</li>
 * </ul>
 *
 * <p>
 * Numeric values are interchangeable as the image allows: a number is coded by the DPT if it has
 * the DPT's form (an integer type takes an integral real), and a decoded number is converted to the
 * datapoint's type where that is exact.
 * </p>
 */
public final class KnxValues {

	private KnxValues() {
		// Functions only.
	}

	/**
	 * Why a value or data cannot be coded.
	 */
	public static final class CodingException extends Exception {

		/**
		 * Creates a {@link CodingException}.
		 */
		public CodingException(String message) {
			super(message);
		}
	}

	/**
	 * The value type of the datapoint type, <code>null</code> for a type without a coding.
	 */
	public static ValueType valueType(Dpt dpt) {
		if (dpt == null || !DatapointValues.supports(dpt)) {
			return null;
		}
		switch (dpt.main()) {
			case 1:
				return ValueType.BOOLEAN;
			case 2:
			case 3:
			case 18:
				return ValueType.CONTROLLED;
			case 4:
			case 16:
				return ValueType.STRING;
			case 5:
				return dpt.sub() == 1 || dpt.sub() == 3 ? ValueType.REAL : ValueType.UNSIGNED;
			case 6:
			case 13:
				return ValueType.SIGNED;
			case 7:
			case 12:
			case 17:
				return ValueType.UNSIGNED;
			case 8:
				return dpt.sub() == 10 ? ValueType.REAL : ValueType.SIGNED;
			case 9:
			case 14:
				return ValueType.REAL;
			case 10:
				return ValueType.TIME_OF_DAY;
			case 11:
				return ValueType.DATE;
			case 19:
				return ValueType.DATE_TIME;
			case 20:
				return ValueType.ENUMERATION;
			case 232:
			case 251:
				return ValueType.COLOR;
			default:
				return null;
		}
	}

	/**
	 * Whether the datapoint type codes values of the given type (numeric types are
	 * interchangeable).
	 */
	public static boolean fits(Dpt dpt, ValueType type) {
		ValueType own = valueType(dpt);
		return own != null && (own == type || numeric(own) && numeric(type));
	}

	private static boolean numeric(ValueType type) {
		return type == ValueType.UNSIGNED || type == ValueType.SIGNED || type == ValueType.REAL
			|| type == ValueType.ENUMERATION;
	}

	/**
	 * The value of the data of a group telegram, in the value type of the datapoint type.
	 *
	 * @param data
	 *        The data: for the types embedded in the octet of the service (DPT 1, 2, 3) the one octet
	 *        holding the bits.
	 */
	public static Val decode(Dpt dpt, byte[] data) throws CodingException {
		ValueType type = valueType(dpt);
		if (type == null) {
			throw new CodingException("the datapoint type " + dpt.code() + " has no coding");
		}
		Object json;
		try {
			json = DatapointValues.decode(dpt, data);
		} catch (DatapointValues.Refusal ex) {
			throw new CodingException(text(dpt, ex));
		}
		switch (type) {
			case BOOLEAN:
				return Val.ofBool(((Boolean) json).booleanValue());
			case CONTROLLED: {
				Map<?, ?> map = (Map<?, ?>) json;
				return Val.ofControlled(((Boolean) map.get(DatapointValues.CONTROL)).booleanValue(),
					((Number) map.get(DatapointValues.VALUE)).intValue());
			}
			case STRING: {
				Val result = Val.ofString((String) json);
				if (!result.has()) {
					throw new CodingException("the text is longer than a string value");
				}
				return result;
			}
			case UNSIGNED:
			case SIGNED:
				return json instanceof Double real ? Val.ofReal(real.doubleValue()) : Val.ofInt(((Number) json).longValue());
			case REAL:
				return Val.ofReal(((Number) json).doubleValue());
			case ENUMERATION:
				return Val.ofEnum(((Number) json).longValue());
			case TIME_OF_DAY: {
				Map<?, ?> map = (Map<?, ?>) json;
				int hour = number(map, "hour");
				if (hour > 23) {
					throw new CodingException("the end of the day (24:00) is no time of day of the rules");
				}
				return Val.ofTime(new TimeOfDay(hour, number(map, "minute"), number(map, "second"), number(map, "dayOfWeek")));
			}
			case DATE: {
				Map<?, ?> map = (Map<?, ?>) json;
				return Val.ofDate(new Date(number(map, "year"), number(map, "month"), number(map, "day")));
			}
			case DATE_TIME: {
				Map<?, ?> map = (Map<?, ?>) json;
				int flags = 0;
				for (int n = 0; n < DatapointValues.DATE_TIME_FLAGS.size(); n++) {
					if (Boolean.TRUE.equals(map.get(DatapointValues.DATE_TIME_FLAGS.get(n)))) {
						flags |= 1 << n;
					}
				}
				int hour = number(map, "hour");
				if (hour > 23) {
					throw new CodingException("the end of the day (24:00) is no time of the rules");
				}
				return Val.ofDateTime(new DateTime(number(map, "year"), number(map, "month"), number(map, "day"), hour,
					number(map, "minute"), number(map, "second"), number(map, "dayOfWeek"), flags));
			}
			case COLOR: {
				Map<?, ?> map = (Map<?, ?>) json;
				if (dpt.main() == 232) {
					return Val.ofColor(new Color(number(map, "red"), number(map, "green"), number(map, "blue"), 0, Color.RGB));
				}
				int valid = 0;
				Object channels = map.get(DatapointValues.VALID);
				List<?> list = channels instanceof List<?> l ? l : List.of("red", "green", "blue", "white");
				for (Object channel : list) {
					valid |= channelBit((String) channel);
				}
				return Val.ofColor(new Color(number(map, "red"), number(map, "green"), number(map, "blue"),
					number(map, "white"), valid));
			}
			default:
				throw new CodingException("no coding for " + type);
		}
	}

	/**
	 * The data of a group telegram carrying the value.
	 */
	public static byte[] encode(Dpt dpt, Val value) throws CodingException {
		ValueType type = valueType(dpt);
		if (type == null) {
			throw new CodingException("the datapoint type " + dpt.code() + " has no coding");
		}
		if (!value.has()) {
			throw new CodingException("no value");
		}
		Object json;
		switch (type) {
			case BOOLEAN:
				json = Boolean.valueOf(require(value, de.haumacher.ehome.rules.Type.BOOLEAN).bool());
				break;
			case CONTROLLED: {
				Val.Controlled controlled = require(value, de.haumacher.ehome.rules.Type.CONTROLLED).controlled();
				Map<String, Object> map = new LinkedHashMap<>();
				map.put(DatapointValues.CONTROL, Boolean.valueOf(controlled.control()));
				map.put(DatapointValues.VALUE, Integer.valueOf(controlled.value()));
				json = map;
				break;
			}
			case STRING:
				json = require(value, de.haumacher.ehome.rules.Type.STRING).text();
				break;
			case UNSIGNED:
			case SIGNED:
			case REAL:
			case ENUMERATION:
				json = number(value);
				break;
			case TIME_OF_DAY: {
				TimeOfDay time = require(value, de.haumacher.ehome.rules.Type.TIME_OF_DAY).time();
				Map<String, Object> map = new LinkedHashMap<>();
				map.put("dayOfWeek", Integer.valueOf(time.dayOfWeek()));
				map.put("hour", Integer.valueOf(time.hour()));
				map.put("minute", Integer.valueOf(time.minute()));
				map.put("second", Integer.valueOf(time.second()));
				json = map;
				break;
			}
			case DATE: {
				Date date = require(value, de.haumacher.ehome.rules.Type.DATE).date();
				Map<String, Object> map = new LinkedHashMap<>();
				map.put("year", Integer.valueOf(date.year()));
				map.put("month", Integer.valueOf(date.month()));
				map.put("day", Integer.valueOf(date.day()));
				json = map;
				break;
			}
			case DATE_TIME: {
				DateTime dateTime = require(value, de.haumacher.ehome.rules.Type.DATE_TIME).dateTime();
				Map<String, Object> map = new LinkedHashMap<>();
				map.put("year", Integer.valueOf(dateTime.year()));
				map.put("month", Integer.valueOf(dateTime.month()));
				map.put("day", Integer.valueOf(dateTime.day()));
				map.put("dayOfWeek", Integer.valueOf(dateTime.dayOfWeek()));
				map.put("hour", Integer.valueOf(dateTime.hour()));
				map.put("minute", Integer.valueOf(dateTime.minute()));
				map.put("second", Integer.valueOf(dateTime.second()));
				for (int n = 0; n < DatapointValues.DATE_TIME_FLAGS.size(); n++) {
					map.put(DatapointValues.DATE_TIME_FLAGS.get(n), Boolean.valueOf((dateTime.flags() & 1 << n) != 0));
				}
				json = map;
				break;
			}
			case COLOR: {
				Color color = require(value, de.haumacher.ehome.rules.Type.COLOR).color();
				Map<String, Object> map = new LinkedHashMap<>();
				map.put("red", Integer.valueOf(color.red()));
				map.put("green", Integer.valueOf(color.green()));
				map.put("blue", Integer.valueOf(color.blue()));
				if (dpt.main() == 251) {
					map.put("white", Integer.valueOf(color.white()));
					List<String> valid = new ArrayList<>();
					for (String channel : List.of("red", "green", "blue", "white")) {
						if ((color.valid() & channelBit(channel)) != 0) {
							valid.add(channel);
						}
					}
					map.put(DatapointValues.VALID, valid);
				}
				json = map;
				break;
			}
			default:
				throw new CodingException("no coding for " + type);
		}
		try {
			return DatapointValues.encode(dpt, json);
		} catch (DatapointValues.Refusal ex) {
			throw new CodingException(text(dpt, ex));
		}
	}

	private static Val require(Val value, de.haumacher.ehome.rules.Type type) throws CodingException {
		if (value.type() != type) {
			throw new CodingException("a " + type.externalName() + " value is required, not " + value);
		}
		return value;
	}

	private static Number number(Val value) throws CodingException {
		switch (value.type()) {
			case INTEGER:
			case ENUMERATION:
				return Long.valueOf(value.integer());
			case REAL:
				return Double.valueOf(value.real());
			default:
				throw new CodingException("a number is required, not " + value);
		}
	}

	private static int number(Map<?, ?> map, String name) {
		Object value = map.get(name);
		return value instanceof Number number ? number.intValue() : 0;
	}

	private static int channelBit(String channel) {
		switch (channel) {
			case "red":
				return Color.RED;
			case "green":
				return Color.GREEN;
			case "blue":
				return Color.BLUE;
			case "white":
				return Color.WHITE;
			default:
				return 0;
		}
	}

	private static String text(Dpt dpt, DatapointValues.Refusal ex) {
		switch (ex.status()) {
			case WRONG_LENGTH:
				return "the data does not have the length of " + dpt.code();
			case OUT_OF_RANGE:
				return "the value is outside the range of " + dpt.code();
			default:
				return "the data is no value of " + dpt.code();
		}
	}

}
