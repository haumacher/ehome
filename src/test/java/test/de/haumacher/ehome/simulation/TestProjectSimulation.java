/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.simulation.KnxBinding;
import de.haumacher.ehome.simulation.Overrides;
import de.haumacher.ehome.simulation.ProjectSimulation;
import de.haumacher.ehome.simulation.Simulation;
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
		try (ProjectSimulation running =
			ProjectSimulation.start(project, START, new File("target/virtual/project-simulation"))) {
			Simulation simulation = running.simulation();
			GroupDevice button = device(running, 0x1332);
			GroupDevice actuator = device(running, 0x1305);
			int key = sender(button, "0/0/1");
			int channel = receiver(actuator, "0/0/34");
			assertTrue(button.object(key).has(GroupObject.COMMUNICATION | GroupObject.TRANSMIT));
			assertTrue(actuator.object(channel).has(GroupObject.COMMUNICATION | GroupObject.WRITE));
			assertEquals(Val.ofDuration(60_000), simulation.parameter("key-light", "delay"));

			long received = simulation.received();
			running.send("0/0/1", "true");
			assertTrue(simulation.awaitReceived(received + 1, TIMEOUT));
			assertTrue(actuator.awaitValue(channel, new byte[] { 1 }, TIMEOUT));
			assertEquals(Long.valueOf(60_000), simulation.timerRemaining("key-light", "off"));
			assertEquals("restartTimer off 1min", simulation.status("key-light").lastAction());

			running.setParameter("key-light/delay", "30s");
			assertEquals("30s", parameter.tValueByName(Overrides.SIMULATION_VALUE));
			running.resetParameter("key-light/delay");
			assertNull(parameter.tValueByName(Overrides.SIMULATION_VALUE));

			running.step();
			assertEquals(60_000, simulation.now());
			assertTrue(actuator.awaitValue(channel, new byte[] { 0 }, TIMEOUT));

			Map<String, Object> snapshot = running.snapshot();
			Map<?, ?> row = (Map<?, ?>) ((List<?>) snapshot.get("rules")).get(0);
			assertEquals("key-light", row.get("id"));
			assertEquals("timer off (Mon 2026-10-19 08:01:00.000)", row.get("trigger"));
			assertEquals("write 0/0/34 = false (Mon 2026-10-19 08:01:00.000)", row.get("action"));
		}
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
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
