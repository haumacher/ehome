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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.monitor.BusTelegram.Service;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.blocks.BlockRegistry;
import de.haumacher.ehome.blocks.BlockRuntime;
import de.haumacher.ehome.blocks.BlockSet;
import de.haumacher.ehome.rules.Astro;
import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.Astro.SunPosition;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.Engine.LogCode;
import de.haumacher.ehome.rules.Engine.ParameterStatus;
import de.haumacher.ehome.rules.Engine.RuleStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.RuleLoader;
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
 * only). The datapoints of the time master belong to the {@link TimeMaster}, which broadcasts the
 * simulated time on their addresses. The Zigbee datapoints belong to the {@link VirtualZigbee virtual
 * Zigbee devices}: the user makes a device report a value ({@link #zigbeeReport(String, Val)}), a
 * command to a function that takes one is reported back by the device after the cascade, a rule's
 * read is answered with the device's value.</li>
 * <li>The {@link Engine} and the {@link Scheduler} run on a simulated clock: it starts at a given
 * instant and moves by {@link #advance(long)} (fast-forward: every timer and every schedule
 * firing on the way at its own time), {@link #step(long)} (to the next timer or firing) and, while it
 * runs in real time ({@link #run(int)}, at a speed of 1, 10 or 60 times the wall clock, until
 * {@link #pause()}), by {@link #tick()} on a thread of its own; local time and the sun come from
 * the image's settings (<code>tz</code>, <code>location</code>). The wall clock is read only to run
 * in real time.</li>
 * <li>Telegrams arrive whenever the bus delivers them and take effect at the simulated time of
 * their arrival. The telegrams of the simulation are sent in order on a thread of their own.</li>
 * <li>The parameter overrides are those of the given {@link ParameterStore}, as a controller's
 * persisted ones at a boot.</li>
 * <li>Everything that happens is written to the {@link #timeline()}: telegrams received and sent,
 * the reports, button presses and commands of the Zigbee devices, the commands of the rules, the logs
 * of the rules and the scheduler, parameter changes and the problems of the bindings.</li>
 * <li>The {@link #setListener(Runnable) listener} is told about every change: an entry of the
 * timeline, a telegram processed, the clock moved. It is called holding the simulation's lock and
 * must only take note.</li>
 * </ul>
 */
public final class Simulation implements AutoCloseable {

	/** The most entries the timeline keeps. */
	public static final int TIMELINE_CAPACITY = 1000;

	/** How long {@link #awaitSent()} and {@link #awaitReceived(long, long)} wait at most, in ms. */
	public static final long BUS_TIMEOUT = 10_000;

	/** How often the clock moves while it runs in real time, in milliseconds of the wall clock. */
	public static final long TICK = 100;

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
		/**
		 * A Zigbee device reported the value of an attribute, also as the answer to a read (the
		 * controller's monitor event <code>zigbee</code> <code>report</code>).
		 */
		ZIGBEE_REPORT("zigbee report"),
		/**
		 * A Zigbee device sent the command of a button: an <code>action</code> (the monitor event
		 * <code>zigbee</code> <code>action</code>).
		 */
		ZIGBEE_ACTION("zigbee action"),
		/** A command was sent to a Zigbee device (the monitor event <code>zigbee</code> <code>command</code>). */
		ZIGBEE_COMMAND("zigbee command"),
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

	private final VirtualZigbee _zigbee;

	private final Scheduler _scheduler;

	private final Engine _engine;

	private final Object _rules;

	/** The <code>blocks</code> of the image (homecontrol spec/blocks.md 4), <code>null</code> for none. */
	private final Object _blocks;

	/** The store of the parameter overrides, also the blocks'. */
	private final ParameterStore _store;

	/** The state records of the blocks, kept across runs of the project's simulation. */
	private BlockRuntime.RecordStore _records = new BlockRuntime.MemoryRecords();

	/** The block runtime, <code>null</code> before {@link #start()}. */
	private BlockRuntime _blockRuntime;

	private final List<Schedule> _schedules;

	private final TimeMaster _timeMaster;

	private final LongSupplier _wall;

	/** The speed of the clock in real time, 0 while it does not run by itself. */
	private int _speed;

	/** The wall clock and the simulated time when the clock last started or was moved by hand. */
	private long _baseWall;

	private long _baseNow;

	private ScheduledExecutorService _ticker;

	private Runnable _listener;

	private final List<String> _problems = new ArrayList<>();

	private final Map<String, String> _owners = new LinkedHashMap<>();

	/** The outputs of the schedule entries: the scheduler owns them. */
	private final Set<String> _outputs = new HashSet<>();

	/** The datapoints bound to an external source of the image (spec/external-values.md). */
	private final Set<String> _external = new HashSet<>();

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
		this(image, startUtc, store, System::currentTimeMillis);
	}

	/**
	 * Creates a simulation of the given image with the given wall clock (read only while the
	 * simulated clock runs in real time); nothing runs before {@link #start()}.
	 *
	 * @param wall
	 *        The wall clock, in milliseconds.
	 */
	public Simulation(Map<?, ?> image, long startUtc, ParameterStore store, LongSupplier wall) {
		_startUtc = startUtc;
		_wall = wall;
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
		// Not connected until connect(): the rules' reads are refused (readRefused) without a bus.
		_binding.setConnected(false);
		_zigbee = new VirtualZigbee(_model, this::add);
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
		_timeMaster = TimeMaster.of(image, _zone);
		Map<String, ValueType> timeMaster = new LinkedHashMap<>();
		if (_timeMaster != null) {
			for (TimeMaster.Member member : _timeMaster.members()) {
				timeMaster.put(member.datapoint(), member.type());
				_owners.put(member.datapoint(), "the time master");
			}
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
			} else if (dp.get("zigbee") instanceof Map<?, ?>) {
				VirtualZigbee.Point point = VirtualZigbee.point(id, dp);
				if (point != null) {
					_model.add(id, type, writable, _zigbee);
					_zigbee.bind(point);
				} else {
					_owners.put(id, "the Zigbee adapter, without a function of the binding");
					_model.add(id, type, writable, _notSimulated);
				}
			} else if (outputs.contains(id)) {
				_model.add(id, type, writable, _scheduler);
			} else {
				_model.add(id, type, writable, null);
			}
		}
		for (KnxBinding.Mapping mapping : mappings) {
			_binding.bind(mapping);
		}
		if (_timeMaster != null) {
			for (TimeMaster.Member member : _timeMaster.members()) {
				_model.add(member.datapoint(), member.type(), false, _timeMaster);
				_binding.bindPublisher(new KnxBinding.Mapping(member.datapoint(), member.dpt(), member.address(),
					List.of(), true, true, false));
			}
		}
		// The datapoints of the rule parameters (spec/rules.md 8.6), after the time master's: the rule
		// engine binds them when it loads the rules.
		for (RuleLoader.ParameterDatapoint datapoint : RuleLoader.parameterDatapoints(image.get("rules"))) {
			_model.add(datapoint.name(), datapoint.type(), datapoint.writable(), null);
			_owners.put(datapoint.name(), "the rule engine");
		}
		// The time datapoints of the schedule entries with an adjustable time (spec/schedules.md 2.2),
		// after the rule parameters': the scheduler binds them when it loads the entries; a command to
		// one is a change of the entry's time, an override for the rest of the run.
		for (String id : Schedule.timeDatapointIds(image.get("schedules"))) {
			String name = Schedule.timeDatapointName(id);
			_model.add(name, ValueType.TIME_OF_DAY, true, null);
			_owners.put(name, "the scheduler");
		}
		// The datapoints of the blocks (spec/blocks.md 6), after the schedules': the block runtime binds
		// them when it adopts the blocks.
		for (BlockSet.ImpliedDatapoint datapoint : BlockSet.impliedDatapoints(image.get("blocks"), BlockRegistry.builtins())) {
			_model.add(datapoint.name(), datapoint.type(), datapoint.writable(), null);
			_owners.put(datapoint.name(), "the block runtime");
		}
		_scheduler.deferTimeReports(true);
		_outputs.addAll(outputs);
		if (image.get("external") instanceof Map<?, ?> external && external.get("sources") instanceof List<?> sources) {
			for (Object source : sources) {
				if (source instanceof Map<?, ?> map && map.get("bindings") instanceof List<?> bindings) {
					for (Object binding : bindings) {
						if (binding instanceof Map<?, ?> b && b.get("datapoint") instanceof String datapoint) {
							_external.add(datapoint);
						}
					}
				}
			}
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

			@Override
			public long localToUtc(DateTime local) {
				return _zone.utc(local);
			}

			@Override
			public DateTime utcToLocal(long utc) {
				return _zone.local(utc);
			}

			@Override
			public SunPosition sunPosition(long utc) {
				return _location == null || !_location.valid() ? null : Astro.sunPosition(utc, _location);
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

			@Override
			public void ruleRead(String rule, Datapoint datapoint) {
				add(Kind.COMMAND, rule, "read " + datapoint.name());
			}
		});
		_scheduler.addListener(_engine::scheduleFired);
		_rules = image.containsKey("rules") ? image.get("rules") : List.of();
		_blocks = image.get("blocks");
		_store = store;
	}

	/**
	 * Sets where the blocks' state records are kept (across runs of a project's simulation); call
	 * before {@link #start()}.
	 */
	public synchronized void setRecords(BlockRuntime.RecordStore records) {
		_records = records;
	}

	/** The block runtime, <code>null</code> before {@link #start()}. */
	public synchronized BlockRuntime blocks() {
		return _blockRuntime;
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
		_binding.setConnected(true);
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
		_scheduler.setQuietReports(_engine::quietly);
		try {
			_scheduler.load(_schedules);
		} catch (Scheduler.RefusedException ex) {
			return refuse("schedules refused: " + ex.getMessage());
		}
		Set<String> ids = new HashSet<>();
		for (Schedule entry : _schedules) {
			ids.add(entry.id());
		}
		Refusal refusal = _engine.load(_rules, new RuleLoader.ScheduleDirectory() {
			@Override
			public boolean hasSchedule(String id) {
				return ids.contains(id);
			}

			@Override
			public boolean ownsOutput(String datapoint) {
				return _outputs.contains(datapoint);
			}

			@Override
			public boolean technologyReads(String datapoint) {
				String owner = _owners.get(datapoint);
				return owner != null && (owner.startsWith("the KNX adapter") || owner.equals("the Zigbee adapter"))
					|| owner == null && _external.contains(datapoint);
			}
		});
		if (refusal != null) {
			return refuse("rules refused: " + refusal);
		}
		// The blocks after the rules (spec/blocks.md 11), called inside the rules' cascades.
		BlockSet set;
		try {
			set = BlockSet.load(_blocks, _model, datapoint -> {
				if (_outputs.contains(datapoint)) {
					return "a schedule entry";
				}
				String owner = _owners.get(datapoint);
				return owner == null || owner.startsWith("the KNX adapter") || owner.equals("the Zigbee adapter") ? null
					: owner;
			}, BlockRegistry.builtins());
		} catch (BlockSet.LoadException ex) {
			return refuse("blocks refused: " + ex.getRefusal());
		}
		_blockRuntime = new BlockRuntime(_model, () -> _now, _store, _records, _engine);
		_blockRuntime.setObserver(new BlockRuntime.Observer() {
			@Override
			public void blockLogged(String block, BlockRuntime.LogCode code, Level level, String text) {
				add(Kind.RULE, "block " + block, code.externalName() + " (" + level.externalName() + "): " + text);
			}

			@Override
			public void blockCommanded(String block, Datapoint datapoint, Val value) {
				add(Kind.COMMAND, "block " + block, "write " + datapoint.name() + " = " + Values.format(value));
			}
		});
		_blockRuntime.adopt(set);
		// Taking the schedules' times at the load is no event for the rules (the outputs' first
		// values neither: setQuietReports above, homecontrol #67).
		_engine.quietly(_scheduler::publishTimes);
		_started = true;
		_nextMidnight = nextMidnight(_now);
		add(Kind.CLOCK, "-", "started at " + timeText(_now));
		_engine.start(StartReason.BOOT);
		_binding.startReads();
		_zigbee.startReports();
		if (_timeMaster != null) {
			_timeMaster.start(utc(), new TimeMaster.Output() {
				@Override
				public void publish(TimeMaster.Member member, Val value) {
					_model.report(member.datapoint(), value);
					_binding.publish(member.datapoint());
				}

				@Override
				public void refused(String datapoint, Val value) {
					problem(datapoint, "refusing the command " + Values.format(value) + " for '" + datapoint
						+ "': the time master owns it");
				}
			});
		}
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
			_speed = 0;
			if (_ticker != null) {
				_ticker.shutdownNow();
			}
			if (_blockRuntime != null) {
				// An orderly stop: the due state records are written.
				_blockRuntime.flush();
			}
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
		rebase();
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
		rebase();
		add(Kind.CLOCK, "-", "step by " + Values.durationText(target - from) + " from " + timeText(from));
		return target - from;
	}

	/**
	 * Lets the clock run by itself at the given multiple of the wall clock (1: real time) until
	 * {@link #pause()}; every timer and firing on the way at its own time, as with
	 * {@link #advance(long)}. A clock running already changes its speed.
	 *
	 * @param speed
	 *        The simulated milliseconds per millisecond of the wall clock, at least 1.
	 */
	public synchronized void run(int speed) {
		if (speed < 1) {
			throw new IllegalArgumentException("The speed of the clock must be at least 1: " + speed);
		}
		checkRunning();
		tick();
		_speed = speed;
		_baseWall = _wall.getAsLong();
		_baseNow = _now;
		if (_ticker == null) {
			_ticker = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread thread = new Thread(r, "E-Home simulation clock");
				thread.setDaemon(true);
				return thread;
			});
			_ticker.scheduleWithFixedDelay(this::tickSafely, TICK, TICK, TimeUnit.MILLISECONDS);
		}
		add(Kind.CLOCK, "-", (speed == 1 ? "running in real time" : "running at " + speed + " times real time")
			+ " from " + timeText(_now));
	}

	/**
	 * Stops the clock running by itself; it stays where it is.
	 */
	public synchronized void pause() {
		checkRunning();
		if (_speed == 0) {
			return;
		}
		tick();
		_speed = 0;
		add(Kind.CLOCK, "-", "paused at " + timeText(_now));
	}

	/**
	 * The speed of the clock running by itself (simulated milliseconds per millisecond of the wall
	 * clock), 0 while it does not.
	 */
	public synchronized int speed() {
		return _speed;
	}

	/**
	 * Moves the clock running by itself to where the wall clock says (called every {@link #TICK} ms
	 * while it runs); nothing while it does not run.
	 */
	public synchronized void tick() {
		if (_speed == 0 || !_started || _closed) {
			return;
		}
		long target = _baseNow + (_wall.getAsLong() - _baseWall) * _speed;
		if (target > _now) {
			runTo(target);
			changed();
		}
	}

	private void tickSafely() {
		try {
			tick();
		} catch (RuntimeException ex) {
			synchronized (this) {
				_speed = 0;
				problem("-", "the clock stopped: " + ex);
			}
		}
	}

	/**
	 * After the clock was moved by hand, the clock running by itself goes on from there.
	 */
	private void rebase() {
		_baseWall = _wall.getAsLong();
		_baseNow = _now;
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

	/**
	 * The next instant at which something is due: an event ({@link #nextEvent()}) or a broadcast of
	 * the time master.
	 */
	private long nextDue() {
		long next = nextEvent();
		if (_timeMaster != null) {
			next = Math.min(next, Math.max(_now, (_timeMaster.next() - _startUtc) * 1000));
		}
		return next;
	}

	private void runTo(long target) {
		int atOnce = 0;
		long last = _now;
		while (true) {
			settle();
			long next = nextDue();
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
		if (_timeMaster != null) {
			_timeMaster.runTo(utc());
			settle();
		}
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
		while (_engine.hasPending() || _binding.hasSent() || _zigbee.hasPending()) {
			_engine.drain();
			_binding.deliverSent();
			_zigbee.deliver();
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
		return _zone.local(_startUtc + Math.floorDiv(ms, 1000L));
	}

	/**
	 * Sets the language the labels of the rules are resolved in (<code>spec/rules.md</code> 2.2: the
	 * engineer's language, as a controller resolves them in its <code>ui.lang</code>); before
	 * {@link #start()}.
	 */
	public synchronized void setLanguage(String language) {
		_engine.setLanguage(language);
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
			changed();
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
		_binding.setConnected(false);
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
	 * The virtual Zigbee device of the datapoint reports the value (a reading, a state, a press of a
	 * button as an <code>action</code>): coded and decoded as the device and the controller do
	 * ({@link VirtualZigbee#normalize(String, Val)}), an update of the datapoint at the simulated time,
	 * also for the value it has already. Before {@link #start()} the device only takes the value and
	 * reports it when the controller reads it at the start.
	 *
	 * @return <code>null</code>, or why the value is refused (also written to the timeline).
	 */
	public synchronized String zigbeeReport(String datapoint, Val value) {
		if (_closed) {
			throw new IllegalStateException("The simulation does not run.");
		}
		String refusal = _zigbee.report(datapoint, value, _started);
		if (refusal != null) {
			VirtualZigbee.Point point = _zigbee.point(datapoint);
			problem(point == null ? datapoint : point.source(),
				"report " + Values.format(value) + " of '" + datapoint + "' refused: " + refusal);
			return refusal;
		}
		if (_started) {
			settle();
		}
		return null;
	}

	/**
	 * Changes adjustable members of a schedule entry as the controller's UI does (homecontrol #58):
	 * the inhabitant's override for the rest of the run.
	 *
	 * @param values
	 *        The scalars of the members ({@link Schedule.Member}).
	 */
	public synchronized Scheduler.Change setSchedule(String id, Map<Schedule.Member, Long> values) {
		checkRunning();
		Scheduler.Change result = _scheduler.setMembers(id, values);
		settle();
		return result;
	}

	/**
	 * The scheduler of the simulation.
	 */
	public Scheduler scheduler() {
		return _scheduler;
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
	 * The virtual Zigbee devices (call it only holding the simulation's lock).
	 */
	public VirtualZigbee zigbee() {
		return _zigbee;
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
		changed();
	}

	/**
	 * Sets the listener told about every change (an entry of the timeline, a telegram processed, the
	 * clock moved); <code>null</code> for none. It is called holding the lock of the simulation and
	 * must only take note.
	 */
	public synchronized void setListener(Runnable listener) {
		_listener = listener;
	}

	private void changed() {
		Runnable listener = _listener;
		if (listener != null) {
			listener.run();
		}
	}

	/**
	 * Writes a problem to the timeline, e.g. one of the installation the simulation runs against.
	 *
	 * @param source
	 *        What it concerns, <code>-</code> for nothing in particular.
	 */
	public synchronized void reportProblem(String source, String text) {
		problem(source, text);
	}

	/**
	 * The time master of the image, <code>null</code> if it has none.
	 */
	public TimeMaster timeMaster() {
		return _timeMaster;
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
