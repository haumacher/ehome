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

import de.haumacher.ehome.rules.Astro.SunEvent;
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
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;

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
	 * Cases of the corpus its index (the table "Cases" of its README) does not list: a defect of the
	 * corpus at haumacher/homecontrol 8d90149, where case 54 came without its line in the index.
	 */
	private static final Set<String> NOT_INDEXED = Set.of("54-silence-read-parameter-datapoint.json");

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
		Matcher matcher = Pattern.compile("^\\| `(\\d\\d-[a-z0-9-]+)` \\|", Pattern.MULTILINE).matcher(readme);
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

		private PosixZone _zone = PosixZone.UTC;

		private String _language = "en";

		private final Map<SunEvent, TimeOfDay> _sun = new LinkedHashMap<>();

		private final Set<String> _schedules = new TreeSet<>();

		private final Set<String> _outputs = new TreeSet<>();

		private final Set<String> _unreadable = new TreeSet<>();

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
			loadModel(_case.get("rules"));
			if (_case.get("persisted") instanceof Map<?, ?> persisted) {
				for (Map.Entry<?, ?> entry : persisted.entrySet()) {
					String key = (String) entry.getKey();
					Map<?, ?> stored = (Map<?, ?>) entry.getValue();
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
			_image = _case.get("rules");
			Map<?, ?> expected = (Map<?, ?>) _case.get("expected");
			_engine = newEngine();
			Refusal refusal = _engine.load(_image, _directory);
			if (expected.containsKey("loadRefused")) {
				if (refusal == null) {
					_failures.add("the load must be refused with " + expected.get("loadRefused"));
				} else if (!refusal.error().externalName().equals(expected.get("loadRefused"))) {
					_failures.add("load refused with " + refusal + ", expected " + expected.get("loadRefused"));
				}
				return;
			}
			if (refusal != null) {
				_failures.add("load refused: " + refusal);
				return;
			}
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

		private Engine newEngine() {
			Engine engine = new Engine(_model, () -> _now[0], _store, new Engine.TimeSource() {
				@Override
				public DateTime localNow() {
					return _synchronized ? _zone.local(_startUtc + Math.floorDiv(_now[0] - BASE, 1000L)) : null;
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
				long deadline = _engine.nextDeadline();
				if (deadline > target) {
					break;
				}
				_now[0] = Math.max(_now[0], deadline);
				_engine.processDueTimers();
			}
			_now[0] = target;
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
			} else if (step.get("setParameter") instanceof Map<?, ?> p) {
				_engine.setParameterJson((String) p.get("rule"), (String) p.get("parameter"), p.get("value"));
			} else if (step.get("clockSync") instanceof Boolean sync) {
				_synchronized = sync.booleanValue();
			} else if (step.get("image") instanceof Map<?, ?> image) {
				// The model is loaded again with the values kept: the parameter datapoints follow the
				// new rules (spec/rules.md 7.6).
				_engine.flush();
				addParameterDatapoints(image.get("rules"));
				Refusal refusal = _engine.load(image.get("rules"), _directory);
				if (refusal != null) {
					_failures.add("image at " + at + " refused: " + refusal);
					return;
				}
				_image = image.get("rules");
				_engine.start(StartReason.IMAGE);
			} else if (step.containsKey("reboot")) {
				_engine.close();
				loadModel(_image);
				_engine = newEngine();
				Refusal refusal = _engine.load(_image, _directory);
				if (refusal != null) {
					_failures.add("reboot at " + at + ": load refused: " + refusal);
					return;
				}
				_engine.start(StartReason.BOOT);
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
			if (expected.get("persisted") instanceof Map<?, ?> persisted) {
				Map<String, Val> stored = _store.overrides();
				for (Map.Entry<?, ?> entry : persisted.entrySet()) {
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
				String line = c.get("at") + " " + c.get("rule") + " " + name + " = " + text(want);
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
				String line = l.get("at") + " " + l.get("rule") + " " + l.get("code");
				line = "log".equals(l.get("code")) ? line + " " + l.get("level") + " \"" + l.get("message") + "\"" : line;
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
