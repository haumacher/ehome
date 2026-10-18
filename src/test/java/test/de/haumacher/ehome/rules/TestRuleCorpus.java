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
import de.haumacher.ehome.rules.RuleLoader.Refusal;
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
		if (!run._failures.isEmpty()) {
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
		assertEquals("The copy holds exactly the cases of the corpus index.", indexed, present);
	}

	/**
	 * One run of a case.
	 */
	private static final class CaseRun {

		private final Map<?, ?> _case;

		final List<String> _failures = new ArrayList<>();

		private final long[] _now = { BASE };

		private final ParameterStore _store = new ParameterStore();

		private boolean _synchronized;

		private long _startSeconds;

		private final Map<SunEvent, TimeOfDay> _sun = new LinkedHashMap<>();

		private final Set<String> _schedules = new TreeSet<>();

		private final List<String> _logs = new ArrayList<>();

		private final List<String> _commands = new ArrayList<>();

		private DatapointModel _model;

		private Engine _engine;

		private Object _image;

		CaseRun(Map<?, ?> json) {
			_case = json;
		}

		void run() {
			Map<?, ?> clock = (Map<?, ?>) _case.get("clock");
			if (clock != null) {
				String[] parts = ((String) clock.get("start")).split("[-T:]");
				_startSeconds = Values.daysOf(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2])) * 86400 + Integer.parseInt(parts[3]) * 3600
					+ Integer.parseInt(parts[4]) * 60 + Integer.parseInt(parts[5]);
				_synchronized = !Boolean.FALSE.equals(clock.get("synchronized"));
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
				for (Object id : schedules) {
					_schedules.add((String) id);
				}
			}
			loadModel();
			if (_case.get("persisted") instanceof Map<?, ?> persisted) {
				for (Map.Entry<?, ?> entry : persisted.entrySet()) {
					String key = (String) entry.getKey();
					Map<?, ?> stored = (Map<?, ?>) entry.getValue();
					Val value = Values.fromJson(Type.fromName((String) stored.get("type")), stored.get("value")).value();
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
			Refusal refusal = _engine.load(_image, _schedules::contains);
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
			compareOutputs(expected);
			if (expected.get("final") instanceof Map<?, ?> state) {
				compareState(state, "final");
			}
		}

		private Engine newEngine() {
			Engine engine = new Engine(_model, () -> _now[0], _store, new Engine.TimeSource() {
				@Override
				public DateTime localNow() {
					if (!_synchronized) {
						return null;
					}
					long s = _startSeconds + (_now[0] - BASE) / 1000;
					long days = Math.floorDiv(s, 86400L);
					long rest = s - days * 86400;
					Val.Date date = Values.civilOf(days);
					return new DateTime(date.year(), date.month(), date.day(), (int) (rest / 3600), (int) (rest % 3600 / 60),
						(int) (rest % 60), Values.weekdayOf(date.year(), date.month(), date.day()), 0);
				}

				@Override
				public TimeOfDay sunTime(SunEvent event) {
					return _sun.get(event);
				}
			});
			engine.setObserver(new Engine.Observer() {
				@Override
				public void ruleLogged(String rule, LogCode code, Level level, String text) {
					String line = (_now[0] - BASE) + " " + rule + " " + code.externalName();
					_logs.add(code == LogCode.LOG ? line + " " + level.externalName() + " \"" + text + "\"" : line);
				}

				@Override
				public void ruleCommanded(String rule, Datapoint datapoint, Val value) {
					_commands.add((_now[0] - BASE) + " " + rule + " " + datapoint.name() + " = " + value);
				}
			});
			return engine;
		}

		private void loadModel() {
			_model = new DatapointModel(() -> _now[0]);
			DatapointModel.Technology accepting = (datapoint, value) -> true;
			DatapointModel.Technology refusing = (datapoint, value) -> false;
			for (Object item : (List<?>) _case.get("datapoints")) {
				Map<?, ?> dp = (Map<?, ?>) item;
				ValueType type = ValueType.fromName((String) dp.get("type"));
				boolean writable = !Boolean.FALSE.equals(dp.get("writable"));
				boolean refuse = Boolean.TRUE.equals(dp.get("refuseCommands"));
				_model.add((String) dp.get("name"), type, writable, refuse ? refusing : accepting);
			}
		}

		private Val datapointValue(String name, Object json) {
			Datapoint datapoint = _model.get(name);
			Val value = expectedValue(datapoint.type().expressionType(), json);
			return Values.convertForDatapoint(value, datapoint.type());
		}

		private void report(String name, Object json) {
			_model.report(name, datapointValue(name, json));
		}

		/** Runs up to the time: the queued events, then every timer at its own deadline. */
		private void advance(long at) {
			long target = BASE + at;
			while (true) {
				if (_engine.hasPending()) {
					_engine.drain();
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

		private void apply(Map<?, ?> step, long at) {
			if (step.get("report") instanceof Map<?, ?> report) {
				report((String) report.get("datapoint"), report.get("value"));
			} else if (step.get("stale") instanceof String name) {
				_model.reportStale(name);
			} else if (step.get("command") instanceof Map<?, ?> command) {
				String name = (String) command.get("datapoint");
				_model.command(name, datapointValue(name, command.get("value")));
			} else if (step.get("schedule") instanceof String id) {
				_engine.scheduleFired(id);
			} else if (step.get("setParameter") instanceof Map<?, ?> p) {
				_engine.setParameterJson((String) p.get("rule"), (String) p.get("parameter"), p.get("value"));
			} else if (step.get("clockSync") instanceof Boolean sync) {
				_synchronized = sync.booleanValue();
			} else if (step.get("image") instanceof Map<?, ?> image) {
				Refusal refusal = _engine.load(image.get("rules"), _schedules::contains);
				if (refusal != null) {
					_failures.add("image at " + at + " refused: " + refusal);
					return;
				}
				_image = image.get("rules");
				_engine.start(StartReason.IMAGE);
			} else if (step.containsKey("reboot")) {
				_engine.close();
				loadModel();
				_engine = newEngine();
				Refusal refusal = _engine.load(_image, _schedules::contains);
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
			if (type == Type.DATE_TIME && json instanceof Map<?, ?> map && map.containsKey("dayOfWeek")) {
				// The corpus notation adds the day of the week to a date-time (README "Values").
				Map<Object, Object> copy = new LinkedHashMap<>(map);
				copy.remove("dayOfWeek");
				Val value = Values.fromJson(type, copy).value();
				if (value.dateTime().dayOfWeek() != ((Number) map.get("dayOfWeek")).intValue()) {
					_failures.add("day of week of the expected date-time " + json);
				}
				return value;
			}
			Values.Literal literal = Values.fromJson(type, json);
			if (!literal.isOk()) {
				throw new AssertionError("Expected value " + json + " of type " + type + ": " + literal.reason());
			}
			return literal.value();
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
				String line = c.get("at") + " " + c.get("rule") + " " + name + " = " + want;
				wantCommands.add(line);
				commandsOk &= i < _commands.size() && sameCommand(_commands.get(i), line, want);
			}
			commandsOk &= commands.size() == _commands.size();
			if (!commandsOk) {
				_failures.add("commands differ\nexpected:\n  " + String.join("\n  ", wantCommands) + "\nactual:\n  "
					+ String.join("\n  ", _commands));
			}
			List<String> wantLogs = new ArrayList<>();
			for (Object item : (List<?>) expected.get("logs")) {
				Map<?, ?> l = (Map<?, ?>) item;
				String line = l.get("at") + " " + l.get("rule") + " " + l.get("code");
				wantLogs.add("log".equals(l.get("code")) ? line + " " + l.get("level") + " \"" + l.get("message") + "\"" : line);
			}
			if (!wantLogs.equals(_logs)) {
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
