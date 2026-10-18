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
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Application datapoints (haumacher/ehome #59): datapoints of the runtime image owned by no
 * technology, in a project built without an ETS import (one group address with the main type 1 of a
 * datapoint type created for the test).
 *
 * <p>
 * The image carries them after the datapoint of the group address, with their labels in both
 * languages, flags, history and an empty <code>virtual</code> member, and the controller's checks
 * accept it with a schedule entry whose output is one and a rule reading and writing them. A
 * schedule output on the group address's datapoint stays refused (<code>outputConflict</code>), at
 * the field and in the image; a writable output is refused at the field. An id that is the one of
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
			counter.tUpdateByName("historyRetention", Integer.valueOf(3600));
			counter.tUpdateByName("historySpacing", Integer.valueOf(60));

			entry = RuleFunctions.create(project, "schedules", runtime("WeeklySchedule"));
			entry.tUpdateByName("output", "schedule.morning");
			TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
			TLObject reaction = RuleFunctions.create(rule, "reactions", runtime("Reaction"));
			RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger")).tUpdateByName("datapoint",
				"schedule.morning");
			reaction.tUpdateByName("condition", "@schedule.morning and not @holiday");
			write = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			write.tUpdateByName("datapoint", "value1");
			write.tUpdateByName("value", "1");
			TLObject light = RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			light.tUpdateByName("datapoint", "1/0/1");
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
			assertEquals(4, table.size());
			Resources resources = Resources.getInstance();
			assertEquals(resources.getString(I18NConstants.BINDING_KNX), ((Map<?, ?>) table.get(0)).get("binding"));
			assertEquals(resources.getString(I18NConstants.BINDING_APPLICATION), ((Map<?, ?>) table.get(1)).get("binding"));
			assertEquals("Holiday", ((Map<?, ?>) table.get(1)).get("label"));

			// The output of a schedule entry at the field.
			RuleForm entryForm = new RuleForm(entry);
			assertEquals(List.of(), entryForm.warnings("output"));
			entryForm.set("output", "1/0/1");
			assertEquals(1, entryForm.warnings("output").size());
			assertTrue(entryForm.warnings("output").get(0),
				entryForm.warnings("output").get(0).contains("outputConflict: output '1/0/1' is owned by the KNX adapter"));
			entryForm.set("output", "holiday");
			assertEquals(List.of(resources.getString(I18NConstants.OUTPUT_WRITABLE__ID.fill("holiday"))),
				entryForm.warnings("output"));
			entryForm.set("output", "value1");
			assertTrue(entryForm.warnings("output").toString(), entryForm.warnings("output").get(0).contains("typeError"));
			entryForm.set("output", "schedule.morning");
			assertEquals(List.of(), entryForm.warnings("output"));

			// A rule writing to the schedule's output, which is not writable.
			RuleForm writeForm = new RuleForm(write);
			assertEquals(List.of(), writeForm.warnings("datapoint"));
			writeForm.set("datapoint", "schedule.morning");
			assertEquals(1, writeForm.warnings("datapoint").size());
			assertTrue(writeForm.warnings("datapoint").get(0), writeForm.warnings("datapoint").get(0).contains("notWritable"));

			// The id at the field.
			RuleForm idForm = new RuleForm(counter);
			idForm.set("identifier", "1/0/1");
			assertEquals(List.of(resources.getString(I18NConstants.DATAPOINT_ID_OF_GROUP_ADDRESS__ADDRESS.fill("1/0/1 Licht"))),
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

			// A stored output on the group address's datapoint: the image is refused at the entry.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				entry.tUpdateByName("output", "1/0/1");
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			report = image.validate();
			assertFalse(report.isValid());
			ImageReport.Refusal refusal = report.getRefusals().get(0);
			assertEquals("schedules[0].output", refusal.path());
			assertTrue(refusal.reason(), refusal.reason().startsWith("outputConflict"));
			assertSame(entry, image.elementAt(refusal.path()));
		} finally {
			SharedData.deleteProjects(List.of(project));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				dpt.tDelete();
				tx.commit();
			}
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
