/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.rules;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.blocks.BlockRuntime;
import de.haumacher.ehome.blocks.BlockRuntime.Check;
import de.haumacher.ehome.blocks.BlockRuntime.ChangeStatus;
import de.haumacher.ehome.blocks.BlockRuntime.StateRecord;
import de.haumacher.ehome.blocks.BlockSet;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.rules.Astro;
import de.haumacher.ehome.rules.Astro.GeoLocation;
import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.Astro.SunPosition;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.Engine.LogCode;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.PosixZone;
import de.haumacher.ehome.rules.RuleLoader;
import de.haumacher.ehome.rules.RuleLoader.ParameterDatapoint;
import de.haumacher.ehome.rules.RuleLoader.Refusal;
import de.haumacher.ehome.rules.RuleLoader.ScheduleDirectory;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.RuleSet.Level;
import de.haumacher.ehome.rules.RuleSet.StartReason;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Schedule.Member;
import de.haumacher.ehome.rules.Scheduler;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import test.de.haumacher.ehome.blocks.ConformanceBlock;

/**
 * The rule conformance corpus of HomeControl (<code>spec/fixtures/rules/</code>, copied to
 * <code>src/test/fixtures/homecontrol/rules/</code>, format in its <code>README.md</code>) run
 * against E-Home's reference interpreter {@link Engine}, as the controller's
 * <code>core/test/rules_corpus_test.cpp</code> runs it against the C++ engine: one test per case,
 * reporting every difference of a case at once. A further test checks that the copy holds exactly
 * the cases of the corpus' own index (the table "Cases" of its README).
 */
public class TestRuleCorpus extends TestCase {

	/** The directory of the corpus copy. */
	public static final File CORPUS = new File("src/test/fixtures/homecontrol/rules");

	/**
	 * Cases of the corpus its index (the table "Cases" of its README) does not list (a defect of the
	 * corpus to be reported at haumacher/homecontrol; case 54 came without its line at 8d90149, fixed
	 * at 1a317be).
	 */
	private static final Set<String> NOT_INDEXED = Set.of();

	/** The monotonic time of the corpus' time 0. */
	private static final long BASE = 1000000;

	private final File _file;

	/**
	 * Creates a {@link TestRuleCorpus} for a case, or for the index check with <code>null</code>.
	 */
	public TestRuleCorpus(File file) {
		super(file == null ? "index" : file.getName());
		_file = file;
	}

	@Override
	protected void runTest() throws Throwable {
		if (_file == null) {
			checkIndex();
			return;
		}
		Map<?, ?> json = (Map<?, ?>) Json.parse(Files.readString(_file.toPath(), StandardCharsets.UTF_8), 100);
		CaseRun run = new CaseRun(json);
		run.run();
		if (!run.failures().isEmpty()) {
			fail(_file.getName() + ": " + json.get("title") + "\n" + String.join("\n", run._failures));
		}
	}

	private static void checkIndex() throws IOException {
		String readme = Files.readString(new File(CORPUS, "README.md").toPath(), StandardCharsets.UTF_8);
		Set<String> indexed = new TreeSet<>();
		Matcher matcher = Pattern.compile("^\\| `(\\d{2,3}-[a-z0-9-]+)` \\|", Pattern.MULTILINE).matcher(readme);
		while (matcher.find()) {
			indexed.add(matcher.group(1) + ".json");
		}
		Set<String> present = new TreeSet<>(Arrays.asList(CORPUS.list((dir, name) -> name.endsWith(".json"))));
		assertFalse("The index lists cases.", indexed.isEmpty());
		for (String name : NOT_INDEXED) {
			assertFalse("The corpus index lists " + name + " now: remove it from NOT_INDEXED.", indexed.contains(name));
			indexed.add(name);
		}
		assertEquals("The copy holds exactly the cases of the corpus index.", indexed, present);
	}

	/**
	 * One run of a case: the datapoints of the case owned by a technology accepting (or refusing)
	 * every command, the reports of the timeline given to the model directly. A subclass may wire
	 * the model to something else ({@link #loadModel()}, {@link #report(String, Object)},
	 * {@link #deliverLocal()}, {@link #finish()}).
	 */
	public static class CaseRun {

		/** The case. */
		protected final Map<?, ?> _case;

		/** The differences found. */
		protected final List<String> _failures = new ArrayList<>();

		/** The monotonic clock. */
		protected final long[] _now = { BASE };

		private final ParameterStore _store = new ParameterStore();

		private boolean _synchronized;

		/** The UTC instant of the corpus' time 0. */
		private long _startUtc;

		/** The steps of the wall clock against the monotonic clock so far, in seconds (README "clockStep"). */
		private long _stepSeconds;

		/** The case's <code>location</code>, <code>null</code> for none. */
		private GeoLocation _location;

		private PosixZone _zone = PosixZone.UTC;

		private String _language = "en";

		private final Map<SunEvent, TimeOfDay> _sun = new LinkedHashMap<>();

		private final Set<String> _schedules = new TreeSet<>();

		private final Set<String> _outputs = new TreeSet<>();

		private final Set<String> _unreadable = new TreeSet<>();

		/**
		 * The full schedule entries of the case (README "schedules"), which the scheduler runs on the
		 * case's clock; <code>null</code> when the case names its entries by id only.
		 */
		private Object _entries;

		/** The persisted records of the scheduler, kept across a reboot. */
		private final Map<String, String> _records = new HashMap<>();

		/** The scheduler of the current boot, <code>null</code> without full entries. */
		private Scheduler _scheduler;

		/** The schedules of the case as the loader sees them. */
		private final ScheduleDirectory _directory = new ScheduleDirectory() {
			@Override
			public boolean hasSchedule(String id) {
				return _schedules.contains(id);
			}

			@Override
			public boolean ownsOutput(String datapoint) {
				return _outputs.contains(datapoint);
			}

			@Override
			public boolean technologyReads(String datapoint) {
				return !_unreadable.contains(datapoint);
			}
		};

		private final List<String> _logs = new ArrayList<>();

		private final List<String> _commands = new ArrayList<>();

		private final List<String> _reads = new ArrayList<>();

		/** The model of the current boot. */
		protected DatapointModel _model;

		private Engine _engine;

		private Object _image;

		/** The <code>blocks</code> of the current image (homecontrol spec/fixtures/blocks/README.md). */
		private Object _blocks;

		/** The persisted state records of the blocks, kept across a reboot. */
		private final BlockRuntime.MemoryRecords _blockRecords = new BlockRuntime.MemoryRecords();

		/** The block runtime of the current boot, <code>null</code> before the blocks are loaded. */
		private BlockRuntime _blockRuntime;

		/** Which datapoints another component owns (spec/blocks.md B14). */
		private final BlockSet.Owners _owners = datapoint -> {
			if (_outputs.contains(datapoint)) {
				return "a schedule entry";
			}
			if (datapoint.startsWith("rule/")) {
				return "the rule engine";
			}
			if (datapoint.startsWith("schedule/")) {
				return "a schedule entry";
			}
			if (datapoint.startsWith("block/")) {
				return "the block runtime";
			}
			return null;
		};

		/**
		 * Creates a {@link CaseRun} of the given case.
		 */
		public CaseRun(Map<?, ?> json) {
			_case = json;
		}

		/**
		 * The differences found by {@link #run()}.
		 */
		public List<String> failures() {
			return _failures;
		}

		/**
		 * Runs the case and compares its outcome.
		 */
		public void run() throws Exception {
			Map<?, ?> clock = (Map<?, ?>) _case.get("clock");
			if (clock != null) {
				if (clock.get("zone") instanceof String zone) {
					_zone = PosixZone.parse(zone);
				}
				String[] parts = ((String) clock.get("start")).split("[-T:]");
				long local = Values.daysOf(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2])) * 86400 + Integer.parseInt(parts[3]) * 3600
					+ Integer.parseInt(parts[4]) * 60 + Integer.parseInt(parts[5]);
				// A time that occurs twice is its first occurrence: the one in summer time.
				_startUtc = _zone.utc(local, true);
				_synchronized = !Boolean.FALSE.equals(clock.get("synchronized"));
			}
			if (_case.get("location") instanceof Map<?, ?> location) {
				// The blocks' sun() (homecontrol spec/fixtures/blocks/README.md "location").
				_location = new GeoLocation(((Number) location.get("lat")).doubleValue(),
					((Number) location.get("lon")).doubleValue(), 0);
			}
			if (_case.get("ui") instanceof Map<?, ?> ui && ui.get("lang") instanceof String lang) {
				_language = lang;
			}
			Map<?, ?> astro = (Map<?, ?>) _case.get("astro");
			if (astro != null) {
				for (SunEvent event : SunEvent.values()) {
					if (astro.containsKey(event.externalName())) {
						_sun.put(event, Values.fromJson(Type.TIME_OF_DAY, astro.get(event.externalName())).value().time());
					}
				}
			}
			if (_case.get("schedules") instanceof List<?> schedules) {
				for (Object item : schedules) {
					if (item instanceof Map<?, ?> schedule) {
						_schedules.add((String) schedule.get("id"));
						if (schedule.get("output") instanceof String output) {
							_outputs.add(output);
						}
						if (schedule.containsKey("kind")) {
							_entries = schedules;
						}
					} else {
						_schedules.add((String) item);
					}
				}
			}
			for (Object item : (List<?>) _case.get("datapoints")) {
				Map<?, ?> dp = (Map<?, ?>) item;
				if (Boolean.FALSE.equals(dp.get("readable"))) {
					_unreadable.add((String) dp.get("name"));
				}
			}
			_blocks = _case.get("blocks");
			loadModel(_case.get("rules"));
			if (_case.get("persisted") instanceof Map<?, ?> persisted) {
				for (Map.Entry<?, ?> entry : persisted.entrySet()) {
					String key = (String) entry.getKey();
					Map<?, ?> stored = (Map<?, ?>) entry.getValue();
					if (key.startsWith("block/")) {
						persistRecord(key.substring("block/".length()), stored);
						continue;
					}
					if ("timeline".equals(stored.get("type"))) {
						// The inhabitant's timeline (spec/rules.md 8.7): "<ruleId>/.timeline".
						_store.writeTimeline(key.substring(0, key.indexOf('/')), stored.get("value"));
						continue;
					}
					// A persisted rule state (spec/rules.md 8.5) is "<ruleId>/.state" with the state id.
					Val value = "state".equals(stored.get("type")) ? Val.ofString((String) stored.get("value"))
						: Values.fromJson(Type.fromName((String) stored.get("type")), stored.get("value")).value();
					int slash = key.indexOf('/');
					_store.write(key.substring(0, slash), key.substring(slash + 1), value);
				}
			}
			if (_case.get("initial") instanceof Map<?, ?> initial) {
				for (Map.Entry<?, ?> entry : initial.entrySet()) {
					report((String) entry.getKey(), entry.getValue());
				}
			}
			_image = rulesOf(_case);
			Map<?, ?> expected = (Map<?, ?>) _case.get("expected");
			_engine = newEngine();
			Refusal refusal = _engine.load(_image, _directory);
			if (expected.containsKey("loadRefused") && (refusal == null && _blocks != null)) {
				// The blocks after the rules (spec/fixtures/blocks/README.md).
				try {
					BlockSet.load(_blocks, _model, _owners, ConformanceBlock.CORPUS_REGISTRY);
					_failures.add("the load must be refused with " + expected.get("loadRefused"));
				} catch (BlockSet.LoadException ex) {
					BlockSet.Refusal blockRefusal = ex.getRefusal();
					if (!blockRefusal.error().externalName().equals(expected.get("loadRefused"))) {
						_failures.add("blocks refused with " + blockRefusal + ", expected " + expected.get("loadRefused"));
					}
					if (expected.get("path") instanceof String path && !path.equals(blockRefusal.path())) {
						_failures.add("blocks refused at " + blockRefusal.path() + ", expected " + path);
					}
				}
				return;
			}
			if (expected.containsKey("loadRefused")) {
				if (refusal == null) {
					_failures.add("the load must be refused with " + expected.get("loadRefused"));
				} else if (!refusal.error().externalName().equals(expected.get("loadRefused"))) {
					_failures.add("load refused with " + refusal + ", expected " + expected.get("loadRefused"));
				} else {
					if (expected.get("path") instanceof String path && !path.equals(refusal.path())) {
						_failures.add("load refused at " + refusal.path() + ", expected " + path);
					}
					if (expected.get("text") instanceof String text && !text.equals(refusal.text())) {
						_failures.add("load refused with the text \"" + refusal.text() + "\", expected \"" + text + "\"");
					}
				}
				return;
			}
			if (refusal != null) {
				_failures.add("load refused: " + refusal);
				return;
			}
			if (!makeBlocks("load")) {
				return;
			}
			loadSchedules();
			_engine.start(StartReason.BOOT);
			advance(0);
			List<?> timeline = _case.get("timeline") instanceof List<?> list ? list : List.of();
			for (Object item : timeline) {
				Map<?, ?> step = (Map<?, ?>) item;
				long at = ((Number) step.get("at")).longValue();
				advance(at);
				apply(step, at);
				advance(at);
			}
			advance(((Number) _case.get("end")).longValue());
			finish();
			compareOutputs(expected);
			if (expected.get("final") instanceof Map<?, ?> state) {
				compareState(state, "final");
			}
		}

		/** The UTC time of now, <code>null</code> while the wall clock is not synchronized. */
		private Long nowUtc() {
			return _synchronized ? Long.valueOf(_startUtc + Math.floorDiv(_now[0] - BASE, 1000L) + _stepSeconds) : null;
		}

		/**
		 * Loads the full schedule entries of the case into a scheduler of the current boot (with the
		 * records of the boots before), the time datapoints taking their values as no events.
		 */
		private void loadSchedules() {
			if (_entries == null) {
				return;
			}
			if (_scheduler == null) {
				_scheduler = new Scheduler(new Scheduler.TimeSource() {
					@Override
					public Long nowUtc() {
						return CaseRun.this.nowUtc();
					}

					@Override
					public int utcOffset(long utc) {
						return _zone.offset(utc);
					}

					@Override
					public Astro.GeoLocation location() {
						return null;
					}
				}, _records, _model, line -> {
					// The scheduler's log is not part of the rule engine's.
				});
				_scheduler.deferTimeReports(true);
				Engine engine = _engine;
				_scheduler.addListener(engine::scheduleFired);
			}
			Schedule.Loaded loaded = Schedule.load(_entries, new Schedule.Datapoints() {
				@Override
				public ValueType type(String name) {
					Datapoint datapoint = _model.get(name);
					return datapoint == null ? null : datapoint.type();
				}

				@Override
				public String owner(String name) {
					return null;
				}
			});
			if (!loaded.ok()) {
				_failures.add("schedules refused: " + loaded.refusals());
				return;
			}
			try {
				_scheduler.load(loaded.entries());
			} catch (Scheduler.RefusedException ex) {
				_failures.add("schedules refused: " + ex.getMessage());
				return;
			}
			_engine.quietly(_scheduler::publishTimes);
		}

		/** Adds the time datapoints of the schedule entries the model lacks (an image swap keeps the others). */
		private void addScheduleDatapoints() {
			if (_entries == null) {
				return;
			}
			for (String id : Schedule.timeDatapointIds(_entries)) {
				String name = Schedule.timeDatapointName(id);
				if (_model.get(name) == null) {
					_model.add(name, ValueType.TIME_OF_DAY, true, null);
				}
			}
		}

		private Engine newEngine() {
			Engine engine = new Engine(_model, () -> _now[0], _store, new Engine.TimeSource() {
				@Override
				public DateTime localNow() {
					Long utc = nowUtc();
					return utc == null ? null : _zone.local(utc.longValue());
				}

				@Override
				public TimeOfDay sunTime(SunEvent event) {
					return _sun.get(event);
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
					return _location == null ? null : Astro.sunPosition(utc, _location);
				}

				@Override
				public GeoLocation location() {
					return _location;
				}

				@Override
				public Long utcNow() {
					return nowUtc();
				}
			});
			engine.setLanguage(_language);
			engine.setObserver(new Engine.Observer() {
				@Override
				public void ruleLogged(String rule, LogCode code, Level level, String text) {
					String line = (_now[0] - BASE) + " " + rule + " " + code.externalName();
					_logs.add(code == LogCode.LOG ? line + " " + level.externalName() + " \"" + text + "\"" : line);
				}

				@Override
				public void loopGuard(String rule, String culprit, int firings) {
					int last = _logs.size() - 1;
					_logs.set(last, _logs.get(last) + " culprit " + culprit + " " + firings);
				}

				@Override
				public void ruleCommanded(String rule, Datapoint datapoint, Val value) {
					_commands.add((_now[0] - BASE) + " " + rule + " " + datapoint.name() + " = " + text(value));
				}

				@Override
				public void ruleRead(String rule, Datapoint datapoint) {
					_reads.add((_now[0] - BASE) + " " + rule + " " + datapoint.name());
				}
			});
			return engine;
		}

		/**
		 * Called after the end of the timeline, before the outputs are compared.
		 */
		protected void finish() throws Exception {
			// Nothing by default.
		}

		/**
		 * Creates the {@link #_model} of a boot with the datapoints of the case and the parameter
		 * datapoints of the rules (<code>spec/rules.md</code> 8.6) as the image loader derives them,
		 * unbound: the engine binds them.
		 */
		private void loadModel(Object rules) {
			createModel();
			addParameterDatapoints(rules);
			addScheduleDatapoints();
			addBlockDatapoints();
		}

		/** Adds the mirrors of the blocks the model lacks (spec/blocks.md 6), as the image loader derives them. */
		private void addBlockDatapoints() {
			for (BlockSet.ImpliedDatapoint dp : BlockSet.impliedDatapoints(_blocks, ConformanceBlock.CORPUS_REGISTRY)) {
				if (_model.get(dp.name()) == null) {
					_model.add(dp.name(), dp.type(), dp.writable(), null);
				}
			}
		}

		/** The <code>rules</code> of the case or of an image step; none: an empty array (block cases). */
		private static Object rulesOf(Map<?, ?> image) {
			return image.containsKey("rules") ? image.get("rules") : List.of();
		}

		/**
		 * Compiles the blocks of the current image and starts a block runtime of the current boot on
		 * them.
		 *
		 * @return Whether the blocks were accepted.
		 */
		private boolean makeBlocks(String where) {
			BlockSet set;
			try {
				set = BlockSet.load(_blocks, _model, _owners, ConformanceBlock.CORPUS_REGISTRY);
			} catch (BlockSet.LoadException ex) {
				_failures.add(where + ": blocks refused: " + ex.getRefusal());
				return false;
			}
			if (_blockRuntime == null) {
				_blockRuntime = new BlockRuntime(_model, () -> _now[0], _store, _blockRecords, _engine);
				_blockRuntime.setObserver(new BlockRuntime.Observer() {
					@Override
					public void blockLogged(String block, BlockRuntime.LogCode code, Level level, String text) {
						String line = (_now[0] - BASE) + " block " + block + " " + code.externalName();
						_logs.add(code == BlockRuntime.LogCode.LOG ? line + " " + level.externalName() + " \"" + text + "\""
							: line);
					}

					@Override
					public void blockCommanded(String block, Datapoint datapoint, Val value) {
						_commands.add((_now[0] - BASE) + " block " + block + " " + datapoint.name() + " = " + text(value));
					}

					@Override
					public void loopGuard(String block, String culprit, int firings) {
						int last = _logs.size() - 1;
						_logs.set(last, _logs.get(last) + " culprit " + culprit + " " + firings);
					}
				});
			}
			_blockRuntime.adopt(set);
			return true;
		}

		/** README "persisted": a state record <code>block/&lt;id&gt;</code> written as the device writes it. */
		private void persistRecord(String id, Map<?, ?> record) {
			int[] version = BlockSet.parseVersion((String) record.get("version"));
			BlockType type = ConformanceBlock.CORPUS_REGISTRY.find((String) record.get("type"), version[0]);
			if (type == null) {
				throw new AssertionError("No block type " + record.get("type"));
			}
			Map<String, Double> fields = new LinkedHashMap<>();
			Manifest m = type.manifest();
			for (Map.Entry<?, ?> field : ((Map<?, ?>) record.get("fields")).entrySet()) {
				int f = m.field((String) field.getKey());
				if (f < 0) {
					throw new AssertionError("No state field " + field.getKey());
				}
				if (m.state().get(f).persist()) {
					fields.put((String) field.getKey(), Double.valueOf(((Number) field.getValue()).doubleValue()));
				}
			}
			_blockRecords.write(id, new StateRecord(m.type(), m.major(), fields));
		}

		/** README "setBlock": the change of PUT /api/blocks/&lt;id&gt;, checked whole, then applied. */
		private void setBlock(Map<?, ?> change, long at) {
			String id = (String) change.get("block");
			Check check = new Check(ChangeStatus.OK, "", 0);
			String member = "";
			List<Object[]> values = new ArrayList<>();
			if (change.get("parameters") instanceof Map<?, ?> parameters) {
				for (Map.Entry<?, ?> entry : parameters.entrySet()) {
					if (!check.ok()) {
						break;
					}
					member = (String) entry.getKey();
					check = _blockRuntime.checkParameter(id, member, entry.getValue(), false);
					values.add(new Object[] { member, Double.valueOf(check.value()) });
				}
			}
			List<?> reset = change.get("reset") instanceof List<?> list ? list : List.of();
			for (Object name : reset) {
				if (check.ok()) {
					member = (String) name;
					check = _blockRuntime.checkParameter(id, member, null, true);
				}
			}
			if (check.ok() && change.containsKey("enabled")) {
				member = "enabled";
				check = _blockRuntime.checkEnabled(id);
			}
			if (check.ok() && change.containsKey("restart")) {
				member = "restart";
				check = _blockRuntime.checkRestart(id);
			}
			if (!check.ok() && check.status() != ChangeStatus.NOT_FAULTED) {
				_blockRuntime.refused(id, member, check.reason());
			}
			String got = check.ok() ? "accepted" : check.status().externalName();
			String want = change.get("refused") instanceof String refused ? refused : "accepted";
			if (!got.equals(want)) {
				_failures.add("setBlock at " + at + ": " + got + " (" + check.reason() + "), expected " + want);
			}
			if (!check.ok()) {
				return;
			}
			for (Object[] value : values) {
				_blockRuntime.setParameter(id, (String) value[0], ((Double) value[1]).doubleValue());
			}
			for (Object name : reset) {
				_blockRuntime.resetParameter(id, (String) name);
			}
			if (change.get("enabled") instanceof Boolean enabled) {
				_blockRuntime.setEnabled(id, enabled.booleanValue());
			}
			if (change.containsKey("restart")) {
				_blockRuntime.restart(id);
			}
		}

		private int block(String id) {
			int b = _blockRuntime == null ? -1 : _blockRuntime.set().find(id);
			if (b < 0) {
				throw new AssertionError("No block " + id);
			}
			return b;
		}

		private static double numberOf(Object json) {
			return json instanceof Boolean b ? (b.booleanValue() ? 1 : 0) : ((Number) json).doubleValue();
		}

		private static boolean sameNumber(double have, double want) {
			return have == want || Math.abs(have - want) <= 1e-9 * Math.max(1.0, Math.max(Math.abs(have), Math.abs(want)));
		}

		private void compareBlocks(Map<?, ?> expected, String where) {
			for (Map.Entry<?, ?> entry : expected.entrySet()) {
				String id = (String) entry.getKey();
				int b = block(id);
				Map<?, ?> want = (Map<?, ?>) entry.getValue();
				Manifest m = _blockRuntime.set().at(b).manifest();
				if (want.get("status") instanceof String status
					&& !status.equals(_blockRuntime.status(b).externalName())) {
					_failures.add(where + ": block " + id + " is " + _blockRuntime.status(b).externalName() + ", expected "
						+ status);
				}
				if (want.get("calls") instanceof Number calls && calls.intValue() != _blockRuntime.calls(b)) {
					_failures.add(where + ": block " + id + " made " + _blockRuntime.calls(b) + " calls, expected " + calls);
				}
				if (want.get("state") instanceof Map<?, ?> state) {
					for (Map.Entry<?, ?> field : state.entrySet()) {
						int f = m.field((String) field.getKey());
						if (f < 0) {
							throw new AssertionError("No state field " + field.getKey());
						}
						double have = _blockRuntime.state(b)[f];
						if (!sameNumber(have, numberOf(field.getValue()))) {
							_failures.add(where + ": " + id + "." + field.getKey() + " is " + have + ", expected "
								+ field.getValue());
						}
					}
				}
				if (want.get("parameters") instanceof Map<?, ?> parameters) {
					for (Map.Entry<?, ?> param : parameters.entrySet()) {
						int p = m.param((String) param.getKey());
						if (p < 0) {
							throw new AssertionError("No parameter " + param.getKey());
						}
						double have = _blockRuntime.param(b, p);
						if (!sameNumber(have, numberOf(param.getValue()))) {
							_failures.add(where + ": parameter " + id + "/" + param.getKey() + " is " + have + ", expected "
								+ param.getValue());
						}
					}
				}
			}
		}

		/** The complete set of persisted records (README "records"), after an orderly shutdown's writes. */
		private void compareRecords(Map<?, ?> expected, String where) {
			_blockRuntime.flush();
			Set<String> expectedIds = new TreeSet<>();
			for (Map.Entry<?, ?> entry : expected.entrySet()) {
				String id = (String) entry.getKey();
				StateRecord record = _blockRecords.read(id);
				if (entry.getValue() == null) {
					if (record != null) {
						_failures.add(where + ": a record of " + id + " is stored");
					}
					continue;
				}
				expectedIds.add(id);
				if (record == null) {
					_failures.add(where + ": no record of " + id);
					continue;
				}
				Map<?, ?> want = (Map<?, ?>) entry.getValue();
				for (Map.Entry<String, Double> field : record.fields().entrySet()) {
					if (!want.containsKey(field.getKey())) {
						_failures.add(where + ": the record of " + id + " holds " + field.getKey() + " = "
							+ field.getValue() + ", not expected");
					} else if (!sameNumber(field.getValue().doubleValue(), numberOf(want.get(field.getKey())))) {
						_failures.add(where + ": the record of " + id + ": " + field.getKey() + " is " + field.getValue()
							+ ", expected " + want.get(field.getKey()));
					}
				}
			}
			for (String id : _blockRecords.blocks()) {
				if (!expectedIds.contains(id)) {
					_failures.add(where + ": an unexpected record of " + id + " is stored");
				}
			}
		}

		/** Adds the parameter datapoints of the rules the model lacks (an image swap keeps the others). */
		private void addParameterDatapoints(Object rules) {
			for (ParameterDatapoint dp : RuleLoader.parameterDatapoints(rules)) {
				if (_model.get(dp.name()) == null) {
					_model.add(dp.name(), dp.type(), dp.writable(), null);
				}
			}
		}

		/**
		 * Creates the {@link #_model} of a boot with the datapoints of the case.
		 */
		protected void createModel() {
			_model = new DatapointModel(() -> _now[0]);
			for (Object item : (List<?>) _case.get("datapoints")) {
				Map<?, ?> dp = (Map<?, ?>) item;
				ValueType type = ValueType.fromName((String) dp.get("type"));
				boolean writable = !Boolean.FALSE.equals(dp.get("writable"));
				boolean refuse = Boolean.TRUE.equals(dp.get("refuseCommands"));
				boolean refuseReads = Boolean.TRUE.equals(dp.get("refuseReads"));
				if (Boolean.TRUE.equals(dp.get("application"))) {
					// A value of the application (schema 1.6): no technology owns it.
					_model.add((String) dp.get("name"), type, writable, true, null);
					continue;
				}
				_model.add((String) dp.get("name"), type, writable, new DatapointModel.Technology() {
					@Override
					public boolean command(Datapoint datapoint, Val value) {
						return !refuse;
					}

					@Override
					public boolean read(Datapoint datapoint) {
						return !refuseReads;
					}
				});
			}
		}

		/**
		 * The value of the case's JSON notation for the datapoint, in its type.
		 */
		protected Val datapointValue(String name, Object json) {
			Datapoint datapoint = _model.get(name);
			Val value = expectedValue(datapoint.type().expressionType(), json);
			return Values.convertForDatapoint(value, datapoint.type());
		}

		/**
		 * The technology reports the value of a datapoint.
		 */
		protected void report(String name, Object json) throws Exception {
			_model.report(name, datapointValue(name, json));
		}

		/**
		 * Delivers what the technology holds back until the cascade is done.
		 *
		 * @return Whether there was something.
		 */
		protected boolean deliverLocal() {
			return false;
		}

		/** Runs up to the time: the queued events, then every timer at its own deadline. */
		private void advance(long at) {
			long target = BASE + at;
			while (true) {
				if (_engine.hasPending()) {
					_engine.drain();
					continue;
				}
				if (deliverLocal()) {
					continue;
				}
				long deadline = Math.min(_engine.nextDeadline(), pointDeadline());
				long firing = schedulerDeadline();
				if (Math.min(deadline, firing) > target) {
					break;
				}
				_now[0] = Math.max(_now[0], Math.min(deadline, firing));
				if (firing <= _now[0]) {
					_scheduler.fireDue();
				} else {
					_engine.processDueTimers();
				}
			}
			_now[0] = target;
		}

		/**
		 * The monotonic time of the next switching point of a timeline (spec/rules.md 7.13), at its
		 * moment exactly; {@link Long#MAX_VALUE} for none.
		 */
		private long pointDeadline() {
			Long moment = _engine.nextTimelineMoment();
			if (moment == null) {
				return Long.MAX_VALUE;
			}
			return BASE + (moment.longValue() - _startUtc - _stepSeconds) * 1000;
		}

		/** The change of a timeline as <code>PUT</code> or <code>DELETE /api/rules/&lt;id&gt;/timeline</code> makes it. */
		private void setTimeline(Map<?, ?> change, long at) {
			String rule = (String) change.get("rule");
			Engine.TimelineChange result = Boolean.TRUE.equals(change.get("reset")) ? _engine.resetTimeline(rule)
				: _engine.setTimeline(rule, change.get("timeline"));
			String have = result.ok() ? "ok" : result.code();
			String want = change.get("refused") instanceof String refused ? refused : "ok";
			if (!want.equals(have)) {
				_failures.add("the change of the timeline of " + rule + " at " + at + " is " + have + " (" + result.text()
					+ "), expected " + want);
			} else if (change.get("path") instanceof String path && !path.equals(result.path())) {
				_failures.add("the change of the timeline of " + rule + " at " + at + " is refused at " + result.path()
					+ ", expected " + path);
			}
		}

		/** The monotonic time the scheduler wants to fire at, {@link Long#MAX_VALUE} for none. */
		private long schedulerDeadline() {
			Long wakeup = _scheduler == null ? null : _scheduler.wakeup();
			if (wakeup == null) {
				return Long.MAX_VALUE;
			}
			return BASE + (wakeup.longValue() - _startUtc - _stepSeconds) * 1000;
		}

		/** The change of a schedule entry as <code>PUT /api/schedules/&lt;id&gt;</code> makes it. */
		private void setSchedule(Map<?, ?> change, long at) {
			String id = (String) change.get("id");
			String result;
			if (Boolean.TRUE.equals(change.get("reset"))) {
				result = _scheduler.resetOverrides(id) ? "ok" : "unknown";
			} else {
				Map<Member, Long> values = new EnumMap<>(Member.class);
				result = null;
				for (Map.Entry<?, ?> entry : change.entrySet()) {
					String name = (String) entry.getKey();
					if (name.equals("id") || name.equals("refused") || name.equals("enabled")) {
						continue;
					}
					Member member = Member.fromName(name);
					Long value = member == null ? null : member.scalar(entry.getValue());
					if (value == null) {
						result = "malformed";
						break;
					}
					values.put(member, value);
				}
				if (result == null && !values.isEmpty()) {
					result = _scheduler.setMembers(id, values).externalName();
				}
				if ((result == null || result.equals("ok")) && change.get("enabled") instanceof Boolean enabled) {
					result = _scheduler.setEnabled(id, enabled.booleanValue()) == null ? "ok" : "not_adjustable";
				}
			}
			Object refused = change.get("refused");
			String expected = refused == null ? "ok" : (String) refused;
			if (!expected.equals(result)) {
				_failures.add("the change of schedule " + id + " at " + at + " is " + result + ", expected " + expected);
			}
		}

		private void apply(Map<?, ?> step, long at) throws Exception {
			if (step.get("report") instanceof Map<?, ?> report) {
				report((String) report.get("datapoint"), report.get("value"));
			} else if (step.get("stale") instanceof String name) {
				_model.reportStale(name);
			} else if (step.get("command") instanceof Map<?, ?> command) {
				String name = (String) command.get("datapoint");
				DatapointModel.CommandStatus status = _model.command(name, datapointValue(name, command.get("value")));
				boolean refused = Boolean.TRUE.equals(command.get("refused"));
				if (refused != (status != DatapointModel.CommandStatus.OK)) {
					_failures.add("the command at " + at + " must " + (refused ? "" : "not ") + "be refused: "
						+ status.text());
				}
			} else if (step.get("schedule") instanceof String id) {
				_engine.scheduleFired(id);
			} else if (step.get("setSchedule") instanceof Map<?, ?> change) {
				setSchedule(change, at);
			} else if (step.get("setParameter") instanceof Map<?, ?> p) {
				_engine.setParameterJson((String) p.get("rule"), (String) p.get("parameter"), p.get("value"));
			} else if (step.get("clockSync") instanceof Boolean sync) {
				_synchronized = sync.booleanValue();
				if (_synchronized) {
					_engine.clockSynchronized();
				}
			} else if (step.get("clockStep") instanceof Number ms) {
				long seconds = ms.longValue() / 1000;
				_stepSeconds += seconds;
				_engine.clockStepped(seconds);
			} else if (step.get("setTimeline") instanceof Map<?, ?> change) {
				setTimeline(change, at);
			} else if (step.get("image") instanceof Map<?, ?> image) {
				// The model is loaded again with the values kept: the parameter datapoints follow the
				// new rules (spec/rules.md 7.6).
				_engine.flush();
				addParameterDatapoints(rulesOf(image));
				_blocks = image.get("blocks");
				addBlockDatapoints();
				Refusal refusal = _engine.load(rulesOf(image), _directory);
				if (refusal != null) {
					_failures.add("image at " + at + " refused: " + refusal);
					return;
				}
				_image = rulesOf(image);
				if (!makeBlocks("image at " + at)) {
					return;
				}
				if (image.get("schedules") instanceof List<?> schedules) {
					// The new image's entries: their overrides are kept or dropped (spec/schedules.md 4.4).
					_entries = schedules;
					addScheduleDatapoints();
					loadSchedules();
				}
				_engine.start(StartReason.IMAGE);
			} else if (step.containsKey("reboot")) {
				if (_blockRuntime != null) {
					// An orderly reboot: the due records are written.
					_blockRuntime.flush();
					_blockRuntime = null;
				}
				_engine.close();
				loadModel(_image);
				_engine = newEngine();
				_scheduler = null;
				Refusal refusal = _engine.load(_image, _directory);
				if (refusal != null) {
					_failures.add("reboot at " + at + ": load refused: " + refusal);
					return;
				}
				if (!makeBlocks("reboot at " + at)) {
					return;
				}
				loadSchedules();
				_engine.start(StartReason.BOOT);
			} else if (step.get("setBlock") instanceof Map<?, ?> change) {
				setBlock(change, at);
			} else if (step.get("check") instanceof Map<?, ?> check) {
				compareState(check, "check at " + at);
			} else {
				_failures.add("unknown timeline step at " + at + ": " + step);
			}
		}

		private Val expectedValue(Type type, Object json) {
			if (json == null) {
				return Val.none(type);
			}
			if (type == Type.DATE_TIME && json instanceof Map<?, ?> map
				&& (map.containsKey("dayOfWeek") || map.containsKey("summerTime"))) {
				// The corpus notation adds the day of the week and the summer-time flag to a date-time
				// (README "Values").
				Map<Object, Object> copy = new LinkedHashMap<>(map);
				copy.remove("dayOfWeek");
				copy.remove("summerTime");
				DateTime value = Values.fromJson(type, copy).value().dateTime();
				if (map.containsKey("dayOfWeek") && value.dayOfWeek() != ((Number) map.get("dayOfWeek")).intValue()) {
					_failures.add("day of week of the expected date-time " + json);
				}
				return Val.ofDateTime(new DateTime(value.year(), value.month(), value.day(), value.hour(), value.minute(),
					value.second(), value.dayOfWeek(), Boolean.TRUE.equals(map.get("summerTime")) ? DateTime.SUMMER_TIME : 0));
			}
			Values.Literal literal = Values.fromJson(type, json);
			if (!literal.isOk()) {
				throw new AssertionError("Expected value " + json + " of type " + type + ": " + literal.reason());
			}
			return literal.value();
		}

		/** A value as the outputs are compared: its literal, a summer-time flag of a date-time noted. */
		private static String text(Val value) {
			String text = String.valueOf(value);
			if (value.has() && value.type() == Type.DATE_TIME && value.dateTime().summerTime()) {
				text += " (summer time)";
			}
			return text;
		}

		private static boolean same(Val actual, Val expected) {
			if (!actual.has() || !expected.has()) {
				return actual.has() == expected.has();
			}
			if (actual.type() == Type.REAL && expected.type() == Type.REAL) {
				double a = actual.real();
				double e = expected.real();
				return a == e || Math.abs(a - e) <= 1e-9 * Math.max(Math.abs(a), Math.abs(e));
			}
			return Val.identical(actual, expected);
		}

		private int rule(String id) {
			int r = _engine.rules().findRule(id);
			if (r < 0) {
				throw new AssertionError("No rule " + id);
			}
			return r;
		}

		private void compareState(Map<?, ?> expected, String where) {
			if (expected.get("datapoints") instanceof Map<?, ?> datapoints) {
				for (Map.Entry<?, ?> entry : datapoints.entrySet()) {
					Datapoint datapoint = _model.get((String) entry.getKey());
					Val want = expectedValue(datapoint.type().expressionType(), entry.getValue());
					Val have = datapoint.quality() == Quality.UNKNOWN ? Val.none(want.type()) : datapoint.value();
					if (!same(have, want)) {
						_failures.add(where + ": datapoint " + entry.getKey() + " is " + have + ", expected " + want);
					}
				}
			}
			if (expected.get("states") instanceof Map<?, ?> states) {
				for (Map.Entry<?, ?> entry : states.entrySet()) {
					String have = _engine.currentState(rule((String) entry.getKey()));
					if (!entry.getValue().equals(have)) {
						_failures.add(where + ": state of " + entry.getKey() + " is " + have + ", expected " + entry.getValue());
					}
				}
			}
			if (expected.get("parameters") instanceof Map<?, ?> parameters) {
				for (Map.Entry<?, ?> entry : parameters.entrySet()) {
					String key = (String) entry.getKey();
					int slash = key.indexOf('/');
					int r = rule(key.substring(0, slash));
					RuleSet.Rule decl = _engine.rules().rules().get(r);
					int p = decl.findParameter(key.substring(slash + 1));
					if (p < 0) {
						_failures.add(where + ": no parameter " + key);
						continue;
					}
					Val have = _engine.parameterValue(r, p);
					Val want = expectedValue(have.type(), entry.getValue());
					if (!same(have, want)) {
						_failures.add(where + ": parameter " + key + " is " + have + ", expected " + want);
					}
				}
			}
			if (expected.get("timers") instanceof Map<?, ?> timers) {
				for (Map.Entry<?, ?> entry : timers.entrySet()) {
					String key = (String) entry.getKey();
					int slash = key.indexOf('/');
					int r = rule(key.substring(0, slash));
					int t = _engine.rules().rules().get(r).findTimer(key.substring(slash + 1));
					if (t < 0) {
						_failures.add(where + ": no timer " + key);
						continue;
					}
					Long have = _engine.timerRemaining(r, t);
					Long want = entry.getValue() == null ? null : Long.valueOf(((Number) entry.getValue()).longValue());
					if (!Objects.equals(have, want)) {
						_failures.add(where + ": timer " + key + " is " + have + ", expected " + want);
					}
				}
			}
			if (expected.get("names") instanceof Map<?, ?> names) {
				for (Map.Entry<?, ?> entry : names.entrySet()) {
					String have = _engine.rules().rules().get(rule((String) entry.getKey())).name();
					if (!entry.getValue().equals(have)) {
						_failures.add(where + ": name of " + entry.getKey() + " is " + have + ", expected " + entry.getValue());
					}
				}
			}
			if (expected.get("labels") instanceof Map<?, ?> labels) {
				for (Map.Entry<?, ?> entry : labels.entrySet()) {
					String key = (String) entry.getKey();
					int slash = key.indexOf('/');
					RuleSet.Rule decl = _engine.rules().rules().get(rule(key.substring(0, slash)));
					int p = decl.findParameter(key.substring(slash + 1));
					String have = p < 0 ? null : decl.parameters().get(p).label();
					if (!entry.getValue().equals(have)) {
						_failures.add(where + ": label of " + key + " is " + have + ", expected " + entry.getValue());
					}
				}
			}
			if (expected.get("firings") instanceof Map<?, ?> firings) {
				for (Map.Entry<?, ?> entry : firings.entrySet()) {
					int have = _engine.firings(rule((String) entry.getKey()));
					int want = ((Number) entry.getValue()).intValue();
					if (have != want) {
						_failures.add(where + ": firings of " + entry.getKey() + " are " + have + ", expected " + want);
					}
				}
			}
			if (expected.get("schedules") instanceof Map<?, ?> schedules) {
				compareSchedules(schedules, where);
			}
			if (expected.get("timelines") instanceof Map<?, ?> timelines) {
				compareTimelines(timelines, where);
			}
			if (expected.get("blocks") instanceof Map<?, ?> blocks) {
				compareBlocks(blocks, where);
			}
			if (expected.get("records") instanceof Map<?, ?> records) {
				compareRecords(records, where);
			}
			if (expected.get("persisted") instanceof Map<?, ?> persisted) {
				Map<String, Val> stored = _store.overrides();
				Map<String, Object> timelines = _store.timelines();
				for (Map.Entry<?, ?> entry : persisted.entrySet()) {
					if (((String) entry.getKey()).endsWith("/" + RuleSet.TIMELINE_KEY)) {
						Object timeline = timelines.get(entry.getKey());
						if (timeline == null) {
							_failures.add(where + ": timeline " + entry.getKey() + " not stored");
						} else if (!jsonValue(timeline).equals(jsonValue(entry.getValue()))) {
							_failures.add(where + ": stored timeline " + entry.getKey() + " is " + Json.compact(timeline)
								+ ", expected " + Json.compact(entry.getValue()));
						}
						continue;
					}
					Val have = stored.get(entry.getKey());
					if (have == null) {
						_failures.add(where + ": override " + entry.getKey() + " not stored");
						continue;
					}
					Val want = expectedValue(have.type(), entry.getValue());
					if (!same(have, want)) {
						_failures.add(where + ": stored override " + entry.getKey() + " is " + have + ", expected " + want);
					}
				}
				for (Map.Entry<String, Val> entry : stored.entrySet()) {
					if (!persisted.containsKey(entry.getKey())) {
						_failures.add(where + ": unexpected stored override " + entry.getKey() + " = " + entry.getValue());
					}
				}
				for (String key : timelines.keySet()) {
					if (!persisted.containsKey(key)) {
						_failures.add(where + ": unexpected stored timeline " + key);
					}
				}
			}
		}

		/** README "final.timelines": whether the inhabitant's timeline applies and the next switching point. */
		private void compareTimelines(Map<?, ?> timelines, String where) {
			for (Map.Entry<?, ?> entry : timelines.entrySet()) {
				String id = (String) entry.getKey();
				int r = rule(id);
				Map<?, ?> want = (Map<?, ?>) entry.getValue();
				if (want.get("overridden") instanceof Boolean overridden
					&& overridden.booleanValue() != _engine.timelineOverridden(r)) {
					_failures.add(where + ": the timeline of " + id + " is " + (overridden.booleanValue() ? "not " : "")
						+ "overridden");
				}
				if (want.containsKey("next")) {
					Engine.NextPoint next = _engine.nextPoint(r);
					String have = next == null ? null : localText(next.utc()) + " " + next.state();
					String expectedNext = want.get("next") instanceof Map<?, ?> point ? point.get("at") + " " + point.get("state")
						: null;
					if (!Objects.equals(have, expectedNext)) {
						_failures.add(where + ": the next point of " + id + " is " + have + ", expected " + expectedNext);
					}
				}
			}
		}

		/** The local wall time of the instant as <code>YYYY-MM-DDTHH:MM:SS</code>. */
		private String localText(long utc) {
			DateTime local = _zone.local(utc);
			return String.format("%04d-%02d-%02dT%02d:%02d:%02d", Integer.valueOf(local.year()),
				Integer.valueOf(local.month()), Integer.valueOf(local.day()), Integer.valueOf(local.hour()),
				Integer.valueOf(local.minute()), Integer.valueOf(local.second()));
		}

		/** A JSON value with its numbers as doubles, for comparing a value written with one read. */
		private static Object jsonValue(Object value) {
			if (value instanceof Number number) {
				return Double.valueOf(number.doubleValue());
			}
			if (value instanceof Map<?, ?> map) {
				Map<Object, Object> result = new LinkedHashMap<>();
				map.forEach((k, v) -> result.put(k, jsonValue(v)));
				return result;
			}
			if (value instanceof List<?> list) {
				List<Object> result = new ArrayList<>();
				for (Object element : list) {
					result.add(jsonValue(element));
				}
				return result;
			}
			return value;
		}

		private void compareSchedules(Map<?, ?> schedules, String where) {
			for (Map.Entry<?, ?> entry : schedules.entrySet()) {
				String id = (String) entry.getKey();
				int index = _scheduler == null ? -1 : _scheduler.find(id);
				if (index < 0) {
					_failures.add(where + ": no schedule " + id);
					continue;
				}
				Map<?, ?> want = (Map<?, ?>) entry.getValue();
				Schedule spec = _scheduler.spec(index);
				if (want.get("values") instanceof Map<?, ?> values) {
					for (Map.Entry<?, ?> value : values.entrySet()) {
						Member member = Member.fromName((String) value.getKey());
						Long expectedValue = member.scalar(value.getValue());
						long have = spec.memberValue(member);
						if (expectedValue == null || have != expectedValue.longValue()) {
							_failures.add(where + ": " + member.externalName() + " of schedule " + id + " is "
								+ Json.write(member.json(have)) + ", expected " + Json.write(value.getValue()));
						}
					}
				}
				if (want.get("overridden") instanceof List<?> overridden) {
					Set<String> have = new TreeSet<>();
					for (Member member : Member.of(_scheduler.overridden(index))) {
						have.add(member.externalName());
					}
					Set<String> expectedSet = new TreeSet<>();
					for (Object name : overridden) {
						expectedSet.add((String) name);
					}
					if (!have.equals(expectedSet)) {
						_failures.add(where + ": overridden members of schedule " + id + " are " + have + ", expected "
							+ expectedSet);
					}
				}
				if (want.get("enabled") instanceof Boolean enabled && enabled.booleanValue() != _scheduler.enabled(index)) {
					_failures.add(where + ": schedule " + id + " is " + (enabled.booleanValue() ? "off" : "on"));
				}
			}
		}

		private void compareOutputs(Map<?, ?> expected) {
			List<String> wantCommands = new ArrayList<>();
			boolean commandsOk = true;
			List<?> commands = (List<?>) expected.get("commands");
			for (int i = 0; i < commands.size(); i++) {
				Map<?, ?> c = (Map<?, ?>) commands.get(i);
				String name = (String) c.get("datapoint");
				Datapoint datapoint = _model.get(name);
				Val want = expectedValue(datapoint.type().expressionType(), c.get("value"));
				String line = c.get("at") + " " + who(c) + " " + name + " = " + text(want);
				wantCommands.add(line);
				commandsOk &= i < _commands.size() && sameCommand(_commands.get(i), line, want);
			}
			commandsOk &= commands.size() == _commands.size();
			if (!commandsOk) {
				_failures.add("commands differ\nexpected:\n  " + String.join("\n  ", wantCommands) + "\nactual:\n  "
					+ String.join("\n  ", _commands));
			}
			List<String> wantReads = new ArrayList<>();
			if (expected.get("reads") instanceof List<?> reads) {
				for (Object item : reads) {
					Map<?, ?> r = (Map<?, ?>) item;
					wantReads.add(r.get("at") + " " + r.get("rule") + " " + r.get("datapoint"));
				}
			}
			if (!wantReads.equals(_reads)) {
				_failures.add("reads differ\nexpected:\n  " + String.join("\n  ", wantReads) + "\nactual:\n  "
					+ String.join("\n  ", _reads));
			}
			List<String> wantLogs = new ArrayList<>();
			List<String> haveLogs = new ArrayList<>();
			List<?> logs = (List<?>) expected.get("logs");
			for (int i = 0; i < logs.size(); i++) {
				Map<?, ?> l = (Map<?, ?>) logs.get(i);
				String line = l.get("at") + " " + who(l) + " " + l.get("code");
				Object level = l.containsKey("level") ? l.get("level") : "info";
				line = "log".equals(l.get("code")) ? line + " " + level + " \"" + l.get("message") + "\"" : line;
				if (l.containsKey("culprit")) {
					line += " culprit " + l.get("culprit") + " " + ((Number) l.get("firings")).intValue();
				}
				wantLogs.add(line);
			}
			for (int i = 0; i < _logs.size(); i++) {
				// The culprit of a loop guard is compared only where the case names it.
				String have = _logs.get(i);
				int culprit = have.indexOf(" culprit ");
				boolean named = i < logs.size() && ((Map<?, ?>) logs.get(i)).containsKey("culprit");
				haveLogs.add(culprit >= 0 && !named ? have.substring(0, culprit) : have);
			}
			if (!wantLogs.equals(haveLogs)) {
				_failures.add("logs differ\nexpected:\n  " + String.join("\n  ", wantLogs) + "\nactual:\n  "
					+ String.join("\n  ", _logs));
			}
		}

		/** The rule of an expected command or log entry, or <code>block &lt;id&gt;</code>. */
		private static String who(Map<?, ?> entry) {
			return entry.containsKey("block") ? "block " + entry.get("block") : String.valueOf(entry.get("rule"));
		}

		/** Compares a recorded command with an expected one, reals with the corpus' tolerance. */
		private static boolean sameCommand(String actual, String expected, Val want) {
			if (actual.equals(expected)) {
				return true;
			}
			if (want.type() != Type.REAL) {
				return false;
			}
			String prefix = expected.substring(0, expected.indexOf(" = ") + 3);
			if (!actual.startsWith(prefix + "real ")) {
				return false;
			}
			double have = Double.parseDouble(actual.substring(prefix.length() + "real ".length()));
			return same(Val.ofReal(have), want);
		}
	}

	/**
	 * The index check and one test per case.
	 */
	public static Test suite() throws IOException {
		File[] files = CORPUS.listFiles((dir, name) -> name.endsWith(".json"));
		if (files == null || files.length == 0) {
			throw new IOException("No corpus in " + CORPUS.getAbsolutePath());
		}
		Arrays.sort(files);
		TestSuite suite = new TestSuite(TestRuleCorpus.class.getName());
		suite.addTest(new TestRuleCorpus(null));
		for (File file : files) {
			suite.addTest(new TestRuleCorpus(file));
		}
		return suite;
	}

}
