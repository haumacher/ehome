/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import static de.haumacher.ehome.ai.logic.LogicSupport.answer;
import static de.haumacher.ehome.ai.logic.LogicSupport.failure;
import static de.haumacher.ehome.ai.logic.LogicSupport.id;
import static de.haumacher.ehome.ai.logic.LogicSupport.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.monitor.BusLink;
import de.haumacher.ehome.monitor.BusTelegram;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.PosixZone;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.KnxBinding;
import de.haumacher.ehome.simulation.KnxValues;
import de.haumacher.ehome.simulation.ProjectSimulation;
import de.haumacher.ehome.simulation.Simulation;

/**
 * The assistant's simulation of a scenario (haumacher/ehome #128): the project's runtime image run
 * by E-Home's {@link Simulation} of the controller on a simulated clock, headless, for the steps of
 * a scenario; the answer is what the rules, the schedule entries and the virtual devices did, in
 * order, with the simulated time.
 *
 * <p>
 * The simulation is the one of the simulation view (<code>projects/simulation.view.xml</code>): the
 * same image, engine, scheduler, block runtime and KNX coding. Unlike the view it is not connected to
 * a virtual installation of the project's devices: a value is set as the bus, a Zigbee device or the
 * datapoint's source would report it, and a command of a rule goes to a bus that answers nothing. So
 * a device does not answer a command (an actuator's status address stays as it is unless the
 * scenario sets it), and a read stays unanswered. The parameters start at their defaults (the
 * overrides of the simulation view are not read and not written). The simulation is closed after
 * the scenario; nothing persistent changes.
 * </p>
 */
public class SimulationTools {

	/** The source address of the telegrams the scenario sends: 15.15.254. */
	private static final int SCENARIO_SOURCE = 0xFFFE;

	/** The most steps of one scenario. */
	private static final int MAX_STEPS = 200;

	/** The longest time one scenario may run the clock. */
	private static final long MAX_SIMULATED_MS = 400L * 24 * 3600 * 1000;

	/**
	 * Runs a scenario in the simulation.
	 */
	@ToolMethod(description = "Tries the rules of an E-Home project in the simulation of the controller, before"
		+ " anything is deployed: starts the project's runtime image at a local time (a boot), runs the steps"
		+ " of a scenario and answers what happened, in order, with the simulated local time: each value set,"
		+ " each command of a rule or virtual device (\"write Name (id) = value\"), the logs of the rules, the"
		+ " schedule firings and parameter changes, and the problems; then the final values of the datapoints"
		+ " the scenario set or the rules wrote, and the state and timers of each rule. Steps, in order:"
		+ " {\"set\": \"<datapoint id>\", \"value\": \"<literal>\"} (a sensor, bus or source reports the value),"
		+ " {\"wait\": \"5min\"} (the clock runs on, timers and schedules fire on the way), {\"until\": \"07:00\"}"
		+ " (to the next such local time) or {\"until\": \"2026-11-11T07:00\"}, {\"parameter\": \"<rule>/<param>\","
		+ " \"value\": \"<literal>\"} (as the inhabitant sets it). With 'definitions' (the form of saveAutomation),"
		+ " rules, schedule entries and datapoints not saved yet are tried in place of / besides the saved ones;"
		+ " nothing is saved. Devices are not simulated: an actuator does not answer a command, so set a"
		+ " status datapoint yourself if a rule depends on it. Nothing persistent changes.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = LogicSupport.PROJECT)
	public ToolResult simulate(ToolContext context,
			@P("The project, or any object of it") TLObject project,
			@P(value = "The local date and time the simulation starts at, e.g. \"2026-11-10T21:55\"; omitted: now",
				required = false) String start,
			@P("The steps of the scenario") List<Map<String, Object>> steps,
			@P(value = "Definitions to try without saving them, as saveAutomation takes them", required = false)
			Map<String, Object> definitions) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project.");
		}
		if (steps == null) {
			steps = List.of();
		}
		if (steps.size() > MAX_STEPS) {
			return failure("A scenario has at most " + MAX_STEPS + " steps.");
		}
		List<Map<String, Object>> scenario = steps;
		if (definitions == null || definitions.isEmpty()) {
			return run(owner, start, scenario);
		}
		RuleTools.Checked checked = RuleTools.check(context, owner, definitions);
		if (checked.failure() != null) {
			return checked.failure();
		}
		return Sandbox.run(context, () -> {
			new AutomationChange(owner).apply(checked.image());
			return run(owner, start, scenario);
		});
	}

	private static ToolResult run(TLObject project, String start, List<Map<String, Object>> steps) {
		RuntimeImage probe = RuntimeImage.build(project, 1, Instant.now(), false);
		PosixZone zone = zone(probe.getJson());
		long startUtc;
		if (start == null || start.isBlank()) {
			startUtc = Instant.now().getEpochSecond() / 60 * 60;
		} else {
			Val.DateTime local = dateTime(start);
			if (local == null) {
				return failure("'start' is no local date and time: write it as 2026-11-10T21:55.");
			}
			startUtc = zone.utc(local);
		}
		RuntimeImage image = RuntimeImage.build(project, 1, Instant.ofEpochSecond(startUtc));
		ImageReport report = image.validate();
		if (!report.isValid()) {
			List<Object> refusals = new ArrayList<>();
			for (ImageReport.Refusal refusal : report.getRefusals()) {
				refusals.add(LogicSupport.text(image.describe(refusal)));
			}
			return ToolResult.failure("The runtime image of the project is refused, so it cannot run (the controller"
				+ " would refuse it too). Fix these first (imageProblems names them with their objects):\n"
				+ AutomationChange.text(refusals));
		}
		Simulation simulation = new Simulation(image.getJson(), startUtc, new ParameterStore());
		try {
			simulation.setLanguage(Resources.getInstance().getLocale().getLanguage());
			simulation.setNames(ProjectSimulation.timelineNames(project));
			ScenarioBus bus = new ScenarioBus();
			simulation.connect(bus);
			String refusal = simulation.start();
			if (refusal != null) {
				return failure("The simulation refuses the image: " + refusal);
			}
			DatapointNames names = DatapointNames.of(project);
			Set<String> touched = new LinkedHashSet<>();
			List<Object> stepsDone = new ArrayList<>();
			int n = 0;
			for (Map<String, Object> step : steps) {
				String problem = step(simulation, bus, step, touched);
				if (problem != null) {
					return answer("Step " + (n + 1) + " " + Json.writeCompact(step) + " failed: " + problem
						+ " What happened before it:", outcome(simulation, names, touched, stepsDone));
				}
				stepsDone.add(simulation.timeText(simulation.now()) + " " + Json.writeCompact(step));
				n++;
			}
			return answer("The scenario ran.", outcome(simulation, names, touched, stepsDone));
		} catch (TopLogicException ex) {
			return failure("The scenario failed: " + LogicSupport.text(ex.getErrorKey()));
		} finally {
			simulation.close();
		}
	}

	/**
	 * Runs one step; the reason it cannot, <code>null</code> when it ran.
	 */
	private static String step(Simulation simulation, ScenarioBus bus, Map<String, Object> step, Set<String> touched) {
		if (step.get("set") != null) {
			String datapoint = String.valueOf(step.get("set"));
			if (simulation.model().get(datapoint) == null) {
				return "the image has no datapoint '" + datapoint + "' (automation lists the ids).";
			}
			Val value = ProjectSimulation.datapointValue(simulation, datapoint, text(step.get("value")));
			touched.add(datapoint);
			return set(simulation, bus, datapoint, value);
		}
		if (step.get("wait") != null) {
			Long ms = duration(text(step.get("wait")));
			if (ms == null || ms.longValue() < 0) {
				return "'wait' is no duration of the rule language (\"30s\", \"5min\", \"1h30min\").";
			}
			return advance(simulation, ms.longValue());
		}
		if (step.get("until") != null) {
			String until = text(step.get("until"));
			Long ms = until(simulation, until);
			if (ms == null) {
				return "'until' is no local time (\"07:00\") or date and time (\"2026-11-11T07:00\") after the"
					+ " simulated time " + simulation.timeText(simulation.now()) + ".";
			}
			return advance(simulation, ms.longValue());
		}
		if (step.get("parameter") != null) {
			String key = String.valueOf(step.get("parameter"));
			int slash = key.indexOf('/');
			if (slash < 0) {
				return "'parameter' is <rule id>/<parameter id>.";
			}
			String rule = key.substring(0, slash);
			String parameter = key.substring(slash + 1);
			Val current;
			try {
				current = simulation.parameter(rule, parameter);
			} catch (RuntimeException ex) {
				return "no parameter '" + key + "'.";
			}
			if (current == null) {
				return "no parameter '" + key + "'.";
			}
			Val value = literal(text(step.get("value")), current.type());
			if (value == null) {
				return "'" + step.get("value") + "' is no " + current.type().externalName() + " literal.";
			}
			Engine.ParameterStatus status = simulation.setParameter(rule, parameter, value);
			return status == Engine.ParameterStatus.OK ? null : "the parameter refuses the value: " + status;
		}
		return "unknown step; a step has one of the members set, wait, until or parameter.";
	}

	private static String set(Simulation simulation, ScenarioBus bus, String datapoint, Val value) {
		if (simulation.zigbee().point(datapoint) != null) {
			return simulation.zigbeeReport(datapoint, value);
		}
		KnxBinding.Mapping mapping = simulation.binding().mapping(datapoint);
		if (mapping != null && !mapping.addresses().isEmpty()) {
			byte[] data;
			try {
				data = KnxValues.encode(mapping.dpt(), value);
			} catch (KnxValues.CodingException ex) {
				return "the value cannot be coded as " + mapping.dpt().code() + ": " + ex.getMessage();
			}
			bus.deliver(new BusTelegram(System.currentTimeMillis(), SCENARIO_SOURCE, mapping.addresses().get(0).intValue(),
				BusTelegram.Service.WRITE, BusTelegram.PRIORITY_LOW, false, BusTelegram.DEFAULT_HOP_COUNT, data, false));
			return null;
		}
		String owner = simulation.owner(datapoint);
		if (owner != null || simulation.model().get(datapoint).technology() != null) {
			return "'" + datapoint + "' is owned by " + (owner != null ? owner : "the scheduler")
				+ "; the scenario cannot set it (a rule parameter is set with a 'parameter' step).";
		}
		return simulation.report(datapoint, value);
	}

	private static String advance(Simulation simulation, long ms) {
		if (simulation.now() + ms > MAX_SIMULATED_MS) {
			return "a scenario runs the clock at most 400 days.";
		}
		simulation.advance(ms);
		return null;
	}

	/** The milliseconds to the next local time of day, or to a local date and time, from now. */
	private static Long until(Simulation simulation, String text) {
		if (text == null) {
			return null;
		}
		long now = simulation.now();
		if (text.contains("T")) {
			Val.DateTime target = dateTime(text);
			if (target == null) {
				return null;
			}
			Val.DateTime local = simulation.localTime(now);
			long nowSeconds = Values.daysOf(local.year(), local.month(), local.day()) * 86400L + local.hour() * 3600L
				+ local.minute() * 60L + local.second();
			long targetSeconds = Values.daysOf(target.year(), target.month(), target.day()) * 86400L
				+ target.hour() * 3600L + target.minute() * 60L + target.second();
			long ms = (targetSeconds - nowSeconds) * 1000 - Math.floorMod(now, 1000L);
			return ms < 0 ? null : Long.valueOf(ms);
		}
		Object json;
		try {
			json = RuleText.parseValue(text, Type.TIME_OF_DAY);
		} catch (SyntaxError ex) {
			return null;
		}
		Map<?, ?> time = (Map<?, ?>) json;
		long target = ((Number) time.get("hour")).longValue() * 3600 + ((Number) time.get("minute")).longValue() * 60
			+ (time.get("second") instanceof Number s ? s.longValue() : 0);
		Val.DateTime local = simulation.localTime(now);
		long current = local.hour() * 3600L + local.minute() * 60L + local.second();
		long seconds = target - current;
		if (seconds <= 0) {
			seconds += 86400;
		}
		return Long.valueOf(seconds * 1000 - Math.floorMod(now, 1000L));
	}

	/**
	 * What the scenario did: the timeline without the bus traffic and the clock, the final values
	 * and the rules.
	 */
	private static Map<String, Object> outcome(Simulation simulation, DatapointNames names, Set<String> touched,
			List<Object> steps) {
		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> timeline = new ArrayList<>();
		for (Simulation.Entry entry : simulation.timeline()) {
			if (entry.kind() == Simulation.Kind.SENT || entry.kind() == Simulation.Kind.CLOCK) {
				continue;
			}
			timeline.add(simulation.timeText(entry.ms()) + " " + entry.kind().externalName() + " " + entry.source()
				+ ": " + entry.text());
			if (entry.kind() == Simulation.Kind.COMMAND && entry.text().startsWith("write ")) {
				// The datapoint the rule wrote: its id is the one in parentheses at the end of its name.
				String target = entry.text().substring("write ".length(), entry.text().lastIndexOf(" = "));
				int open = target.lastIndexOf(" (");
				touched.add(open >= 0 && target.endsWith(")") ? target.substring(open + 2, target.length() - 1) : target);
			}
		}
		result.put("timeline", timeline);
		if (simulation.dropped() > 0) {
			result.put("dropped", "The first " + simulation.dropped() + " entries of the timeline were dropped (at most "
				+ Simulation.TIMELINE_CAPACITY + " are kept).");
		}
		result.put("steps", steps);
		result.put("end", simulation.timeText(simulation.now()));
		Map<String, Object> values = new LinkedHashMap<>();
		for (String datapoint : touched) {
			if (simulation.model().get(datapoint) != null) {
				Val value = simulation.value(datapoint);
				values.put(names.datapoint(datapoint), value.has() ? Values.format(value) : "none");
			}
		}
		result.put("values", values);
		List<Object> rules = new ArrayList<>();
		RuleSet set = simulation.rules();
		if (set != null) {
			for (RuleSet.Rule rule : set.rules()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("rule", rule.id());
				String state = simulation.state(rule.id());
				if (state != null) {
					entry.put("state", state);
				}
				List<String> timers = new ArrayList<>();
				for (var timer : rule.timers()) {
					Long remaining = simulation.timerRemaining(rule.id(), timer.id());
					if (remaining != null) {
						timers.add(timer.id() + " runs " + Values.durationText(remaining.longValue()));
					}
				}
				if (!timers.isEmpty()) {
					entry.put("timers", timers);
				}
				Engine.RuleStatus status = simulation.status(rule.id());
				entry.put("firings", Integer.valueOf(status.firings()));
				if (status.errors() > 0) {
					entry.put("errors", Integer.valueOf(status.errors()));
				}
				rules.add(entry);
			}
		}
		result.put("rules", rules);
		return result;
	}

	// ---- values ------------------------------------------------------------------------------

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	private static Val.DateTime dateTime(String text) {
		Val value = literal(text, Type.DATE_TIME);
		return value == null ? null : value.dateTime();
	}

	private static Long duration(String text) {
		Val value = literal(text, Type.DURATION);
		return value == null ? null : Long.valueOf(value.integer());
	}

	private static Val literal(String text, Type type) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			Values.Literal literal = Values.fromJson(type, RuleText.parseValue(text.trim(), type));
			return literal.isOk() ? literal.value() : null;
		} catch (SyntaxError ex) {
			return null;
		}
	}

	private static PosixZone zone(Map<String, Object> image) {
		if (image.get("settings") instanceof Map<?, ?> settings && settings.get("tz") instanceof String tz) {
			return PosixZone.parse(tz);
		}
		return PosixZone.UTC;
	}

	/**
	 * The bus of a scenario: delivers the telegrams of the scenario to the simulation and takes its
	 * telegrams without answering them.
	 */
	private static final class ScenarioBus implements BusLink {

		private Listener _listener;

		@Override
		public void setListener(Listener listener) {
			_listener = listener;
		}

		/** A device of the bus sends the telegram. */
		void deliver(BusTelegram telegram) {
			_listener.received(telegram);
		}

		@Override
		public int localAddress() {
			return 0xFFFF;
		}

		@Override
		public String description() {
			return "the bus of the assistant's scenario";
		}

		@Override
		public void send(int destination, byte[] apdu) {
			// Nobody answers.
		}

		@Override
		public void close() {
			// Nothing to release.
		}
	}

}
