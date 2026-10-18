/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.simulation.KnxBinding;
import de.haumacher.ehome.simulation.Overrides;
import de.haumacher.ehome.simulation.ProjectSimulation;
import de.haumacher.ehome.simulation.Simulation;
import de.haumacher.ehome.simulation.Simulation.Kind;
import de.haumacher.ehome.simulation.Simulations;
import de.haumacher.ehome.virtual.device.GroupDevice;
import de.haumacher.ehome.virtual.device.GroupObject;

/**
 * The simulation of a project ({@link ProjectSimulation}) on the KNX test system
 * (<code>samples/KNX-Testsystem.knxproj</code>) with a rule of the project: key 1 of the Gira push
 * button 1.3.50 (0/0/1) switches channel A of the MDT actuator 1.3.5 (0/0/34) on, and off again after
 * the parameter <code>delay</code> (default 2 min).
 *
 * <ul>
 * <li>The virtual installation holds the devices of the project with their objects, flags and
 * addresses.</li>
 * <li>The override of <code>delay</code> stored at the parameter (1min) is the value the simulation
 * starts with; one set in the simulation (30s) is written to the project, a reset removes it.</li>
 * <li>Key 1 sends from the push button's own object; the actuator's object takes the value the rule
 * writes, on and, after a step of the clock to the timer, off.</li>
 * <li>The object followers of the project (haumacher/ehome #62): the state of channel A (object 7,
 * 0/0/37) follows its switching object (object 0, 0/0/34) and comes back to the simulation; the state
 * of channel B (object 19, 0/0/43) follows its switching object (object 12, 0/0/40) inverted; an
 * incomplete follower is left out with a problem in the timeline.</li>
 * <li>The simulation runs as the service's ({@link Simulations}): its listener of the project is told
 * about the start, the changes (a telegram arriving, the clock moving) and the stop.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestProjectSimulation extends BasicTestCase {

	/** Monday 2026-10-19 08:00 UTC. */
	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 8 * 3600;

	private static final long TIMEOUT = 5000;

	private static final String RULE = "{\"id\": \"key-light\", \"name\": \"Light by key\","
		+ " \"parameters\": [{\"id\": \"delay\", \"label\": \"Delay\", \"type\": \"duration\", \"default\": 120000}],"
		+ " \"timers\": [{\"id\": \"off\", \"duration\": {\"kind\": \"parameter\", \"id\": \"delay\"}}],"
		+ " \"reactions\": ["
		+ "  {\"triggers\": [{\"kind\": \"datapoint\", \"datapoint\": \"0/0/1\"}],"
		+ "   \"condition\": {\"kind\": \"datapoint\", \"name\": \"0/0/1\"},"
		+ "   \"actions\": [{\"kind\": \"write\", \"datapoint\": \"0/0/34\", \"value\": {\"kind\": \"literal\", \"type\": \"boolean\", \"value\": true}},"
		+ "    {\"kind\": \"restartTimer\", \"timer\": \"off\"}]},"
		+ "  {\"triggers\": [{\"kind\": \"timer\", \"timer\": \"off\"}],"
		+ "   \"actions\": [{\"kind\": \"write\", \"datapoint\": \"0/0/34\", \"value\": {\"kind\": \"literal\", \"type\": \"boolean\", \"value\": false}}]}"
		+ " ]}";

	public void testKeySwitchesChannel() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			TLObject parameter;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject rule = new RuleImport().importRules(project, List.of(Json.parse(RULE, 100))).get(0);
				parameter = (TLObject) ((List<?>) rule.tValueByName("parameters")).get(0);
				parameter.tUpdateByName(Overrides.SIMULATION_VALUE, "1min");
				TLObject actuator = projectDevice(project, 0x1305);
				addFollower(project, actuator, 0, 7, false);
				addFollower(project, actuator, 12, 19, true);
				addFollower(project, null, -1, -1, false);
				tx.commit();
			}
			run(project, parameter);
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void run(TLObject project, TLObject parameter) throws Exception {
		AtomicInteger changes = new AtomicInteger();
		Runnable listener = changes::incrementAndGet;
		Simulations simulations = Simulations.getInstance();
		simulations.addListener(project, listener);
		try {
			ProjectSimulation running = simulations.start(project, START);
			try {
				assertTrue(changes.get() > 0);
				run(running, parameter, changes);
			} finally {
				int before = changes.get();
				simulations.stop(project);
				assertTrue(changes.get() > before);
				assertNull(simulations.get(project));
			}
		} finally {
			simulations.removeListener(project, listener);
		}
	}

	private void run(ProjectSimulation running, TLObject parameter, AtomicInteger changes) throws Exception {
		Simulation simulation = running.simulation();
		GroupDevice button = device(running, 0x1332);
		GroupDevice actuator = device(running, 0x1305);
		int key = sender(button, "0/0/1");
		int channel = receiver(actuator, "0/0/34");
		assertTrue(button.object(key).has(GroupObject.COMMUNICATION | GroupObject.TRANSMIT));
		assertTrue(actuator.object(channel).has(GroupObject.COMMUNICATION | GroupObject.WRITE));
		assertEquals(Val.ofDuration(60_000), simulation.parameter("key-light", "delay"));

		assertEquals(List.of(Integer.valueOf(7)), actuator.followers(0));
		assertEquals(List.of(Integer.valueOf(19)), actuator.followers(12));
		assertTrue(simulation.timeline().toString(), simulation.timeline().stream()
			.anyMatch(entry -> entry.kind() == Kind.PROBLEM && entry.text().startsWith("an object follower without a device")));

		long received = simulation.received();
		int before = changes.get();
		running.send("0/0/1", "true");
		assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
		assertTrue(changes.get() > before);
		assertTrue(actuator.awaitValue(channel, new byte[] { 1 }, TIMEOUT));
		// The state of channel A follows and comes back.
		assertTrue(actuator.awaitValue(7, new byte[] { 1 }, TIMEOUT));
		assertTrue(simulation.awaitReceived(received + 2, TIMEOUT));
		assertEquals(Val.ofBool(true), simulation.value("0/0/37"));

		// The state of channel B follows inverted.
		running.command("0/0/40", "true");
		assertTrue(actuator.awaitValue(19, new byte[] { 0 }, TIMEOUT));
		assertTrue(simulation.awaitReceived(received + 3, TIMEOUT));
		assertEquals(Val.ofBool(false), simulation.value("0/0/43"));
		assertEquals(Long.valueOf(60_000), simulation.timerRemaining("key-light", "off"));
		assertEquals("restartTimer off 1min", simulation.status("key-light").lastAction());

		running.setParameter("key-light/delay", "30s");
		assertEquals("30s", parameter.tValueByName(Overrides.SIMULATION_VALUE));
		running.resetParameter("key-light/delay");
		assertNull(parameter.tValueByName(Overrides.SIMULATION_VALUE));

		before = changes.get();
		running.step();
		assertTrue(changes.get() > before);
		assertEquals(60_000, simulation.now());
		assertTrue(actuator.awaitValue(channel, new byte[] { 0 }, TIMEOUT));
		assertTrue(actuator.awaitValue(7, new byte[] { 0 }, TIMEOUT));

		Map<String, Object> snapshot = running.snapshot();
		Map<?, ?> row = (Map<?, ?>) ((List<?>) snapshot.get("rules")).get(0);
		assertEquals("key-light", row.get("id"));
		assertEquals("timer off (Mon 2026-10-19 08:01:00.000)", row.get("trigger"));
		assertEquals("write 0/0/34 = false (Mon 2026-10-19 08:01:00.000)", row.get("action"));
	}

	private static TLObject projectDevice(TLObject project, int address) {
		for (TLObject device : DeviceChanges.devices(project)) {
			if (Integer.valueOf(address).equals(DownloadImage.individualAddress(device))) {
				return device;
			}
		}
		throw new AssertionError("No device " + Integer.toHexString(address));
	}

	private static void addFollower(TLObject project, TLObject device, int source, int follower, boolean inverted) {
		TLClass type = (TLClass) TLModelUtil.findType("ehome.runtime:ObjectFollower");
		TLObject result = ModelService.getInstance().getFactory().createObject(type);
		if (device != null) {
			result.tUpdateByName("device", device);
			result.tUpdateByName("source", object(device, source));
			result.tUpdateByName("follower", object(device, follower));
			result.tUpdateByName("inverted", Boolean.valueOf(inverted));
		}
		List<Object> followers = new ArrayList<>((List<?>) project.tValueByName("simulationFollowers"));
		followers.add(result);
		project.tUpdateByName("simulationFollowers", followers);
	}

	private static TLObject object(TLObject device, int number) {
		for (Object item : (Collection<?>) device.tValueByName("connectors")) {
			TLObject connector = (TLObject) item;
			if (connector.tValueByName("port") instanceof Interface placed && placed.getPort().getIndex() == number) {
				return connector;
			}
		}
		throw new AssertionError("No object " + number);
	}

	private static GroupDevice device(ProjectSimulation running, int address) {
		for (GroupDevice device : running.installation().devices()) {
			if (device.address() == address) {
				return device;
			}
		}
		throw new AssertionError("No device " + Integer.toHexString(address) + " in " + running.installation().devices());
	}

	private static int sender(GroupDevice device, String address) {
		for (GroupObject object : device.objects()) {
			if (object.send() == KnxBinding.groupAddress(address)) {
				return object.number();
			}
		}
		throw new AssertionError("No object of " + device + " sends to " + address + ": " + device.objects());
	}

	private static int receiver(GroupDevice device, String address) {
		for (GroupObject object : device.objects()) {
			if (object.listensTo(KnxBinding.groupAddress(address))) {
				return object.number();
			}
		}
		throw new AssertionError("No object of " + device + " receives " + address + ": " + device.objects());
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProjectSimulation.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			Simulations.Module.INSTANCE));
	}

}
