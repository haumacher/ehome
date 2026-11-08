/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Application datapoints (haumacher/ehome #59): datapoints of the runtime image owned by no
 * technology, in a project built without an ETS import (one group address with the main type 1 of a
 * datapoint type created for the test).
 *
 * <p>
 * The image carries them after the datapoint of the group address, with their labels in both
 * languages, flags, history and an empty <code>virtual</code> member, and the controller's checks
 * accept it with a schedule entry whose output is one and a rule reading and writing them. The output
 * of a schedule entry is an application datapoint (haumacher/ehome #90, a group address cannot be
 * chosen); a writable output is refused at the field. An id that is the one of
 * the group address's datapoint, of the form of a group address, of another application datapoint
 * or reserved for the controller is refused at the field and on commit.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestApplicationDatapoints extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	public void testApplicationDatapoints() throws Exception {
		TLObject project;
		TLObject dpt;
		TLObject holiday;
		TLObject morning;
		TLObject counter;
		TLObject entry;
		TLObject write;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			dpt = create("ehome.datapoint:DatapointMainType");
			dpt.tUpdateByName("number", Integer.valueOf(1));
			dpt.tUpdateByName("extId", "TEST-APPLICATION-DATAPOINTS-1");
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Application datapoints");
			project.tUpdateByName("extId", "P-TEST-59");
			TLObject address = create("ehome.logical:GroupAddress");
			address.tUpdateByName("name", "Licht");
			address.tUpdateByName("address", new GA(0x0801, GroupAddressStyle.ThreeLevel));
			address.tUpdateByName("datapointTypes", List.of(dpt));
			address.tUpdateByName("runtimeWritable", Boolean.TRUE);
			project.tUpdateByName("groupAddresses", List.of(address));

			holiday = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			holiday.tUpdateByName("identifier", "holiday");
			holiday.tUpdateByName("label",
				ResKey.builder().add(Locale.GERMAN, "Urlaub").add(Locale.ENGLISH, "Holiday").build());
			holiday.tUpdateByName("writable", Boolean.TRUE);
			holiday.tUpdateByName("persist", Boolean.TRUE);
			morning = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			morning.tUpdateByName("identifier", "schedule.morning");
			counter = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			counter.tUpdateByName("type", valueType("unsigned"));
			counter.tUpdateByName("writable", Boolean.TRUE);
			counter.tUpdateByName("unit", "x");
			// A history set by hand (haumacher/ehome #73).
			counter.tUpdateByName("historyManual", Boolean.TRUE);
			counter.tUpdateByName("historyRetention", Integer.valueOf(3600));
			counter.tUpdateByName("historySpacing", Integer.valueOf(60));

			entry = RuleFunctions.create(project, "schedules", runtime("WeeklySchedule"));
			entry.tUpdateByName("output", morning);
			TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
			TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
			RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger")).tUpdateByName("datapoint", morning);
			reaction.tUpdateByName("condition", "@schedule.morning and not @holiday");
			write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			write.tUpdateByName("datapoint", counter);
			write.tUpdateByName("value", "1");
			TLObject light = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			light.tUpdateByName("datapoint", address);
			light.tUpdateByName("value", "true");
			tx.commit();
		}
		try {
			assertEquals("A new application datapoint gets an id of its own.", "value1", counter.tValueByName("identifier"));

			// The export: after the group address's datapoint, without a binding.
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			List<?> datapoints = (List<?>) image.getJson().get("datapoints");
			assertEquals(4, datapoints.size());
			assertEquals("1/0/1", ((Map<?, ?>) datapoints.get(0)).get("id"));
			assertEquals(map("id", "holiday", "label", map("de", "Urlaub", "en", "Holiday"), "type", "boolean",
				"writable", Boolean.TRUE, "persist", Boolean.TRUE, "virtual", Map.of()), datapoints.get(1));
			Map<?, ?> exportedMorning = (Map<?, ?>) datapoints.get(2);
			assertEquals(List.of("id", "label", "type", "virtual"), List.copyOf(exportedMorning.keySet()));
			assertEquals("schedule.morning", exportedMorning.get("id"));
			assertEquals(Map.of("en", "New datapoint", "de", "Neuer Datenpunkt"), exportedMorning.get("label"));
			assertEquals(map("id", "value1", "label", Map.of("en", "New datapoint", "de", "Neuer Datenpunkt"),
				"type", "unsigned", "unit", "x", "writable", Boolean.TRUE,
				"history", map("retention", Long.valueOf(3600), "spacing", Long.valueOf(60)), "virtual", Map.of()),
				datapoints.get(3));
			assertSame(holiday, image.elementAt("datapoints[1].id"));
			assertEquals("schedule.morning", ((Map<?, ?>) ((List<?>) image.getJson().get("schedules")).get(0)).get("output"));

			// The rules' table of datapoints.
			List<Object> table = RuleFunctions.datapoints(project);
			assertEquals(5, table.size());
			Resources resources = Resources.getInstance();
			// The datapoint of the rule's enable switch (spec/rules.md 8.6), after the image's own.
			assertEquals(map("id", "rule/rule-1/enabled", "label", resources.getString(I18NConstants.NEW_RULE_NAME),
				"type", "boolean", "writable", Boolean.TRUE, "binding", resources.getString(I18NConstants.BINDING_RULE_PARAMETER),
				"parameter", Map.of("rule", "rule-1", "parameter", "enabled")), table.get(4));
			assertEquals(resources.getString(I18NConstants.BINDING_KNX), ((Map<?, ?>) table.get(0)).get("binding"));
			assertEquals(resources.getString(I18NConstants.BINDING_APPLICATION), ((Map<?, ?>) table.get(1)).get("binding"));
			assertEquals("Holiday", ((Map<?, ?>) table.get(1)).get("label"));

			// The output of a schedule entry at the field.
			RuleForm entryForm = new RuleForm(entry);
			assertEquals(List.of(), entryForm.warnings("output"));
			// A group address is no option: the output is an application datapoint (haumacher/ehome #90).
			entryForm.set("output", holiday);
			assertEquals(List.of(resources.getString(I18NConstants.OUTPUT_WRITABLE__ID.fill(DatapointNames.name(holiday)))),
				entryForm.warnings("output"));
			entryForm.set("output", counter);
			assertTrue(entryForm.warnings("output").toString(), entryForm.warnings("output").get(0).contains("not boolean"));
			entryForm.set("output", morning);
			assertEquals(List.of(), entryForm.warnings("output"));

			// A rule writing to the schedule's output, which is not writable.
			RuleForm writeForm = new RuleForm(write);
			assertEquals(List.of(), writeForm.warnings("datapoint"));
			writeForm.set("datapoint", morning);
			assertEquals(1, writeForm.warnings("datapoint").size());
			assertTrue(writeForm.warnings("datapoint").get(0), writeForm.warnings("datapoint").get(0).contains("is not writable"));

			// The id at the field.
			RuleForm idForm = new RuleForm(counter);
			idForm.set("identifier", "1/0/1");
			assertEquals(List.of(resources.getString(I18NConstants.DATAPOINT_ID_OF_GROUP_ADDRESS__ADDRESS.fill("Licht (1/0/1)"))),
				idForm.errors("identifier"));
			idForm.set("identifier", "1/0/2");
			assertEquals(List.of(resources.getString(I18NConstants.DATAPOINT_ID_ADDRESS_FORM)), idForm.errors("identifier"));
			idForm.set("identifier", "holiday");
			assertEquals(List.of(resources.getString(I18NConstants.DATAPOINT_ID_DUPLICATE__ID.fill("holiday"))),
				idForm.errors("identifier"));
			idForm.set("identifier", "knx.time");
			assertEquals(List.of(resources.getString(I18NConstants.DATAPOINT_ID_RESERVED__ID.fill("knx.time"))),
				idForm.errors("identifier"));
			idForm.set("identifier", "a\tb");
			assertEquals(List.of(resources.getString(I18NConstants.INVALID_DATAPOINT_ID)), idForm.errors("identifier"));
			idForm.set("identifier", "Zähler 1");
			assertEquals(List.of(), idForm.errors("identifier"));
			idForm.set("unit", "0123456789abcdef");
			assertEquals(List.of(resources.getString(I18NConstants.INVALID_UNIT)), idForm.errors("unit"));
			idForm.set("type", valueType("string"));
			idForm.set("historySpacing", Integer.valueOf(60));
			assertEquals(List.of(resources.getString(I18NConstants.HISTORY_WRONG_TYPE__TYPE.fill("string"))),
				idForm.errors("historySpacing"));

			// On commit.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				counter.tUpdateByName("identifier", "1/0/1");
				tx.commit();
				fail("The id of the group address's datapoint is refused.");
			} catch (TopLogicException ex) {
				// Expected.
			}
		} finally {
			SharedData.deleteProjects(List.of(project));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				dpt.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * Roles of functions bound to application datapoints (haumacher/ehome #61): the image binds them
	 * to the datapoint's id, the validator accepts a fitting one and refuses one of a value type the
	 * role does not take or a read-only one where the role commands; the options of a role offer the
	 * group addresses and the application datapoints.
	 */
	public void testFunctionRoles() throws Exception {
		TLObject project;
		TLObject address;
		TLObject virtualSwitch;
		TLObject level;
		TLObject light;
		TLObject dimmer;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Function roles");
			project.tUpdateByName("extId", "P-TEST-61");
			address = create("ehome.logical:GroupAddress");
			address.tUpdateByName("name", "Licht");
			address.tUpdateByName("address", new GA(0x0801, GroupAddressStyle.ThreeLevel));
			project.tUpdateByName("groupAddresses", List.of(address));

			virtualSwitch = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			virtualSwitch.tUpdateByName("identifier", "virtual.switch");
			virtualSwitch.tUpdateByName("writable", Boolean.TRUE);
			level = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			level.tUpdateByName("identifier", "virtual.level");
			level.tUpdateByName("type", valueType("unsigned"));
			level.tUpdateByName("unit", "%");

			light = create("ehome.runtime:Function");
			light.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Virtueller Schalter").build());
			light.tUpdateByName("identifier", "virtual-light");
			light.tUpdateByName("kind", FunctionProposal.classifier("switch"));
			light.tUpdateByName("command", virtualSwitch);
			light.tUpdateByName("state", virtualSwitch);
			dimmer = create("ehome.runtime:Function");
			dimmer.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Virtueller Dimmer").build());
			dimmer.tUpdateByName("identifier", "virtual-dimmer");
			dimmer.tUpdateByName("kind", FunctionProposal.classifier("dimmer"));
			dimmer.tUpdateByName("levelCommand", virtualSwitch);
			dimmer.tUpdateByName("level", level);
			project.tUpdateByName("functions", List.of(light, dimmer));
			tx.commit();
		}
		try {
			assertEquals(List.of(address, virtualSwitch, level), RuntimeFunctions.datapoints(project));

			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			List<?> functions = (List<?>) image.getJson().get("functions");
			Map<?, ?> exportedLight = (Map<?, ?>) functions.get(0);
			assertEquals(map("command", "virtual.switch", "state", "virtual.switch"), exportedLight.get("datapoints"));
			Map<?, ?> exportedDimmer = (Map<?, ?>) functions.get(1);
			assertEquals(map("levelCommand", "virtual.switch", "level", "virtual.level"),
				exportedDimmer.get("datapoints"));
			// The unit of the main datapoint; the main role of the dimmer is a truth value without unit.
			assertNull(exportedDimmer.get("unit"));

			// The level command of a dimmer takes a number, and it must be writable.
			ImageReport report = image.validate();
			assertFalse(report.isValid());
			assertEquals(1, report.getRefusals().size());
			ImageReport.Refusal refusal = report.getRefusals().get(0);
			assertEquals("functions[1].datapoints.levelCommand", refusal.path());
			assertTrue(refusal.reason(), refusal.reason().contains("'virtual.switch' is boolean"));
			assertSame(dimmer, image.elementAt(refusal.path()));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				dimmer.tUpdateByName("levelCommand", level);
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			report = image.validate();
			assertFalse(report.isValid());
			assertEquals(1, report.getRefusals().size());
			refusal = report.getRefusals().get(0);
			assertEquals("functions[1].datapoints.levelCommand", refusal.path());
			assertTrue(refusal.reason(), refusal.reason().contains("needs a writable datapoint"));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				level.tUpdateByName("writable", Boolean.TRUE);
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			report = image.validate();
			assertTrue(report.toString(), report.isValid());
			exportedDimmer = (Map<?, ?>) ((List<?>) image.getJson().get("functions")).get(1);
			assertEquals(map("levelCommand", "virtual.level", "level", "virtual.level"), exportedDimmer.get("datapoints"));
			assertEquals("%", exportedDimmer.get("unit"));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * Probe (#61): a role follows its application datapoint: a changed id shows in the image, a
	 * datapoint of another project is refused, a deleted datapoint leaves the role unbound - the
	 * function's main role, so the function is unused (haumacher/ehome #98): the image leaves it out
	 * without refusing and counts it.
	 */
	public void testRolesFollowTheDatapoint() throws Exception {
		TLObject project;
		TLObject other;
		TLObject virtualSwitch;
		TLObject foreign;
		TLObject light;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Roles follow");
			project.tUpdateByName("extId", "P-PROBE-61A");
			other = create("ehome.project:Project");
			other.tUpdateByName("name", "Other");
			other.tUpdateByName("extId", "P-PROBE-61B");
			virtualSwitch = RuleFunctions.create(project, "applicationDatapoints", runtime("ApplicationDatapoint"));
			virtualSwitch.tUpdateByName("identifier", "virtual.switch");
			virtualSwitch.tUpdateByName("writable", Boolean.TRUE);
			foreign = RuleFunctions.create(other, "applicationDatapoints", runtime("ApplicationDatapoint"));
			foreign.tUpdateByName("identifier", "foreign.switch");
			foreign.tUpdateByName("writable", Boolean.TRUE);
			light = create("ehome.runtime:Function");
			light.tUpdateByName("name", ResKey.builder().add(Locale.GERMAN, "Licht").build());
			light.tUpdateByName("identifier", "light");
			light.tUpdateByName("kind", FunctionProposal.classifier("switch"));
			light.tUpdateByName("command", virtualSwitch);
			project.tUpdateByName("functions", List.of(light));
			tx.commit();
		}
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				virtualSwitch.tUpdateByName("identifier", "virtual.renamed");
				tx.commit();
			}
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			Map<?, ?> exported = (Map<?, ?>) ((List<?>) image.getJson().get("functions")).get(0);
			assertEquals(map("command", "virtual.renamed"), exported.get("datapoints"));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				light.tUpdateByName("command", foreign);
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			assertFalse("Datapoint of another project accepted: " + Json.write(image.getJson().get("functions")),
				image.validate().isValid());

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				light.tUpdateByName("command", virtualSwitch);
				tx.commit();
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				virtualSwitch.tDelete();
				tx.commit();
			}
			assertNull(light.tValueByName("command"));
			assertTrue(FunctionProposal.unused(light));
			image = RuntimeImage.build(project, 1, NOW);
			assertNull(Json.write(image.getJson()), image.getJson().get("functions"));
			assertEquals(1, image.getUnusedFunctions());
			assertTrue(image.validate().toString(), image.validate().isValid());
		} finally {
			SharedData.deleteProjects(List.of(project, other));
		}
	}

	private static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int n = 0; n < keyValues.length; n += 2) {
			result.put((String) keyValues[n], keyValues[n + 1]);
		}
		return result;
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	private static Object valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestApplicationDatapoints.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
