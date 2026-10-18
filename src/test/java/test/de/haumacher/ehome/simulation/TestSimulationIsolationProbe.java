/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.simulation;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.simulation.ProjectSimulation;
import de.haumacher.ehome.simulation.Simulation;

/**
 * Probe of the simulation (#58): two projects of the same installation simulated at once are two
 * separate buses (a telegram of one never reaches the other), and a project's simulation starts
 * again after it was closed.
 */
@SuppressWarnings("javadoc")
public class TestSimulationIsolationProbe extends BasicTestCase {

	private static final long START = Values.daysOf(2026, 10, 19) * 86400 + 8 * 3600;

	private static final long TIMEOUT = 5000;

	public void testTwoProjects() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(2, projects.size());
		try {
			File dir = new File("target/virtual/isolation-probe");
			try (ProjectSimulation first = ProjectSimulation.start(projects.get(0), START, new File(dir, "a"));
					ProjectSimulation second = ProjectSimulation.start(projects.get(1), START, new File(dir, "b"))) {
				Simulation one = first.simulation();
				Simulation two = second.simulation();
				long receivedOne = one.received();
				long receivedTwo = two.received();
				first.send("0/0/1", "true");
				assertTrue(one.awaitReceived(receivedOne + 1, TIMEOUT));
				assertFalse("Telegram of the first installation reached the second.",
					two.awaitReceived(receivedTwo + 1, 300));
			}
			try (ProjectSimulation again = ProjectSimulation.start(projects.get(0), START, new File(dir, "a"))) {
				long received = again.simulation().received();
				again.send("0/0/1", "false");
				assertTrue(again.simulation().awaitReceived(received + 1, TIMEOUT));
			}
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestSimulationIsolationProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
