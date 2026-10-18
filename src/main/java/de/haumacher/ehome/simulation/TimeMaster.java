/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.PosixZone;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;

/**
 * The time master of a runtime image (<code>knx.timeMaster</code>, homecontrol
 * <code>spec/runtime-image.md</code> 3.6 and <code>doc/knx.md</code> "Time master") on the simulated
 * clock: it owns the datapoints <code>knx.time</code> (DPT 10.001), <code>knx.date</code> (11.001)
 * and <code>knx.datetime</code> (19.001) of the configured addresses, refuses every command on them,
 * and broadcasts the local time as the controller does.
 *
 * <p>
 * The simulated clock is always synchronized. A broadcast publishes the configured datapoints in the
 * order time, date, date-time; it is due
 * </p>
 * <ul>
 * <li>at the start (<code>onSynchronized</code>, default true);</li>
 * <li>at every tick of the interval (default 600 s), aligned to local time: when the local time in
 * seconds since the epoch is a multiple of the interval (every 10 minutes at :00, :10, ...); the time
 * sent is that instant;</li>
 * <li>at local midnight (<code>atMidnight</code>, default true), unless a broadcast was sent on the
 * new date already;</li>
 * <li>at a change of the UTC offset that no broadcast has shown yet (a daylight saving
 * transition).</li>
 * </ul>
 *
 * <p>
 * The values: DPT 10 the day of the week (1 Monday .. 7 Sunday) with the local time of day; DPT 11
 * the local date; DPT 19 the same fields with the flags summer time (from the zone), no working day
 * (the working day is unknown), clock quality and reliable synchronization source (only synchronized
 * time is sent). Times are whole seconds in UTC.
 * </p>
 */
public final class TimeMaster implements DatapointModel.Technology {

	/** The interval of the broadcasts when the image names none, in seconds. */
	public static final int DEFAULT_INTERVAL = 600;

	private static final int DAY = 86400;

	/** DPT 19 flag bits in the order of the codec's flags: no working day. */
	private static final int NO_WORKING_DAY = 1 << 2;

	/** DPT 19 flag: summer time. */
	private static final int SUMMER_TIME = 1 << 7;

	/** DPT 19 flag: the clock has an external synchronization signal. */
	private static final int CLOCK_QUALITY = 1 << 8;

	/** DPT 19 flag: the synchronization source is reliable. */
	private static final int SYNC_RELIABLE = 1 << 9;

	/**
	 * A datapoint of the time master.
	 *
	 * @param datapoint
	 *        Its id: <code>knx.time</code>, <code>knx.date</code> or <code>knx.datetime</code>.
	 * @param type
	 *        Its value type.
	 * @param dpt
	 *        The datapoint type of its address.
	 * @param address
	 *        The group address it is sent to.
	 */
	public record Member(String datapoint, ValueType type, Dpt dpt, int address) {
		// A value.
	}

	/**
	 * Where the time master's broadcasts go.
	 */
	public interface Output {

		/**
		 * The datapoint got the value (published by the time master); it is to be sent on its
		 * address.
		 */
		void publish(Member member, Val value);

		/**
		 * A command on a datapoint of the time master was refused.
		 */
		void refused(String datapoint, Val value);
	}

	private final List<Member> _members;

	private final int _interval;

	private final boolean _atMidnight;

	private final boolean _onSynchronized;

	private final PosixZone _zone;

	private Output _output;

	/** The instant up to which (inclusive) the time master has done what was due, UTC seconds. */
	private long _handled;

	/** The UTC offset of the last broadcast. */
	private int _shownOffset;

	/** The local date (days since the epoch) of the last broadcast, {@link Long#MIN_VALUE} if none. */
	private long _lastDay = Long.MIN_VALUE;

	private int _broadcasts;

	private TimeMaster(List<Member> members, int interval, boolean atMidnight, boolean onSynchronized,
			PosixZone zone) {
		_members = List.copyOf(members);
		_interval = interval;
		_atMidnight = atMidnight;
		_onSynchronized = onSynchronized;
		_zone = zone;
	}

	/**
	 * The time master of the image, <code>null</code> if it has none.
	 *
	 * @param image
	 *        The runtime image (its parsed JSON).
	 * @param zone
	 *        The time zone of the image's settings.
	 */
	public static TimeMaster of(Map<?, ?> image, PosixZone zone) {
		if (!(image.get("knx") instanceof Map<?, ?> knx) || !(knx.get("timeMaster") instanceof Map<?, ?> master)) {
			return null;
		}
		List<Member> members = new ArrayList<>();
		member(members, master, "time", "knx.time", ValueType.TIME_OF_DAY, "10.001");
		member(members, master, "date", "knx.date", ValueType.DATE, "11.001");
		member(members, master, "dateTime", "knx.datetime", ValueType.DATE_TIME, "19.001");
		if (members.isEmpty()) {
			return null;
		}
		int interval = master.get("interval") instanceof Number number ? number.intValue() : DEFAULT_INTERVAL;
		return new TimeMaster(members, interval, !Boolean.FALSE.equals(master.get("atMidnight")),
			!Boolean.FALSE.equals(master.get("onSynchronized")), zone);
	}

	private static void member(List<Member> members, Map<?, ?> master, String key, String datapoint, ValueType type,
			String dpt) {
		if (master.get(key) instanceof String address) {
			members.add(new Member(datapoint, type, Dpt.parse(dpt), KnxBinding.groupAddress(address)));
		}
	}

	/**
	 * The datapoints, in the order of a broadcast.
	 */
	public List<Member> members() {
		return _members;
	}

	/**
	 * The interval of the broadcasts in seconds.
	 */
	public int interval() {
		return _interval;
	}

	/**
	 * The number of broadcasts sent.
	 */
	public int broadcasts() {
		return _broadcasts;
	}

	/**
	 * Starts the time master at the given instant: a broadcast right away if it broadcasts on
	 * synchronization.
	 */
	public void start(long utc, Output output) {
		_output = output;
		_handled = utc;
		_shownOffset = _zone.offset(utc);
		if (_onSynchronized) {
			broadcast(utc);
		}
	}

	/**
	 * The next instant after the ones handled at which something is due, UTC seconds.
	 */
	public long next() {
		long tick = aligned(_handled, _interval);
		long end = tick;
		if (_atMidnight) {
			end = Math.min(end, aligned(_handled, DAY));
		}
		int offset = _zone.offset(_handled);
		if (_zone.offset(end) != offset) {
			// An offset change before: the first second with the new offset.
			long low = _handled;
			long high = end;
			while (high - low > 1) {
				long middle = low + (high - low) / 2;
				if (_zone.offset(middle) == offset) {
					low = middle;
				} else {
					high = middle;
				}
			}
			return high;
		}
		return end;
	}

	/**
	 * Does what is due up to the given instant (inclusive), each at its own time.
	 */
	public void runTo(long utc) {
		while (true) {
			long next = next();
			if (next > utc) {
				break;
			}
			fire(next);
		}
	}

	private void fire(long utc) {
		_handled = utc;
		int offset = _zone.offset(utc);
		long local = utc + offset;
		boolean tick = Math.floorMod(local, (long) _interval) == 0;
		boolean midnight = _atMidnight && Math.floorMod(local, (long) DAY) == 0
			&& _lastDay != Math.floorDiv(local, (long) DAY);
		if (tick || midnight || offset != _shownOffset) {
			broadcast(utc);
		}
	}

	/**
	 * The smallest instant after <code>after</code> whose local time is a multiple of the period.
	 */
	private long aligned(long after, int period) {
		long result = Long.MAX_VALUE;
		// The zone has at most two offsets in a period: before and after a transition.
		for (int offset : new int[] { _zone.offset(after + 1), _zone.offset(after + period + 1) }) {
			long candidate = (Math.floorDiv(after + offset, (long) period) + 1) * period - offset;
			if (candidate > after && _zone.offset(candidate) == offset) {
				result = Math.min(result, candidate);
			}
		}
		return result == Long.MAX_VALUE ? after + period : result;
	}

	private void broadcast(long utc) {
		int offset = _zone.offset(utc);
		long local = utc + offset;
		long days = Math.floorDiv(local, (long) DAY);
		long rest = local - days * DAY;
		Val.Date date = Values.civilOf(days);
		int hour = (int) (rest / 3600);
		int minute = (int) (rest % 3600 / 60);
		int second = (int) (rest % 60);
		int weekday = Values.weekdayOf(date.year(), date.month(), date.day());
		for (Member member : _members) {
			Val value;
			switch (member.type()) {
				case TIME_OF_DAY:
					value = Val.ofTime(new Val.TimeOfDay(hour, minute, second, weekday));
					break;
				case DATE:
					value = Val.ofDate(date);
					break;
				default:
					int flags = NO_WORKING_DAY | CLOCK_QUALITY | SYNC_RELIABLE
						| (_zone.summerTime(utc) ? SUMMER_TIME : 0);
					value = Val.ofDateTime(
						new DateTime(date.year(), date.month(), date.day(), hour, minute, second, weekday, flags));
					break;
			}
			_output.publish(member, value);
		}
		_shownOffset = offset;
		_lastDay = days;
		_broadcasts++;
	}

	@Override
	public boolean command(Datapoint datapoint, Val value) {
		if (_output != null) {
			_output.refused(datapoint.name(), value);
		}
		return false;
	}

}
