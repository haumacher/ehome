/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.io.File;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.monitor.CalimeroBusLink;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.MonitorRow;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.Engine.ParameterStatus;
import de.haumacher.ehome.rules.Engine.RuleStatus;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.simulation.KnxValues.CodingException;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupObject;
import de.haumacher.ehome.virtual.device.ProjectGroupDevices;
import de.haumacher.ehome.virtual.server.VirtualInstallation;
import de.haumacher.ehome.virtual.server.VirtualInterface.Endpoint;

/**
 * The simulation of a project: its runtime image run by a {@link Simulation} against a
 * {@link VirtualInstallation} of its devices ({@link ProjectGroupDevices}) on the loopback address,
 * with the parameter overrides stored at its rules ({@link Overrides}).
 *
 * <p>
 * The installation is reached by any tool as a KNXnet/IP gateway at {@link #endpoint()} (the group
 * monitor of the project with that gateway shows its traffic). Beside the devices of the project it
 * holds an input device ({@link #INPUT_ADDRESS}) that sends a value to an address no device of the
 * project sends to ({@link #send(String, String)}).
 * </p>
 */
public final class ProjectSimulation implements AutoCloseable {

	/** The individual address of the input device of the installation, 15.15.1. */
	public static final int INPUT_ADDRESS = 0xFF01;

	/** The first UDP port tried for the installation, below the ephemeral range. */
	private static final int FIRST_PORT = 23671;

	private static final int PORTS = 1000;

	/** The longest step of the clock, in milliseconds. */
	private static final long DAY = 86_400_000L;

	/** How many tunnelling connections the installation accepts: the simulation and some tools. */
	private static final int TUNNELS = 4;

	private final TLObject _project;

	private final VirtualInstallation _installation;

	private final Simulation _simulation;

	private final ParameterStore _store;

	private final GroupDevice _input = new GroupDevice("E-Home input", INPUT_ADDRESS);

	private ProjectSimulation(TLObject project, VirtualInstallation installation, Simulation simulation,
			ParameterStore store) {
		_project = project;
		_installation = installation;
		_simulation = simulation;
		_store = store;
	}

	/**
	 * Starts the simulation of the project.
	 *
	 * @param startUtc
	 *        The instant the simulated clock starts at, in seconds since the epoch.
	 * @param workDir
	 *        The directory for the files of the KNXnet/IP server.
	 * @throws TopLogicException
	 *         If the image of the project is not valid, its rules or schedules are refused, or the
	 *         installation cannot be started.
	 */
	public static ProjectSimulation start(TLObject project, long startUtc, File workDir) {
		RuntimeImage image = RuntimeImage.build(project, 1, Instant.ofEpochSecond(startUtc));
		ImageReport report = image.validate();
		if (!report.isValid()) {
			List<String> refusals = new ArrayList<>();
			for (ImageReport.Refusal refusal : report.getRefusals()) {
				refusals.add(refusal.toString());
			}
			throw new TopLogicException(I18NConstants.ERROR_IMAGE_INVALID__PROBLEMS.fill(String.join("; ", refusals)));
		}
		ParameterStore store = Overrides.load(project);
		VirtualInstallation installation =
			VirtualInstallation.start(freeLoopback(), "E-Home simulation", TUNNELS, workDir);
		Simulation simulation = null;
		try {
			List<String> problems = new ArrayList<>();
			for (GroupDevice device : ProjectGroupDevices.ofProject(project, problems)) {
				installation.attach(device);
			}
			simulation = new Simulation(image.getJson(), startUtc, store);
			for (String problem : problems) {
				simulation.reportProblem("-", problem);
			}
			simulation.connect(CalimeroBusLink.open(new InetSocketAddress(installation.endpoint().address(),
				installation.endpoint().port())));
			String refusal = simulation.start();
			if (refusal != null) {
				throw new TopLogicException(I18NConstants.ERROR_IMAGE_REFUSED__REASON.fill(refusal));
			}
			ProjectSimulation result = new ProjectSimulation(project, installation, simulation, store);
			installation.attach(result._input);
			return result;
		} catch (BusException ex) {
			close(simulation, installation);
			throw new TopLogicException(I18NConstants.ERROR_NO_CONNECTION__DETAIL.fill(ex.getMessage()), ex);
		} catch (RuntimeException ex) {
			close(simulation, installation);
			throw ex;
		}
	}

	private static void close(Simulation simulation, VirtualInstallation installation) {
		if (simulation != null) {
			simulation.close();
		}
		installation.close();
	}

	/**
	 * A free UDP port on the loopback address, below the ephemeral range.
	 */
	private static Endpoint freeLoopback() {
		InetAddress loopback = InetAddress.getLoopbackAddress();
		for (int port = FIRST_PORT; port < FIRST_PORT + PORTS; port++) {
			try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback, port))) {
				return new Endpoint(loopback, port, false);
			} catch (SocketException ex) {
				// In use, try the next one.
			}
		}
		throw new TopLogicException(I18NConstants.ERROR_NO_FREE_PORT);
	}

	/**
	 * The project.
	 */
	public TLObject project() {
		return _project;
	}

	/**
	 * The simulation.
	 */
	public Simulation simulation() {
		return _simulation;
	}

	/**
	 * The virtual installation.
	 */
	public VirtualInstallation installation() {
		return _installation;
	}

	/**
	 * Where the installation is reached.
	 */
	public Endpoint endpoint() {
		return _installation.endpoint();
	}

	/**
	 * Runs the clock forward by the duration (a literal, e.g. <code>30min</code>).
	 */
	public void advance(String duration) {
		Val value = parse(duration, Type.DURATION);
		if (value == null || value.integer() <= 0) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_DURATION__TEXT.fill(duration));
		}
		_simulation.advance(value.integer());
		saveOverrides();
	}

	/**
	 * Runs the clock forward to the next timer, schedule firing or midnight, at most by a day.
	 */
	public void step() {
		_simulation.step(DAY);
		saveOverrides();
	}

	/**
	 * Lets the clock run by itself at the given multiple of real time (1, 10, 60).
	 */
	public void run(int speed) {
		_simulation.run(speed);
	}

	/**
	 * Stops the clock running by itself; the overrides the rules set meanwhile are written to the
	 * project.
	 */
	public void pause() {
		_simulation.pause();
		saveOverrides();
	}

	/**
	 * A device of the installation sends the value to the address of the datapoint: the device whose
	 * object sends to the address, else the input device.
	 *
	 * @param value
	 *        A literal of the datapoint's type (<code>true</code>, <code>21.5</code>).
	 */
	public void send(String datapoint, String value) {
		KnxBinding.Mapping mapping = _simulation.binding().mapping(datapoint);
		if (mapping == null || mapping.addresses().isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_NOT_ON_BUS__DATAPOINT.fill(datapoint));
		}
		Val parsed = datapointValue(datapoint, value);
		byte[] data;
		try {
			data = KnxValues.encode(mapping.dpt(), parsed);
		} catch (CodingException ex) {
			throw new TopLogicException(
				I18NConstants.ERROR_INVALID_VALUE__TEXT_DATAPOINT_DETAIL.fill(value, datapoint, ex.getMessage()));
		}
		int address = mapping.addresses().get(0).intValue();
		for (GroupDevice device : _installation.devices()) {
			for (GroupObject object : device.objects()) {
				if (object.send() == address && object.fits(data) && device != _input) {
					_installation.set(device, object.number(), data);
					return;
				}
			}
		}
		int number = inputObject(address, mapping.dpt(), data);
		_installation.set(_input, number, data);
	}

	private int inputObject(int address, Dpt dpt, byte[] data) {
		for (GroupObject object : _input.objects()) {
			if (object.send() == address && object.fits(data)) {
				return object.number();
			}
		}
		int number = _input.objects().size();
		_input.add(GroupObject.of(number, RuntimeImage.threeLevel(address) + " (" + dpt.code() + ")",
			dpt.main() <= 3 ? 6 : data.length * 8, GroupObject.COMMUNICATION | GroupObject.TRANSMIT, address));
		return number;
	}

	/**
	 * Commands the datapoint as the UI of a controller does.
	 */
	public void command(String datapoint, String value) {
		CommandStatus status = _simulation.command(datapoint, datapointValue(datapoint, value));
		if (status != CommandStatus.OK) {
			throw new TopLogicException(I18NConstants.ERROR_COMMAND_REFUSED__DATAPOINT_REASON.fill(datapoint, status.text()));
		}
	}

	/**
	 * Sets a parameter (<code>rule/parameter</code>) as the UI of a controller does; the override is
	 * stored with the project.
	 */
	public void setParameter(String key, String value) {
		String[] parts = split(key);
		Type type = _simulation.parameter(parts[0], parts[1]).type();
		Val parsed = parse(value, type);
		if (parsed == null) {
			throw new TopLogicException(
				I18NConstants.ERROR_INVALID_PARAMETER_VALUE__TEXT_PARAMETER.fill(value, key));
		}
		ParameterStatus status = _simulation.setParameter(parts[0], parts[1], parsed);
		saveOverrides();
		if (status != ParameterStatus.OK) {
			throw new TopLogicException(I18NConstants.ERROR_PARAMETER_REFUSED__PARAMETER_REASON.fill(key, status));
		}
	}

	/**
	 * Removes the override of a parameter (<code>rule/parameter</code>).
	 */
	public void resetParameter(String key) {
		String[] parts = split(key);
		ParameterStatus status = _simulation.resetParameter(parts[0], parts[1]);
		saveOverrides();
		if (status != ParameterStatus.OK) {
			throw new TopLogicException(I18NConstants.ERROR_PARAMETER_REFUSED__PARAMETER_REASON.fill(key, status));
		}
	}

	private static String[] split(String key) {
		int slash = key == null ? -1 : key.indexOf('/');
		if (slash < 0) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PARAMETER);
		}
		return new String[] { key.substring(0, slash), key.substring(slash + 1) };
	}

	/**
	 * Writes the overrides the simulation holds to the project.
	 */
	public void saveOverrides() {
		if (_project.tValid()) {
			Overrides.save(_project, _store);
		}
	}

	private Val datapointValue(String datapoint, String text) {
		Datapoint dp = _simulation.model().get(datapoint);
		if (dp == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_DATAPOINT);
		}
		Val parsed = parse(text, dp.type().expressionType());
		if (parsed == null && dp.type().expressionType() == Type.INTEGER) {
			parsed = parse(text, Type.REAL);
		}
		Val converted = parsed == null ? null : Values.convertForDatapoint(parsed, dp.type());
		if (converted == null) {
			throw new TopLogicException(I18NConstants.ERROR_INVALID_VALUE__TEXT_DATAPOINT_DETAIL.fill(text, datapoint,
				dp.type().externalName()));
		}
		return converted;
	}

	private static Val parse(String text, Type type) {
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

	/**
	 * What the view shows of the simulation: <code>time</code>, <code>next</code>, <code>speed</code>
	 * (of the clock running by itself, 0 while it does not), <code>endpoint</code>,
	 * <code>devices</code>, the rows <code>rules</code>, <code>parameters</code>,
	 * <code>datapoints</code> and <code>timeline</code> (newest first). A row is a {@link MonitorRow}
	 * standing for its rule, parameter, datapoint or entry, so that a table keeps its selection across
	 * updates.
	 */
	public Map<String, Object> snapshot() {
		Map<String, Object> result = new LinkedHashMap<>();
		synchronized (_simulation) {
			Simulation s = _simulation;
			result.put("time", s.timeText(s.now()));
			long next = s.nextEvent();
			result.put("next", next == Long.MAX_VALUE ? null : s.timeText(next));
			result.put("speed", Integer.valueOf(s.speed()));
			result.put("endpoint", _installation.endpoint().toString());
			result.put("devices", Integer.valueOf(_installation.devices().size() - 1));
			result.put("rules", rules(s));
			result.put("parameters", parameters(s));
			result.put("datapoints", datapoints(s));
			List<Object> timeline = new ArrayList<>();
			long number = s.dropped();
			for (Simulation.Entry entry : s.timeline()) {
				Map<String, Object> row = new MonitorRow(Long.valueOf(number++));
				row.put("time", s.timeText(entry.ms()));
				row.put("kind", entry.kind().externalName());
				row.put("source", entry.source());
				row.put("text", entry.text());
				timeline.add(0, row);
			}
			result.put("timeline", timeline);
		}
		return result;
	}

	private static List<Object> rules(Simulation s) {
		List<Object> result = new ArrayList<>();
		RuleSet set = s.rules();
		Engine engine = s.engine();
		for (int r = 0; r < set.rules().size(); r++) {
			RuleSet.Rule rule = set.rules().get(r);
			RuleStatus status = engine.status(r);
			Map<String, Object> row = new MonitorRow(rule.id());
			row.put("id", rule.id());
			row.put("name", rule.name());
			row.put("enabled", Boolean.valueOf(engine.enabled(r)));
			row.put("state", engine.currentState(r));
			row.put("firings", Integer.valueOf(status.firings()));
			row.put("trigger", status.lastTrigger() == null ? null
				: status.lastTrigger() + " (" + s.timeText(status.lastTriggerMs()) + ")");
			row.put("action", status.lastAction() == null ? null
				: status.lastAction() + " (" + s.timeText(status.lastActionMs()) + ")");
			row.put("errors", Integer.valueOf(status.errors()));
			row.put("error", status.lastErrorCode() == null ? null : status.lastErrorCode().externalName() + ": "
				+ status.lastErrorText() + " (" + s.timeText(status.lastErrorMs()) + ")");
			List<String> timers = new ArrayList<>();
			for (int t = 0; t < rule.timers().size(); t++) {
				Long remaining = engine.timerRemaining(r, t);
				timers.add(rule.timers().get(t).id() + " "
					+ (remaining == null ? "–" : Values.durationText(remaining.longValue())));
			}
			row.put("timers", String.join(", ", timers));
			List<String> parameters = new ArrayList<>();
			for (int p = 1; p < rule.parameters().size(); p++) {
				parameters.add(rule.parameters().get(p).id() + " = " + Values.format(engine.parameterValue(r, p))
					+ (engine.parameterOverridden(r, p) ? " *" : ""));
			}
			row.put("parameters", String.join(", ", parameters));
			result.add(row);
		}
		return result;
	}

	private static List<Object> parameters(Simulation s) {
		List<Object> result = new ArrayList<>();
		RuleSet set = s.rules();
		Engine engine = s.engine();
		for (int r = 0; r < set.rules().size(); r++) {
			RuleSet.Rule rule = set.rules().get(r);
			for (int p = 0; p < rule.parameters().size(); p++) {
				RuleSet.Parameter parameter = rule.parameters().get(p);
				Map<String, Object> row = new MonitorRow(ParameterStore.key(rule.id(), parameter.id()));
				row.put("rule", rule.id());
				row.put("parameter", parameter.id());
				row.put("label", parameter.label());
				row.put("type", parameter.type().externalName());
				row.put("value", literal(engine.parameterValue(r, p)));
				row.put("default", literal(parameter.defaultValue()));
				row.put("overridden", Boolean.valueOf(engine.parameterOverridden(r, p)));
				row.put("adjustable", Boolean.valueOf(parameter.adjustable()));
				result.add(row);
			}
		}
		return result;
	}

	private static String literal(Val value) {
		if (!value.has()) {
			return "";
		}
		try {
			return Overrides.text(value, value.type());
		} catch (RuntimeException ex) {
			return Values.format(value);
		}
	}

	private List<Object> datapoints(Simulation s) {
		List<Object> result = new ArrayList<>();
		for (Datapoint dp : s.model().datapoints()) {
			Map<String, Object> row = new MonitorRow(dp.name());
			row.put("id", dp.name());
			row.put("type", dp.type().externalName());
			row.put("value", dp.quality() == Quality.UNKNOWN ? "" : Values.format(dp.value()));
			row.put("quality", dp.quality().externalName());
			row.put("updated", dp.quality() == Quality.UNKNOWN ? "" : s.timeText(dp.updatedMs()));
			KnxBinding.Mapping mapping = s.binding().mapping(dp.name());
			String binding;
			if (mapping != null) {
				List<String> addresses = new ArrayList<>();
				for (Integer address : mapping.addresses()) {
					addresses.add(RuntimeImage.threeLevel(address.intValue()));
				}
				binding = String.join(", ", addresses) + " (" + mapping.dpt().code() + ")";
			} else {
				String owner = s.owner(dp.name());
				binding = owner != null ? owner : dp.technology() != null ? "schedule" : "application";
			}
			row.put("binding", binding);
			row.put("writable", Boolean.valueOf(dp.writable()));
			row.put("bus", Boolean.valueOf(mapping != null && !mapping.addresses().isEmpty()));
			result.add(row);
		}
		return result;
	}

	@Override
	public void close() {
		try {
			_simulation.close();
		} finally {
			_installation.close();
		}
	}

}
