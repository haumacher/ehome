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
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.GroupRange;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.ScheduleTrigger;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;
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
		Project project;
		GroupRange range;
		GroupAddress address;
		ApplicationDatapoint temperature;
		ApplicationDatapoint presence;
		ApplicationDatapoint unused;
		Function sensor;
		Function light;
		Rule rule;
		WeeklySchedule entry;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.setName("Delete confirmation");
			project.setExtId("P-TEST-94");
			address = create("ehome.logical:GroupAddress");
			address.setName("Licht Flur");
			address.setAddress(new GA(0x0801, GroupAddressStyle.ThreeLevel));
			range = create("ehome.logical:GroupRange");
			range.setName("Licht");
			range.setParts(Set.of(address));
			project.setGroupAddresses(Set.of(range));

			temperature = add(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			temperature.setIdentifier("flur.temperature");
			presence = add(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			presence.setIdentifier("flur.presence");
			unused = add(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			unused.setIdentifier("unused");

			sensor = create("ehome.runtime:Function");
			sensor.setNameKey(ResKey.builder().add(Locale.GERMAN, "Flur Temperatur")
				.add(Locale.ENGLISH, "Hall temperature").build());
			sensor.setIdentifier("flur-temperatur");
			sensor.setKind(FunctionProposal.classifier("sensor"));
			sensor.setValue(temperature);
			light = create("ehome.runtime:Function");
			light.setNameKey(ResKey.builder().add(Locale.GERMAN, "Licht Flur")
				.add(Locale.ENGLISH, "Hall light").build());
			light.setIdentifier("licht-flur");
			light.setKind(FunctionProposal.classifier("switch"));
			light.setCommand(address);
			light.setState(address);
			project.setFunctions(List.of(sensor, light));

			entry = add(project, "schedules", runtime("WeeklySchedule"));
			((de.haumacher.ehome.model.runtime.ScheduleEntry) entry).setLabel(ResKey.text("entry"));
			entry.setOutput(presence);
			rule = add(project, "rules", runtime("Rule"));
			rule.setNameKey(ResKey.builder().add(Locale.GERMAN, "Frostschutz").add(Locale.ENGLISH, "Frostschutz").build());
			TLObject reaction = add(rule, "reactions", runtime("Reaction"));
			TestDeleteConfirmation.<DatapointTrigger> add(reaction, "triggers", runtime("DatapointTrigger"))
				.setDatapoint(temperature);
			TestDeleteConfirmation.<ScheduleTrigger> add(reaction, "triggers", runtime("ScheduleTrigger"))
				.setSchedule(entry);
			WriteAction write = add(reaction, "actions", runtime("WriteAction"));
			write.setDatapoint(temperature);
			write.setValue("1");
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

	/** A new part at the end of the reference of the owner ({@link RuleFunctions#create}), as its type. */
	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T add(TLObject owner, String reference, TLClass type) {
		return (T) RuleFunctions.create(owner, reference, type);
	}

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T create(String type) {
		return (T) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeleteConfirmation.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
