/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.FunctionRoleCheck;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * A heating actuator with its own controller and the room keys sending the temperatures it receives
 * (<code>src/test/fixtures/TestFunctionHeating.knxproj</code>, see <code>make-fixtures.py</code>; the
 * construct of the MDT AKH-0600.03 in the ETS6 demo, haumacher/ehome #138).
 *
 * <p>
 * The actuator's channel receives the room temperature (an object with the flags W and T), reports
 * whether it heats ("Stellwert &gt; 0%", 1.011) and its actuating value (5.001); its setpoint and
 * operating mode are connected to nothing. Linked with the key sending the temperature (haumacher/ehome
 * #98), it is one climate function: the temperature the key's address, heating the 1.011 report, the
 * heating level the actuating value, in the key's room (#133), and the key gives no sensor of its own.
 * A channel whose reports are connected to nothing but which receives a temperature is a climate
 * with the temperature alone (the reports are its objects in effect). The temperature no heating
 * receives stays a sensor; the channel connected to nothing is an unused climate.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFunctionHeating extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionHeating.knxproj";

	private static final List<String> EXPECTED = List.of(
		"TEST Heizungsaktor Stellwert: Kanal C | climate |  | - | unused",
		"Temperatur Flur | sensor | value=7/3/1 | Flur | used",
		// The channel's reports connected to nothing: still the heating's climate, not the key's sensor.
		"Temperatur Gast | climate | temperature=7/4/1 | Wohnzimmer | used",
		"Temperatur Küche | climate | temperature=7/2/1, heating=7/2/2, heatingLevel=7/2/3 | Küche | used",
		"Temperatur Wohnzimmer | climate | temperature=7/1/1, heating=7/1/2, heatingLevel=7/1/3 | Wohnzimmer | used");

	public void testHeatingActuatorWithRoomKeys() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				FunctionProposal.run(project);
				tx.commit();
			}
			assertEquals(EXPECTED, TestFunctionLinks.table(project));
			for (TLObject function : FunctionProposal.functions(project)) {
				assertEquals(TestFunctionProposal.name(function), List.of(), FunctionRoleCheck.problems(function));
			}
			RuntimeImage image = RuntimeImage.build(project, 1, java.time.Instant.parse("2026-11-12T12:00:00Z"));
			assertTrue(image.validate().toString(), image.validate().isValid());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionHeating.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
