/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import static test.de.haumacher.ehome.simulation.LoopbackEndpoints.gateway;
import static test.de.haumacher.ehome.simulation.LoopbackEndpoints.loopback;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import test.de.haumacher.ehome.rules.TestRuleCorpus;
import test.de.haumacher.ehome.rules.TestRuleCorpus.CaseRun;

import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.monitor.CalimeroBusLink;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.simulation.KnxBinding;
import de.haumacher.ehome.simulation.KnxValues;
import de.haumacher.ehome.simulation.KnxValues.CodingException;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupDevice.Received;
import de.haumacher.ehome.virtual.device.GroupObject;
import de.haumacher.ehome.virtual.server.VirtualInstallation;

/**
 * The timelines of the rule corpus (<code>src/test/fixtures/homecontrol/rules/</code>,
 * {@link TestRuleCorpus}) replayed against a {@link VirtualInstallation}: the datapoints of a case
 * are bound to group addresses by a {@link KnxBinding}, each with the datapoint type of its value
 * type ({@link #DPTS}); a virtual device of the case sends the reports of the timeline as group
 * telegrams and receives the commands of the rules. A case passes when its outputs are those the
 * corpus expects and every telegram the binding sent reached the device unchanged.
 *
 * <p>
 * A case is replayed where its datapoints map to group addresses ({@link #unmappable(Map)}): every
 * value type has a datapoint type, every value reported travels the bus exactly (a real as a 4-octet
 * float, a string of at most 14 characters, a controlled value of a step), every value commanded
 * can be sent, and the timeline reports no single datapoint stale (a bus loses all of them at
 * once). {@link #testMappable()} pins which cases these are.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRuleCorpusOnBus extends TestCase {

	/** The datapoint type of each value type on the bus. */
	static final Map<ValueType, Dpt> DPTS = new LinkedHashMap<>();
	static {
		DPTS.put(ValueType.BOOLEAN, Dpt.parse("1.001"));
		DPTS.put(ValueType.UNSIGNED, Dpt.parse("12.001"));
		DPTS.put(ValueType.SIGNED, Dpt.parse("13.001"));
		DPTS.put(ValueType.REAL, Dpt.parse("14"));
		DPTS.put(ValueType.STRING, Dpt.parse("16.001"));
		DPTS.put(ValueType.TIME_OF_DAY, Dpt.parse("10.001"));
		DPTS.put(ValueType.DATE, Dpt.parse("11.001"));
		DPTS.put(ValueType.DATE_TIME, Dpt.parse("19.001"));
		DPTS.put(ValueType.ENUMERATION, Dpt.parse("20"));
		DPTS.put(ValueType.CONTROLLED, Dpt.parse("3.007"));
		DPTS.put(ValueType.COLOR, Dpt.parse("251.600"));
	}

	/**
	 * The cases replayed on the bus. Not replayed: 02, 03, 13, 14, 46 (a single datapoint reported
	 * stale), 12 (a string command of 18 characters), 19 (the humidity 69.9 arrives as the float
	 * 69.90000153), 44 (the reads of a single datapoint refused). A read of a rule travels the bus as a
	 * GroupValueRead the device of the case does not answer (its objects have no read flag); the
	 * datapoints of the rule parameters are the engine's and those of the adjustable schedule times
	 * the scheduler's, not on the bus.
	 */
	private static final List<String> MAPPABLE = List.of(
		"01-trigger-datapoint-change.json",
		"04-trigger-schedule.json",
		"05-timer-presence-light.json",
		"06-trigger-start.json",
		"07-trigger-parameter.json",
		"08-operators-arithmetic.json",
		"09-operators-comparison-logic.json",
		"10-operators-duration-time.json",
		"11-functions-numeric.json",
		"15-eval-error-once.json",
		"16-write-refused-onlyonchange.json",
		"17-time-functions.json",
		"18-history-since-change.json",
		"20-timers-start-restart-cancel.json",
		"21-state-machine-staircase.json",
		"22-state-machine-order.json",
		"23-queue-order.json",
		"24-loop-guard.json",
		"25-image-swap-state-timers.json",
		"26-image-swap-parameters.json",
		"27-parameter-persistence-reboot.json",
		"28-enable-switch.json",
		"29-set-parameter-action.json",
		"30-log-format.json",
		"31-load-refused-type-error.json",
		"32-load-refused-match-time.json",
		"33-load-refused-unknown-timer.json",
		"34-example-heating-setback.json",
		"35-example-blind-sun-wind.json",
		"36-parameter-reset-to-default.json",
		"37-state-persistence-reboot.json",
		"38-date-time-across-dst.json",
		"39-load-refused-schedule-output.json",
		"40-labels-language.json",
		"41-canonical-normalization.json",
		"42-state-override-fold-labels.json",
		"43-action-read.json",
		"45-load-refused-not-readable.json",
		"47-silence-enable-parameter.json",
		"48-silence-image-swap.json",
		"49-silence-reboot.json",
		"50-parameter-datapoint-change.json",
		"51-parameter-datapoint-command.json",
		"52-parameter-datapoint-other-rule.json",
		"53-parameter-datapoint-enabled.json",
		"54-silence-read-parameter-datapoint.json",
		"55-schedule-adjust-time.json",
		"56-schedule-change-semantics.json",
		"57-schedule-time-datapoint.json",
		"58-schedule-image-swap-overrides.json",
		"59-sun-position.json",
		"60-sun-position-nrel-example.json",
		"61-sun-position-no-clock.json",
		"62-sun-position-no-location.json",
		"63-load-refused-match-sun.json",
		"64-sun-follow-timer.json");

	private static final long TIMEOUT = 5000;

	private static VirtualInstallation _installation;

	private static BusLink _link;

	private static final BlockingQueue<BusTelegram> INBOX = new LinkedBlockingQueue<>();

	private final File _file;

	private final int _index;

	public TestRuleCorpusOnBus(File file, int index) {
		super(file == null ? "mappable" : file.getName());
		_file = file;
		_index = index;
	}

	@Override
	protected void runTest() throws Throwable {
		if (_file == null) {
			testMappable();
			return;
		}
		Map<?, ?> json = read(_file);
		INBOX.clear();
		BusRun run = new BusRun(json, _index);
		try {
			run.run();
		} finally {
			_installation.detach(run._device);
		}
		if (!run.failures().isEmpty()) {
			fail(_file.getName() + ": " + json.get("title") + "\n" + String.join("\n", run.failures()));
		}
	}

	/**
	 * Which cases are replayed, and why the others are not.
	 */
	public void testMappable() throws Exception {
		Map<String, String> reasons = new TreeMap<>();
		List<String> mappable = new ArrayList<>();
		for (File file : cases()) {
			String reason = unmappable(read(file));
			if (reason == null) {
				mappable.add(file.getName());
			} else {
				reasons.put(file.getName(), reason);
			}
		}
		assertEquals(reasons.toString(), MAPPABLE, mappable);
	}

	/**
	 * Why the case cannot be replayed on the bus, <code>null</code> if it can.
	 */
	static String unmappable(Map<?, ?> json) {
		Map<String, ValueType> types = new LinkedHashMap<>();
		for (Object item : (List<?>) json.get("datapoints")) {
			Map<?, ?> dp = (Map<?, ?>) item;
			ValueType type = ValueType.fromName((String) dp.get("type"));
			if (!DPTS.containsKey(type)) {
				return "no datapoint type for " + type;
			}
			if (Boolean.TRUE.equals(dp.get("refuseReads"))) {
				return "the reads of a single datapoint refused (a bus refuses all of them or none)";
			}
			types.put((String) dp.get("name"), type);
		}
		List<Object[]> reported = new ArrayList<>();
		List<Object[]> commanded = new ArrayList<>();
		if (json.get("initial") instanceof Map<?, ?> initial) {
			for (Map.Entry<?, ?> entry : initial.entrySet()) {
				reported.add(new Object[] { entry.getKey(), entry.getValue() });
			}
		}
		if (json.get("timeline") instanceof List<?> timeline) {
			for (Object item : timeline) {
				Map<?, ?> step = (Map<?, ?>) item;
				if (step.containsKey("stale")) {
					return "a single datapoint reported stale";
				}
				if (step.get("report") instanceof Map<?, ?> report) {
					reported.add(new Object[] { report.get("datapoint"), report.get("value") });
				}
				if (step.get("command") instanceof Map<?, ?> command) {
					commanded.add(new Object[] { command.get("datapoint"), command.get("value") });
				}
			}
		}
		Map<?, ?> expected = (Map<?, ?>) json.get("expected");
		if (expected.get("commands") instanceof List<?> commands) {
			for (Object item : commands) {
				Map<?, ?> command = (Map<?, ?>) item;
				commanded.add(new Object[] { command.get("datapoint"), command.get("value") });
			}
		}
		// The datapoints of the rule parameters are the rule engine's, those of the schedules' times the
		// scheduler's, not on the bus.
		reported.removeIf(report -> engineOwned((String) report[0]));
		commanded.removeIf(command -> engineOwned((String) command[0]));
		for (Object[] report : reported) {
			ValueType type = types.get(report[0]);
			Val value = value(type, report[1]);
			try {
				Val back = KnxValues.decode(DPTS.get(type), KnxValues.encode(DPTS.get(type), value));
				Val converted = Values.convertForDatapoint(back, type);
				if (converted == null || !Val.identical(converted, value)) {
					return "the report " + report[0] + " = " + value + " arrives as " + back;
				}
			} catch (CodingException ex) {
				return "the report " + report[0] + " = " + value + " cannot be sent: " + ex.getMessage();
			}
		}
		for (Object[] command : commanded) {
			ValueType type = types.get(command[0]);
			Val value = value(type, command[1]);
			try {
				KnxValues.encode(DPTS.get(type), value);
			} catch (CodingException ex) {
				return "the command " + command[0] + " = " + value + " cannot be sent: " + ex.getMessage();
			}
		}
		return null;
	}

	private static boolean engineOwned(String datapoint) {
		return datapoint.startsWith(RuleSet.PARAMETER_DATAPOINT_PREFIX) || datapoint.startsWith(Schedule.DATAPOINT_PREFIX);
	}

	private static Val value(ValueType type, Object json) {
		Object plain = json;
		boolean summer = false;
		if (json instanceof Map<?, ?> map && type == ValueType.DATE_TIME) {
			Map<Object, Object> copy = new LinkedHashMap<>(map);
			copy.remove("dayOfWeek");
			summer = Boolean.TRUE.equals(copy.remove("summerTime"));
			plain = copy;
		}
		Type expression = type.expressionType();
		Values.Literal literal =
			Values.fromJson(expression == Type.INTEGER && json instanceof Double ? Type.REAL : expression, plain);
		Val result = json == null || !literal.isOk() ? null : Values.convertForDatapoint(literal.value(), type);
		if (result != null && summer) {
			DateTime d = result.dateTime();
			result = Val.ofDateTime(new DateTime(d.year(), d.month(), d.day(), d.hour(), d.minute(), d.second(),
				d.dayOfWeek(), d.flags() | DateTime.SUMMER_TIME));
		}
		return result == null ? Val.none(expression) : result;
	}

	/**
	 * A run of a case whose datapoints are bound to the bus.
	 */
	private static final class BusRun extends CaseRun {

		private final int _index;

		private final GroupDevice _device;

		private final Map<String, Integer> _objects = new LinkedHashMap<>();

		private final Map<String, Integer> _addresses = new LinkedHashMap<>();

		private final List<String> _sent = new ArrayList<>();

		private KnxBinding _binding;

		BusRun(Map<?, ?> json, int index) {
			super(json);
			_index = index;
			_device = new GroupDevice("Corpus device " + index, 0x1100 | (index + 1));
			int n = 0;
			for (Object item : (List<?>) json.get("datapoints")) {
				Map<?, ?> dp = (Map<?, ?>) item;
				String name = (String) dp.get("name");
				ValueType type = ValueType.fromName((String) dp.get("type"));
				// Main group 1..; one middle group per case, one address per datapoint.
				int address = (1 + index / 8) << 11 | (index % 8) << 8 | n;
				_objects.put(name, Integer.valueOf(n));
				_addresses.put(name, Integer.valueOf(address));
				_device.add(GroupObject.of(n, name, bits(DPTS.get(type)),
					GroupObject.COMMUNICATION | GroupObject.WRITE | GroupObject.TRANSMIT, address));
				n++;
			}
			_installation.attach(_device);
		}

		private static int bits(Dpt dpt) {
			switch (dpt.main()) {
				case 1:
					return 1;
				case 3:
					return 4;
				case 10:
				case 11:
					return 24;
				case 12:
				case 13:
				case 14:
					return 32;
				case 16:
					return 112;
				case 19:
					return 64;
				case 251:
					return 48;
				default:
					return 8;
			}
		}

		@Override
		protected void createModel() {
			_model = new DatapointModel(() -> _now[0]);
			_binding = new KnxBinding(_model, this::send, text -> {
				// The refusals show as the engine's writeRefused.
			});
			for (Object item : (List<?>) _case.get("datapoints")) {
				Map<?, ?> dp = (Map<?, ?>) item;
				String name = (String) dp.get("name");
				ValueType type = ValueType.fromName((String) dp.get("type"));
				boolean writable = !Boolean.FALSE.equals(dp.get("writable"));
				// A datapoint refusing commands has no sending address: the binding refuses them.
				boolean refuse = Boolean.TRUE.equals(dp.get("refuseCommands"));
				_model.add(name, type, writable, _binding);
				int address = _addresses.get(name).intValue();
				_binding.bind(new KnxBinding.Mapping(name, DPTS.get(type), refuse ? -1 : address,
					refuse ? List.of(Integer.valueOf(address)) : List.of(), true, false, false));
			}
		}

		private void send(int destination, byte[] apdu) {
			_sent.add(destination + " " + HexFormat.of().formatHex(apdu));
			try {
				_link.send(destination, apdu);
			} catch (BusException ex) {
				throw new AssertionError(ex);
			}
		}

		@Override
		protected void report(String name, Object json) throws Exception {
			ValueType type = _model.get(name).type();
			byte[] data = KnxValues.encode(DPTS.get(type), datapointValue(name, json));
			_installation.set(_device, _objects.get(name).intValue(), data);
			BusTelegram telegram = INBOX.poll(TIMEOUT, TimeUnit.MILLISECONDS);
			assertNotNull("The report of " + name + " did not arrive.", telegram);
			assertEquals(_addresses.get(name).intValue(), telegram.destination());
			_binding.received(telegram.source(), telegram.destination(), telegram.service(), telegram.data());
		}

		@Override
		protected boolean deliverLocal() {
			if (!_binding.hasSent()) {
				return false;
			}
			_binding.deliverSent();
			return true;
		}

		@Override
		protected void finish() throws Exception {
			if (!_device.awaitReceived(_sent.size(), TIMEOUT)) {
				_failures.add("the device received " + _device.received().size() + " of " + _sent.size() + " telegrams");
			}
			List<String> received = new ArrayList<>();
			for (Received telegram : _device.received()) {
				byte[] data = telegram.data();
				Dpt dpt = DPTS.get(_model.get(nameOf(telegram.destination())).type());
				received.add(telegram.destination() + " " + HexFormat.of().formatHex(KnxBinding.apdu(telegram.service(), dpt, data)));
			}
			if (!received.equals(_sent)) {
				_failures.add("telegrams on the bus differ\nsent:\n  " + String.join("\n  ", _sent) + "\nreceived:\n  "
					+ String.join("\n  ", received));
			}
		}

		private String nameOf(int address) {
			for (Map.Entry<String, Integer> entry : _addresses.entrySet()) {
				if (entry.getValue().intValue() == address) {
					return entry.getKey();
				}
			}
			throw new AssertionError("No datapoint at " + address);
		}

		@Override
		public String toString() {
			return "case " + _index;
		}
	}

	private static Map<?, ?> read(File file) throws Exception {
		return (Map<?, ?>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), 100);
	}

	private static File[] cases() {
		File[] files = TestRuleCorpus.CORPUS.listFiles((dir, name) -> name.endsWith(".json"));
		Arrays.sort(files);
		return files;
	}

	public static Test suite() throws Exception {
		TestSuite suite = new TestSuite(TestRuleCorpusOnBus.class.getName());
		suite.addTest(new TestRuleCorpusOnBus(null, -1));
		File[] files = cases();
		for (int index = 0; index < files.length; index++) {
			if (unmappable(read(files[index])) == null) {
				suite.addTest(new TestRuleCorpusOnBus(files[index], index));
			}
		}
		return new TestSetup(suite) {
			@Override
			protected void setUp() throws Exception {
				_installation = VirtualInstallation.start(loopback(), "E-Home corpus test", 1,
					new File("target/virtual/corpus"));
				_link = CalimeroBusLink.open(gateway(_installation.endpoint()));
				_link.setListener(new BusLink.Listener() {
					@Override
					public void received(BusTelegram telegram) {
						INBOX.add(telegram);
					}

					@Override
					public void closed(String reason) {
						// Shown by the next poll that times out.
					}
				});
			}

			@Override
			protected void tearDown() throws Exception {
				if (_link != null) {
					_link.close();
					_link = null;
				}
				if (_installation != null) {
					_installation.close();
					_installation = null;
				}
			}
		};
	}

}
