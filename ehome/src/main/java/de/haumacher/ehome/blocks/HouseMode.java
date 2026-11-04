/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import java.util.List;

import de.haumacher.ehome.blocks.BlockContext.InputValue;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Field;
import de.haumacher.ehome.blocks.Manifest.InputTrigger;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.Manifest.PortType;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Values;

/**
 * The house mode <code>org.homecontrol.house-mode</code> 1.0 (homecontrol <code>spec/blocks.md</code>
 * 19): home, away, night or vacation from presence, push buttons, the morning and evening of schedule
 * entries and a vacation switch; the mode as a KNX HVAC mode for the room controllers and as booleans;
 * persisted across a reboot. A literal port of homecontrol <code>core/src/blocks/house_mode.cpp</code>,
 * in IEEE 754 doubles in the order the specification writes it (<code>spec/blocks.md</code> 13).
 */
public final class HouseMode extends BlockType {

	/** The type id. */
	public static final String TYPE = "org.homecontrol.house-mode";

	/** The one instance. */
	public static final HouseMode INSTANCE = new HouseMode();

	// Ports, in manifest order.

	private static final int PRESENCE = 0;

	private static final int AWAY_BUTTON = 1;

	private static final int NIGHT_BUTTON = 2;

	private static final int HOME_BUTTON = 3;

	private static final int VACATION_IN = 4;

	private static final int MORNING_IN = 5;

	private static final int EVENING_IN = 6;

	/** Port <code>mode</code>. */
	public static final int MODE = 7;

	/** Port <code>hvacMode</code>. */
	public static final int HVAC_MODE = 8;

	/** Port <code>home</code>; <code>away</code>, <code>night</code>, <code>onVacation</code> follow. */
	public static final int HOME_OUT = 9;

	/** Port <code>occupied</code>. */
	public static final int OCCUPIED = 13;

	/** Port <code>since</code>. */
	public static final int SINCE = 14;

	// Parameters, in manifest order.

	private static final int AWAY_DELAY = 0;

	private static final int RETURN_DETECTION = 1;

	/** <code>hvacHome</code>; <code>hvacAway</code>, <code>hvacNight</code>, <code>hvacVacation</code> follow. */
	private static final int HVAC_HOME = 2;

	// State fields, in manifest order.

	/** State field <code>mode</code>. */
	public static final int S_MODE = 0;

	/** State field <code>since</code>. */
	public static final int S_SINCE = 1;

	private static final int S_CHANGED_AT = 2;

	private static final int S_ABSENT_SINCE = 3;

	private static final int S_ARMED = 4;

	/** <code>awaySeen</code>; <code>nightSeen</code>, <code>homeSeen</code> follow. */
	private static final int S_AWAY_SEEN = 5;

	private static final int S_LAST_MORNING = 8;

	private static final int S_LAST_EVENING = 9;

	private static final int S_SENT_MODE = 10;

	private static final int S_SENT_SINCE = 11;

	private static final int AWAY_TIMER = 0;

	// The modes.

	private static final int HOME = 0;

	private static final int AWAY = 1;

	private static final int NIGHT = 2;

	private static final int VACATION = 3;

	// The events a call hands the step (spec/blocks.md 19.4).

	private static final int NONE = 0;

	private static final int AWAY_PRESS = 1;

	private static final int NIGHT_PRESS = 2;

	private static final int HOME_PRESS = 3;

	private static final int MORNING = 4;

	private static final int EVENING = 5;

	private static final List<Choice> HVAC_MODES = List.of(new Choice(0, "auto", "Auto"),
		new Choice(1, "comfort", "Comfort"), new Choice(2, "standby", "Standby"), new Choice(3, "night", "Night"),
		new Choice(4, "protection", "Frost/building protection"));

	private static final Manifest MANIFEST = new Manifest(TYPE, 1, 0, "House mode",
		"Home, away, night or vacation from presence, buttons and schedules; the HVAC mode for the room controllers",
		List.of(
			in("presence", "Presence", 16, InputTrigger.CHANGE),
			in("awayButton", "Away button", 4, InputTrigger.UPDATE),
			in("nightButton", "Night button", 4, InputTrigger.UPDATE),
			in("homeButton", "Home button", 4, InputTrigger.UPDATE),
			in("vacation", "Vacation", 1, InputTrigger.CHANGE),
			in("morning", "Morning", 1, InputTrigger.CHANGE),
			in("evening", "Evening", 1, InputTrigger.CHANGE),
			Port.out("mode", PortType.ENUMERATION, "Mode", "", 0, Double.valueOf(0), Double.valueOf(3)),
			Port.out("hvacMode", PortType.ENUMERATION, "HVAC mode", "", 0, Double.valueOf(0), Double.valueOf(4)),
			Port.out("home", PortType.BOOLEAN, "Home", "", 1, null, null),
			Port.out("away", PortType.BOOLEAN, "Away", "", 0, null, null),
			Port.out("night", PortType.BOOLEAN, "Night", "", 0, null, null),
			Port.out("onVacation", PortType.BOOLEAN, "On vacation", "", 0, null, null),
			Port.out("occupied", PortType.BOOLEAN, "Occupied", "", 1, null, null),
			Port.diagnostic("since", PortType.INTEGER, "Since", "min", null, null)),
		List.of(
			new Param("awayDelay", ParamType.DURATION, "Away after", "min", 1800000, Double.valueOf(60000),
				Double.valueOf(86400000), 60000, List.of(), true),
			new Param("returnDetection", ParamType.BOOLEAN, "Return detection", "", 1, null, null, 0, List.of(), true),
			hvac("hvacHome", "HVAC mode at home", 1),
			hvac("hvacAway", "HVAC mode away", 2),
			hvac("hvacNight", "HVAC mode at night", 3),
			hvac("hvacVacation", "HVAC mode on vacation", 4)),
		List.of(new Field("mode", true, 0), new Field("since", true, -1), new Field("changedAt", false, -1),
			new Field("absentSince", false, -1), new Field("armed", false, 1), new Field("awaySeen", false, -1),
			new Field("nightSeen", false, -1), new Field("homeSeen", false, -1), new Field("lastMorning", false, -1),
			new Field("lastEvening", false, -1), new Field("sentMode", false, -1), new Field("sentSince", false, -1)),
		List.of("away"), 60000, 60000);

	private static Port in(String id, String label, int multiple, InputTrigger trigger) {
		return Port.in(id, PortType.BOOLEAN, label, "", false, multiple,
			multiple > 1 ? List.of(Aggregation.ANY) : List.of(), trigger);
	}

	private static Param hvac(String id, String label, double def) {
		return new Param(id, ParamType.ENUMERATION, label, "", def, null, null, 0, HVAC_MODES, false);
	}

	private HouseMode() {
		// The singleton.
	}

	@Override
	public Manifest manifest() {
		return MANIFEST;
	}

	/** The largest update time of the port's datapoints that are good and true; -1 if none (19.4). */
	private static double latest(BlockContext cx, int port) {
		double result = -1;
		for (int k = 0; k < cx.count(port); k++) {
			InputValue v = cx.readAt(port, k);
			if (v.isTrue()) {
				double updated = v.updatedMs();
				result = result < updated ? updated : result;
			}
		}
		return result;
	}

	/** The input's value when it is not unknown, else -1 (19.4). */
	private static double known(BlockContext cx, int port) {
		InputValue v = cx.read(port);
		return v.quality() == Quality.UNKNOWN ? -1 : v.value();
	}

	@Override
	public void start(BlockContext cx, StartReason reason, boolean restored) {
		double[] s = cx.state();
		if (!restored) {
			// A fresh record holds the initial values already.
			s[S_CHANGED_AT] = cx.now();
		} else if (!(s[S_MODE] == HOME || s[S_MODE] == AWAY || s[S_MODE] == NIGHT || s[S_MODE] == VACATION)) {
			// A later minor version's mode (D11): a change.
			s[S_MODE] = HOME;
			s[S_CHANGED_AT] = cx.now();
			s[S_SINCE] = -1;
		}
		s[S_ABSENT_SINCE] = -1;
		s[S_SENT_MODE] = -1;
		s[S_SENT_SINCE] = -1;
		for (int b = 0; b < 3; b++) {
			s[S_AWAY_SEEN + b] = latest(cx, AWAY_BUTTON + b);
		}
		s[S_LAST_MORNING] = known(cx, MORNING_IN);
		s[S_LAST_EVENING] = known(cx, EVENING_IN);
		cx.cancelTimer(AWAY_TIMER);
		step(cx, NONE);
	}

	@Override
	public void input(BlockContext cx, int port) {
		double[] s = cx.state();
		int event = NONE;
		if (port == AWAY_BUTTON || port == NIGHT_BUTTON || port == HOME_BUTTON) {
			int seen = S_AWAY_SEEN + (port - AWAY_BUTTON);
			double l = latest(cx, port);
			if (l > s[seen]) {
				event = AWAY_PRESS + (port - AWAY_BUTTON);
			}
			s[seen] = s[seen] < l ? l : s[seen];
		} else if (port == MORNING_IN || port == EVENING_IN) {
			int last = port == MORNING_IN ? S_LAST_MORNING : S_LAST_EVENING;
			InputValue v = cx.read(port);
			if (v.good() && v.value() != 0 && s[last] == 0) {
				event = port == MORNING_IN ? MORNING : EVENING;
			}
			if (v.quality() != Quality.UNKNOWN) {
				s[last] = v.value();
			}
		}
		step(cx, event);
	}

	@Override
	public void timer(BlockContext cx, int timer) {
		step(cx, NONE);
	}

	@Override
	public void period(BlockContext cx) {
		step(cx, NONE);
	}

	@Override
	public void parameter(BlockContext cx, int index) {
		step(cx, NONE);
	}

	private static void change(double[] s, double t, double m) {
		if (m != s[S_MODE]) {
			s[S_MODE] = m;
			s[S_CHANGED_AT] = t;
			s[S_SINCE] = -1;
			s[S_ABSENT_SINCE] = -1;
		}
	}

	private static void step(BlockContext cx, int event) {
		double[] s = cx.state();
		final double t = cx.now();

		InputValue v = cx.read(VACATION_IN); // D8
		if (v.good() && v.value() != 0) {
			change(s, t, VACATION);
		} else if (s[S_MODE] == VACATION && (v.good() || !v.bound())) {
			change(s, t, HOME);
		}
		if (s[S_MODE] != VACATION) {
			final double mode = s[S_MODE];
			if (event == AWAY_PRESS) { // D5
				change(s, t, AWAY);
				s[S_ARMED] = 0;
			} else if (event == NIGHT_PRESS) {
				if (mode != NIGHT) {
					change(s, t, NIGHT);
				}
			} else if (event == HOME_PRESS) { // D4: also while home
				change(s, t, HOME);
				s[S_ABSENT_SINCE] = -1;
			} else if (event == EVENING) { // D7
				if (mode == HOME) {
					change(s, t, NIGHT);
				}
			} else if (event == MORNING) {
				if (mode == NIGHT) {
					change(s, t, HOME);
				}
			}
			InputValue p = cx.read(PRESENCE); // D2, D5
			boolean present = p.good() && p.value() != 0;
			boolean absent = p.good() && p.value() == 0;
			if (s[S_MODE] == AWAY) {
				if (absent) {
					s[S_ARMED] = 1;
				} else if (present && s[S_ARMED] == 1 && cx.param(RETURN_DETECTION) != 0) {
					change(s, t, HOME);
				}
			}
			if (s[S_MODE] == HOME) {
				if (!absent) {
					s[S_ABSENT_SINCE] = -1;
				} else {
					if (s[S_ABSENT_SINCE] < 0) {
						s[S_ABSENT_SINCE] = t;
					}
					if (t - s[S_ABSENT_SINCE] >= cx.param(AWAY_DELAY)) {
						change(s, t, AWAY);
						s[S_ARMED] = 1;
					}
				}
			}
		}

		DateTime w = cx.wall(); // D14
		if (s[S_SINCE] < 0 && s[S_CHANGED_AT] >= 0 && w != null) {
			double local = (double) Values.daysOf(w.year(), w.month(), w.day()) * 86400 + w.hour() * 3600
				+ w.minute() * 60 + w.second();
			s[S_SINCE] = Math.floor((local - (t - s[S_CHANGED_AT]) / 1000.0) / 60.0);
		}

		if (s[S_MODE] == HOME && s[S_ABSENT_SINCE] >= 0) {
			cx.setTimer(AWAY_TIMER, (long) (s[S_ABSENT_SINCE] + cx.param(AWAY_DELAY) - t));
		} else {
			cx.cancelTimer(AWAY_TIMER);
		}

		final double mode = s[S_MODE];
		if (mode != s[S_SENT_MODE]) { // D13
			cx.write(MODE, mode);
			cx.write(HVAC_MODE, cx.param(HVAC_HOME + (int) mode));
			for (int m = HOME; m <= VACATION; m++) {
				cx.write(HOME_OUT + m, mode == m ? 1 : 0);
			}
			cx.write(OCCUPIED, mode == HOME || mode == NIGHT ? 1 : 0);
			s[S_SENT_MODE] = mode;
		}
		if (s[S_SINCE] >= 0 && s[S_SINCE] != s[S_SENT_SINCE]) {
			cx.write(SINCE, s[S_SINCE]);
			s[S_SENT_SINCE] = s[S_SINCE];
		}
	}

}
