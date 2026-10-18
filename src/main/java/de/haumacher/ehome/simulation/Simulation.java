/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.monitor.BusTelegram.Service;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.Engine.LogCode;
import de.haumacher.ehome.rules.Engine.ParameterStatus;
import de.haumacher.ehome.rules.Engine.RuleStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.PosixZone;
import de.haumacher.ehome.rules.RuleLoader.Refusal;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.RuleSet.Level;
import de.haumacher.ehome.rules.RuleSet.StartReason;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Scheduler;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The rules and schedules of a runtime image run against a KNX bus on a simulated clock: what a
 * HomeControl controller does with the image, without the controller.
 *
 * <ul>
 * <li>The datapoints of the image form a {@link DatapointModel}: the KNX datapoints are bound to
 * the bus through a {@link KnxBinding} (reports from the group telegrams received, commands as
 * telegrams), the outputs of schedule entries belong to the {@link Scheduler}, every other
 * datapoint is a value of the application that every command sets (it lives in the simulation
 * only). The datapoints of the time master exist without a value; Zigbee datapoints refuse their
 * commands.</li>
 * <li>The {@link Engine} and the {@link Scheduler} run on a simulated clock: it starts at a given
 * instant and moves only by {@link #advance(long)} (fast-forward: every timer and every schedule
 * firing on the way at its own time) and {@link #step()} (to the next timer or firing); local time
 * and the sun come from the image's settings (<code>tz</code>, <code>location</code>). The wall
 * clock is never read.</li>
 * <li>Telegrams arrive whenever the bus delivers them and take effect at the simulated time of
 * their arrival. The telegrams of the simulation are sent in order on a thread of their own.</li>
 * <li>The parameter overrides are those of the given {@link ParameterStore}, as a controller's
 * persisted ones at a boot.</li>
 * <li>Everything that happens is written to the {@link #timeline()}: telegrams received and sent,
 * the commands of the rules, the logs of the rules and the scheduler, parameter changes and the
 * problems of the binding.</li>
 * </ul>
 */
public final class Simulation implements AutoCloseable {

	/** The most entries the timeline keeps. */
	public static final int TIMELINE_CAPACITY = 1000;

	/** How long {@link #awaitSent()} and {@link #awaitReceived(long, long)} wait at most, in ms. */
	public static final long BUS_TIMEOUT = 10_000;

	/** The most events processed at one instant before the clock gives up on them. */
	private static final int MAX_EVENTS_AT_ONCE = 10_000;

	private static final String[] WEEKDAYS = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };

	/**
	 * The kind of an entry of the {@link Simulation#timeline()}.
	 */
	public enum Kind {
		/** A group telegram received from the bus. */
		RECEIVED("received"),
		/** A group telegram the simulation sent. */
		SENT("sent"),
		/** A rule commanded a datapoint. */
		COMMAND("command"),
		/** A log entry of a rule (<code>spec/rules.md</code> 9.2). */
		RULE("rule"),
		/** A log line of the scheduler. */
		SCHEDULE("schedule"),
		/** A parameter of a rule changed its value. */
		PARAMETER("parameter"),
		/** The clock moved, the simulation started or stopped. */
		CLOCK("clock"),
		/** A telegram changed nothing, a command was refused, the bus failed. */
		PROBLEM("problem");

		private final String _name;

		Kind(String name) {
			_name = name;
		}

		/** The name of the kind, as the view shows it. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * An entry of the timeline.
	 *
	 * @param ms
	 *        The simulated time since the start, in milliseconds.
	 * @param kind
	 *        What happened.
	 * @param source
	 *        The rule, datapoint, address or schedule entry concerned, <code>-</code> if none.
	 * @param text
	 *        What happened, for a person.
	 */
	public record Entry(long ms, Kind kind, String source, String text) {
		// A value.
	}

	private final long _startUtc;

	private final PosixZone _zone;

	private final GeoLocation _location;

	private final DatapointModel _model;

	private final KnxBinding _binding;

	private final Scheduler _scheduler;

	private final Engine _engine;

	private final Object _rules;

	private final List<Schedule> _schedules;

	private final List<String> _problems = new ArrayList<>();

	private final Map<String, String> _owners = new LinkedHashMap<>();

	private final Deque<Entry> _timeline = new ArrayDeque<>();

	private long _dropped;

	private long _now;

	private long _nextMidnight;

	private boolean _started;

	private boolean _closed;

	private String _refusal;

	private BusLink _link;

	private long _received;

	private final ExecutorService _sender = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "E-Home simulation sender");
		thread.setDaemon(true);
		return thread;
	});

	private final DatapointModel.Technology _notSimulated = (datapoint, value) -> {
		problem(datapoint.name(), "the command for '" + datapoint.name() + "' is refused: its technology ("
			+ _owners.get(datapoint.name()) + ") is not simulated");
		return false;
	};

	/**
	 * Creates a simulation of the given image; nothing runs before {@link #start()}.
	 *
	 * @param image
	 *        The runtime image (its parsed JSON), valid by <code>ImageValidator</code>.
	 * @param startUtc
	 *        The instant the simulated clock starts at, in seconds since the epoch (UTC).
	 * @param store
	 *        The persisted parameter overrides.
	 */
	public Simulation(Map<?, ?> image, long startUtc, ParameterStore store) {
		_startUtc = startUtc;
		Map<?, ?> settings = image.get("settings") instanceof Map<?, ?> map ? map : Map.of();
		_zone = settings.get("tz") instanceof String tz ? PosixZone.parse(tz) : PosixZone.UTC;
		if (settings.get("location") instanceof Map<?, ?> location) {
			_location = new GeoLocation(number(location.get("lat")), number(location.get("lon")),
				number(location.get("elev")));
		} else {
			_location = null;
		}
		_model = new DatapointModel(() -> _now);
		_binding = new KnxBinding(_model, this::send, text -> problem("-", text));
		_scheduler = new Scheduler(new Scheduler.TimeSource() {
			@Override
			public Long nowUtc() {
				return Long.valueOf(utc());
			}

			@Override
			public int utcOffset(long utc) {
				return _zone.offset(utc);
			}

			@Override
			public GeoLocation location() {
				return _location;
			}
		}, new LinkedHashMap<>(), _model, this::schedulerLog);

		List<?> datapoints = image.get("datapoints") instanceof List<?> list ? list : List.of();
		for (Object item : datapoints) {
			Map<?, ?> dp = (Map<?, ?>) item;
			_owners.put((String) dp.get("id"),
				dp.containsKey("knx") ? "the KNX adapter" : dp.containsKey("zigbee") ? "the Zigbee adapter" : null);
		}
		Map<String, ValueType> timeMaster = timeMaster(image);
		for (String id : timeMaster.keySet()) {
			_owners.put(id, "the time master");
		}
		Schedule.Loaded loaded = Schedule.load(image.containsKey("schedules") ? image.get("schedules") : List.of(),
			new Schedule.Datapoints() {
				@Override
				public ValueType type(String name) {
					for (Object item : datapoints) {
						Map<?, ?> dp = (Map<?, ?>) item;
						if (name.equals(dp.get("id"))) {
							return ValueType.fromName((String) dp.get("type"));
						}
					}
					return timeMaster.get(name);
				}

				@Override
				public String owner(String name) {
					return _owners.get(name);
				}
			});
		_schedules = loaded.entries();
		for (Schedule.Refusal refusal : loaded.refusals()) {
			_problems.add(refusal.toString());
		}
		Set<String> outputs = new HashSet<>();
		for (Schedule entry : _schedules) {
			if (entry.output() != null) {
				outputs.add(entry.output());
			}
		}

		List<KnxBinding.Mapping> mappings = new ArrayList<>();
		for (Object item : datapoints) {
			Map<?, ?> dp = (Map<?, ?>) item;
			String id = (String) dp.get("id");
			ValueType type = ValueType.fromName((String) dp.get("type"));
			boolean writable = Boolean.TRUE.equals(dp.get("writable"));
			if (dp.get("knx") instanceof Map<?, ?>) {
				KnxBinding.Mapping mapping = KnxBinding.mapping(id, dp);
				if (KnxValues.fits(mapping.dpt(), type)) {
					_model.add(id, type, writable, _binding);
					mappings.add(mapping);
				} else {
					_owners.put(id, "the KNX adapter, without a coding of " + mapping.dpt().code());
					_model.add(id, type, writable, _notSimulated);
				}
			} else if (dp.containsKey("zigbee")) {
				_model.add(id, type, writable, _notSimulated);
			} else if (outputs.contains(id)) {
				_model.add(id, type, writable, _scheduler);
			} else {
				_model.add(id, type, writable, null);
			}
		}
		for (KnxBinding.Mapping mapping : mappings) {
			_binding.bind(mapping);
		}
		for (Map.Entry<String, ValueType> datapoint : timeMaster.entrySet()) {
			_model.add(datapoint.getKey(), datapoint.getValue(), false, null);
		}

		_engine = new Engine(_model, () -> _now, store, new Engine.TimeSource() {
			@Override
			public DateTime localNow() {
				return localTime(_now);
			}

			@Override
			public TimeOfDay sunTime(SunEvent event) {
				return _scheduler.sunTime(utc(), event);
			}
		});
		_engine.setObserver(new Engine.Observer() {
			@Override
			public void ruleLogged(String rule, LogCode code, Level level, String text) {
				add(Kind.RULE, rule, code.externalName() + " (" + level.externalName() + "): " + text);
			}

			@Override
			public void ruleCommanded(String rule, Datapoint datapoint, Val value) {
				add(Kind.COMMAND, rule, "write " + datapoint.name() + " = " + Values.format(value));
			}

			@Override
			public void parameterChanged(String rule, String parameter, Val value) {
				add(Kind.PARAMETER, rule, parameter + " = " + Values.format(value));
			}
		});
		_scheduler.addListener(_engine::scheduleFired);
		_rules = image.containsKey("rules") ? image.get("rules") : List.of();
	}

	private static Map<String, ValueType> timeMaster(Map<?, ?> image) {
		Map<String, ValueType> result = new LinkedHashMap<>();
		if (image.get("knx") instanceof Map<?, ?> knx && knx.get("timeMaster") instanceof Map<?, ?> master) {
			if (master.containsKey("time")) {
				result.put("knx.time", ValueType.TIME_OF_DAY);
			}
			if (master.containsKey("date")) {
				result.put("knx.date", ValueType.DATE);
			}
			if (master.containsKey("dateTime")) {
				result.put("knx.datetime", ValueType.DATE_TIME);
			}
		}
		return result;
	}

	private static double number(Object value) {
		return value instanceof Number number ? number.doubleValue() : 0;
	}

	// ---- Life cycle --------------------------------------------------------------------------

	/**
	 * Connects the simulation to the bus: the telegrams of the link are received from now on, the
	 * simulation's telegrams are sent through it.
	 */
	public synchronized void connect(BusLink link) {
		_link = link;
		link.setListener(new BusLink.Listener() {
			@Override
			public void received(BusTelegram telegram) {
				Simulation.this.received(telegram);
			}

			@Override
			public void closed(String reason) {
				linkClosed(reason);
			}
		});
	}

	/**
	 * Loads the schedules and the rules and starts them (a boot); the datapoints read on start are
	 * read.
	 *
	 * @return <code>null</code>, or why the image is refused (the simulation does not run then).
	 */
	public synchronized String start() {
		if (_started) {
			throw new IllegalStateException("The simulation runs already.");
		}
		if (!_problems.isEmpty()) {
			return refuse("schedules refused: " + String.join("; ", _problems));
		}
		try {
			_scheduler.load(_schedules);
		} catch (Scheduler.RefusedException ex) {
			return refuse("schedules refused: " + ex.getMessage());
		}
		Set<String> ids = new HashSet<>();
		for (Schedule entry : _schedules) {
			ids.add(entry.id());
		}
		Refusal refusal = _engine.load(_rules, ids::contains);
		if (refusal != null) {
			return refuse("rules refused: " + refusal);
		}
		_started = true;
		_nextMidnight = nextMidnight(_now);
		add(Kind.CLOCK, "-", "started at " + timeText(_now));
		_engine.start(StartReason.BOOT);
		_binding.startReads();
		settle();
		fireDue();
		return null;
	}

	private String refuse(String text) {
		_refusal = text;
		add(Kind.PROBLEM, "-", text);
		return text;
	}

	/**
	 * Why the image was refused, <code>null</code> if it was not.
	 */
	public synchronized String refusal() {
		return _refusal;
	}

	/**
	 * Whether the rules run.
	 */
	public synchronized boolean isStarted() {
		return _started && !_closed;
	}

	/**
	 * Stops the simulation: the rules stop, the link is closed.
	 */
	@Override
	public void close() {
		BusLink link;
		synchronized (this) {
			if (_closed) {
				return;
			}
			_closed = true;
			_engine.close();
			link = _link;
			_link = null;
			if (_started) {
				add(Kind.CLOCK, "-", "stopped at " + timeText(_now));
			}
			notifyAll();
		}
		_sender.shutdown();
		try {
			_sender.awaitTermination(BUS_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		if (link != null) {
			link.close();
		}
	}

	// ---- Clock -------------------------------------------------------------------------------

	/**
	 * The simulated time since the start, in milliseconds.
	 */
	public synchronized long now() {
		return _now;
	}

	/**
	 * The simulated instant in seconds since the epoch (UTC).
	 */
	public synchronized long utc() {
		return _startUtc + Math.floorDiv(_now, 1000L);
	}

	/**
	 * Runs the simulated clock forward by the given time: the timers of the rules and the firings of
	 * the schedules on the way each at its own time.
	 */
	public synchronized void advance(long ms) {
		if (ms < 0) {
			throw new IllegalArgumentException("The clock does not run backwards: " + ms + " ms.");
		}
		checkRunning();
		long target = _now + ms;
		long from = _now;
		runTo(target);
		add(Kind.CLOCK, "-", "fast-forward by " + Values.durationText(ms) + " from " + timeText(from));
	}

	/**
	 * Runs the simulated clock forward to the next timer of a rule, firing of a schedule or local
	 * midnight, and processes what is due then.
	 *
	 * @param limit
	 *        The most to run forward, in milliseconds.
	 * @return The time run forward, in milliseconds.
	 */
	public synchronized long step(long limit) {
		checkRunning();
		long next = nextEvent();
		long target = next == Long.MAX_VALUE ? _now + limit : Math.min(next, _now + limit);
		long from = _now;
		runTo(target);
		add(Kind.CLOCK, "-", "step by " + Values.durationText(target - from) + " from " + timeText(from));
		return target - from;
	}

	/**
	 * The next instant (simulated ms) at which a timer of a rule expires, a schedule fires or local
	 * midnight comes, {@link Long#MAX_VALUE} if none.
	 */
	public synchronized long nextEvent() {
		long next = Math.min(_engine.nextDeadline(), _nextMidnight);
		Long wakeup = _scheduler.wakeup();
		if (wakeup != null) {
			next = Math.min(next, Math.max(_now, (wakeup.longValue() - _startUtc) * 1000));
		}
		return next;
	}

	private void runTo(long target) {
		int atOnce = 0;
		long last = _now;
		while (true) {
			settle();
			long next = nextEvent();
			if (next > target) {
				break;
			}
			_now = Math.max(_now, next);
			if (_now == last) {
				if (++atOnce > MAX_EVENTS_AT_ONCE) {
					problem("-", "more than " + MAX_EVENTS_AT_ONCE + " events at " + timeText(_now) + "; the clock moves on");
					break;
				}
			} else {
				atOnce = 0;
				last = _now;
			}
			fireDue();
		}
		_now = Math.max(_now, target);
		fireDue();
	}

	private void fireDue() {
		_engine.processDueTimers();
		if (_now >= _nextMidnight) {
			_nextMidnight = nextMidnight(_now);
			_scheduler.evaluate();
		}
		Long wakeup = _scheduler.wakeup();
		if (wakeup != null && wakeup.longValue() <= utc()) {
			_scheduler.fireDue();
		}
		settle();
	}

	private void settle() {
		while (_engine.hasPending() || _binding.hasSent()) {
			_engine.drain();
			_binding.deliverSent();
		}
		_engine.drain();
	}

	private long nextMidnight(long ms) {
		long utc = _startUtc + Math.floorDiv(ms, 1000L);
		long local = utc + _zone.offset(utc);
		long midnightLocal = (Math.floorDiv(local, 86400L) + 1) * 86400L;
		// The offset at the midnight itself (a change of the offset is never at midnight in practice).
		long midnightUtc = midnightLocal - _zone.offset(midnightLocal - _zone.offset(utc));
		return (midnightUtc - _startUtc) * 1000;
	}

	/**
	 * The local time at the simulated time, with its day of the week.
	 */
	public synchronized DateTime localTime(long ms) {
		long utc = _startUtc + Math.floorDiv(ms, 1000L);
		long s = utc + _zone.offset(utc);
		long days = Math.floorDiv(s, 86400L);
		long rest = s - days * 86400;
		Val.Date date = Values.civilOf(days);
		return new DateTime(date.year(), date.month(), date.day(), (int) (rest / 3600), (int) (rest % 3600 / 60),
			(int) (rest % 60), Values.weekdayOf(date.year(), date.month(), date.day()), 0);
	}

	/**
	 * The local time at the simulated time as a text, e.g. <code>Mon 2026-10-19 06:30:00.000</code>.
	 */
	public synchronized String timeText(long ms) {
		DateTime t = localTime(ms);
		return String.format(Locale.ROOT, "%s %04d-%02d-%02d %02d:%02d:%02d.%03d", WEEKDAYS[t.dayOfWeek() - 1], t.year(),
			t.month(), t.day(), t.hour(), t.minute(), t.second(), Math.floorMod(ms, 1000L));
	}

	private void checkRunning() {
		if (!_started || _closed) {
			throw new IllegalStateException("The simulation does not run.");
		}
	}

	// ---- Bus ---------------------------------------------------------------------------------

	private void received(BusTelegram telegram) {
		synchronized (this) {
			if (_closed) {
				return;
			}
			add(Kind.RECEIVED, RuntimeImage.threeLevel(telegram.destination()),
				telegram.service().classifier() + " " + telegram.hex() + " from " + telegram.sourceText()
					+ datapointsText(telegram.destination()));
			_binding.received(telegram.source(), telegram.destination(), telegram.service(), telegram.data());
			settle();
			_received++;
			notifyAll();
		}
	}

	private String datapointsText(int address) {
		List<String> names = new ArrayList<>();
		for (KnxBinding.Mapping mapping : _binding.mappings()) {
			if (mapping.addresses().contains(Integer.valueOf(address))) {
				names.add(mapping.datapoint());
			}
		}
		return names.isEmpty() ? "" : " (" + String.join(", ", names) + ")";
	}

	private synchronized void linkClosed(String reason) {
		if (_closed) {
			return;
		}
		problem("-", "the bus link was closed: " + reason);
		_link = null;
		_binding.linkLost();
		settle();
	}

	private void send(int destination, byte[] apdu) {
		BusLink link = _link;
		String address = RuntimeImage.threeLevel(destination);
		Service service = BusTelegram.Service.ofCode(apdu[1] & 0xC0);
		String data = apdu.length > 2 ? HexFormat.ofDelimiter(" ").withUpperCase().formatHex(apdu, 2, apdu.length)
			: service == Service.READ ? "" : String.format("%02X", apdu[1] & 0x3F);
		if (link == null) {
			problem(address, "not sent (no bus): " + service.classifier() + " " + data);
			return;
		}
		add(Kind.SENT, address, (service.classifier() + " " + data).trim() + datapointsText(destination));
		try {
			_sender.execute(() -> {
				try {
					link.send(destination, apdu);
				} catch (BusException ex) {
					synchronized (Simulation.this) {
						problem(address, "sending failed: " + ex.getMessage());
					}
				}
			});
		} catch (RejectedExecutionException ex) {
			problem(address, "not sent, the simulation stops: " + service.classifier() + " " + data);
		}
	}

	/**
	 * Waits until the telegrams sent so far have left.
	 */
	public void awaitSent() throws InterruptedException {
		try {
			_sender.submit(() -> {
				// Nothing: done when everything before is.
			}).get(BUS_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException ex) {
			// Stopped.
		} catch (ExecutionException | TimeoutException ex) {
			throw new IllegalStateException("The telegrams of the simulation were not sent.", ex);
		}
	}

	/**
	 * The number of group telegrams received and processed.
	 */
	public synchronized long received() {
		return _received;
	}

	/**
	 * Waits until the given number of telegrams was received in all.
	 *
	 * @return Whether it was before the timeout.
	 */
	public synchronized boolean awaitReceived(long count, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (_received < count && !_closed) {
			long rest = deadline - System.currentTimeMillis();
			if (rest <= 0) {
				return false;
			}
			wait(rest);
		}
		return _received >= count;
	}

	// ---- Commands from outside ----------------------------------------------------------------

	/**
	 * Commands a datapoint as the UI or the API of the controller does.
	 */
	public synchronized CommandStatus command(String datapoint, Val value) {
		checkRunning();
		CommandStatus status = _model.command(datapoint, value);
		add(status == CommandStatus.OK ? Kind.COMMAND : Kind.PROBLEM, "-",
			"write " + datapoint + " = " + Values.format(value) + (status == CommandStatus.OK ? "" : ": " + status.text()));
		settle();
		return status;
	}

	/**
	 * Sets a parameter as the UI or the API of the controller does (<code>spec/rules.md</code> 8.2).
	 */
	public synchronized ParameterStatus setParameter(String rule, String parameter, Val value) {
		checkRunning();
		ParameterStatus status = _engine.setParameter(rule, parameter, value);
		settle();
		return status;
	}

	/**
	 * Removes the override of a parameter.
	 */
	public synchronized ParameterStatus resetParameter(String rule, String parameter) {
		checkRunning();
		ParameterStatus status = _engine.resetParameter(rule, parameter);
		settle();
		return status;
	}

	// ---- State -------------------------------------------------------------------------------

	/**
	 * The datapoint model.
	 */
	public DatapointModel model() {
		return _model;
	}

	/**
	 * The KNX binding of the datapoints.
	 */
	public KnxBinding binding() {
		return _binding;
	}

	/**
	 * What owns a datapoint (<code>the KNX adapter</code>, <code>the time master</code>, ...),
	 * <code>null</code> for a value of the application or the output of a schedule.
	 */
	public synchronized String owner(String datapoint) {
		return _owners.get(datapoint);
	}

	/**
	 * The current value of a datapoint, no value while it is unknown.
	 */
	public synchronized Val value(String datapoint) {
		Datapoint dp = _model.get(datapoint);
		if (dp == null) {
			throw new IllegalArgumentException("No datapoint '" + datapoint + "'.");
		}
		return dp.quality() == Quality.UNKNOWN ? Val.none(dp.type().expressionType()) : dp.value();
	}

	/**
	 * The loaded rules, <code>null</code> before the start.
	 */
	public synchronized RuleSet rules() {
		return _engine.rules();
	}

	/**
	 * The engine (for its diagnostics; call it only holding the simulation's lock).
	 */
	public Engine engine() {
		return _engine;
	}

	/**
	 * The diagnostics of the rule with the given id.
	 */
	public synchronized RuleStatus status(String rule) {
		return _engine.status(ruleIndex(rule));
	}

	/**
	 * The current state of the rule, <code>null</code> without states.
	 */
	public synchronized String state(String rule) {
		return _engine.currentState(ruleIndex(rule));
	}

	/**
	 * The remaining time of a timer of a rule, <code>null</code> when it is stopped.
	 */
	public synchronized Long timerRemaining(String rule, String timer) {
		int r = ruleIndex(rule);
		int t = _engine.rules().rules().get(r).findTimer(timer);
		if (t < 0) {
			throw new IllegalArgumentException("No timer '" + timer + "' in rule '" + rule + "'.");
		}
		return _engine.timerRemaining(r, t);
	}

	/**
	 * The current value of a parameter of a rule.
	 */
	public synchronized Val parameter(String rule, String parameter) {
		int r = ruleIndex(rule);
		int p = _engine.rules().rules().get(r).findParameter(parameter);
		if (p < 0) {
			throw new IllegalArgumentException("No parameter '" + parameter + "' in rule '" + rule + "'.");
		}
		return _engine.parameterValue(r, p);
	}

	private int ruleIndex(String rule) {
		RuleSet rules = _engine.rules();
		int r = rules == null ? -1 : rules.findRule(rule);
		if (r < 0) {
			throw new IllegalArgumentException("No rule '" + rule + "'.");
		}
		return r;
	}

	/**
	 * The timeline, oldest first (at most {@link #TIMELINE_CAPACITY} entries).
	 */
	public synchronized List<Entry> timeline() {
		return List.copyOf(_timeline);
	}

	/**
	 * The number of entries dropped from the start of the timeline.
	 */
	public synchronized long dropped() {
		return _dropped;
	}

	private void add(Kind kind, String source, String text) {
		_timeline.add(new Entry(_now, kind, source, text));
		while (_timeline.size() > TIMELINE_CAPACITY) {
			_timeline.poll();
			_dropped++;
		}
	}

	private void problem(String source, String text) {
		add(Kind.PROBLEM, source, text);
	}

	private void schedulerLog(String line) {
		String text = line.length() > 2 && line.charAt(1) == ' ' ? line.substring(2) : line;
		String source = "-";
		if (text.startsWith("schedule ")) {
			int end = text.indexOf(' ', "schedule ".length());
			source = end < 0 ? text.substring("schedule ".length()) : text.substring("schedule ".length(), end);
		}
		add(line.startsWith("I ") ? Kind.SCHEDULE : Kind.PROBLEM, source, text);
	}

	/**
	 * The datapoint type of the binding of a datapoint, <code>null</code> if it is not bound to the
	 * bus.
	 */
	public synchronized Dpt dpt(String datapoint) {
		KnxBinding.Mapping mapping = _binding.mapping(datapoint);
		return mapping == null ? null : mapping.dpt();
	}

}
