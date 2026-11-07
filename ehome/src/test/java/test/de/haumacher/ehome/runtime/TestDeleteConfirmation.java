/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Referers;
import de.haumacher.ehome.runtime.RuleFunctions;

/**
 * The question before a delete names what refers to the object (haumacher/ehome #94): deleting the
 * application datapoints of a motion sensor left the sensors of the tab "Functions" without their
 * datapoints, and only the next deployment refused them ("a sensor needs the role value").
 *
 * <p>
 * The referers are found through the model: the function by its role, the rule by a trigger and an
 * action (reported once, as the rule), the schedule entry by its output; a schedule entry is named by
 * the schedule trigger of a rule; a group range by a function naming an address inside it. A referer
 * deleted together with the object (a rule naming its own parts) is not reported.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDeleteConfirmation extends BasicTestCase {

	public void testReferers() throws Exception {
		TLObject project;
		TLObject range;
		TLObject address;
		TLObject temperature;
		TLObject presence;
		TLObject unused;
		TLObject sensor;
		TLObject light;
		TLObject rule;
		TLObject entry;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Delete confirmation");
			project.tUpdateByName("extId", "P-TEST-94");
			address = create("ehome.logical:GroupAddress");
			address.tUpdateByName("name", "Licht Flur");
			address.tUpdateByName("address", new GA(0x0801, GroupAddressStyle.ThreeLevel));
			range = create("ehome.logical:GroupRange");
			range.tUpdateByName("name", "Licht");
			range.tUpdateByName("parts", List.of(address));
			project.tUpdateByName("groupAddresses", List.of(range));

			temperature = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			temperature.tUpdateByName("identifier", "flur.temperature");
			presence = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			presence.tUpdateByName("identifier", "flur.presence");
			unused = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			unused.tUpdateByName("identifier", "unused");

			sensor = create("ehome.runtime:Function");
			sensor.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Flur Temperatur")
				.add(Locale.ENGLISH, "Hall temperature").build());
			sensor.tUpdateByName("identifier", "flur-temperatur");
			sensor.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
			sensor.tUpdateByName("value", temperature);
			light = create("ehome.runtime:Function");
			light.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Licht Flur")
				.add(Locale.ENGLISH, "Hall light").build());
			light.tUpdateByName("identifier", "licht-flur");
			light.tUpdateByName("kind", FunctionProposal.classifier("switch"));
			light.tUpdateByName("command", address);
			light.tUpdateByName("state", address);
			project.tUpdateByName("functions", List.of(sensor, light));

			entry = RuleFunctions.create(project, "schedules", runtime("WeeklySchedule"));
			entry.tUpdateByName("output", presence);
			rule = RuleFunctions.create(project, "rules", runtime("Rule"));
			rule.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Frostschutz").add(Locale.ENGLISH, "Frostschutz").build());
			TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
			RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger")).tUpdateByName("datapoint", temperature);
			RuleFunctions.create(reaction, "triggers", runtime("ScheduleTrigger")).tUpdateByName("schedule", entry);
			TLObject write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			write.tUpdateByName("datapoint", temperature);
			write.tUpdateByName("value", "1");
			tx.commit();
		}
		try {
			// The datapoint of the sensor: the function by its role, the rule once for its trigger and action.
			Map<TLObject, Set<String>> referers = Referers.referers(temperature);
			assertEquals(Set.of(sensor, rule), referers.keySet());
			assertEquals(1, referers.get(sensor).size());
			assertEquals(1, referers.get(rule).size());

			String question = Referers.deleteConfirmation(temperature);
			assertTrue(question, question.contains("flur.temperature"));
			assertTrue(question, question.contains("Frostschutz"));
			assertTrue(question, question.contains("Flur Temperatur") || question.contains("Hall temperature"));
			assertTrue(question, question.contains(" 2 "));
			String once = Referers.deleteConfirmation(presence);
			assertFalse(once, once.contains(" 1 "));
			assertEquals(2, Referers.lines(temperature).size());

			// The output of a schedule entry.
			assertEquals(Set.of(entry), Referers.referers(presence).keySet());
			// A schedule entry named by the schedule trigger of a rule.
			assertEquals(Set.of(rule), Referers.referers(entry).keySet());

			// A group address, and the range it is deleted with.
			assertEquals(Set.of(light), Referers.referers(address).keySet());
			assertEquals(Set.of(light), Referers.referers(range).keySet());

			// Nothing refers to it; a rule naming its own parts is not its referer.
			assertEquals(Map.of(), Referers.referers(unused));
			assertEquals(List.of(), Referers.lines(unused));
			assertEquals(Map.of(), Referers.referers(rule));
			assertFalse(Referers.deleteConfirmation(unused).contains("•"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeleteConfirmation.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
