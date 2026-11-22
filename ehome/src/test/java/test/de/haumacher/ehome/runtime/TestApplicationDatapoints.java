/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.Deployer;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuleImport;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.runtime.binary.ImageDecoder;

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
		Project project;
		TLObject dpt;
		ApplicationDatapoint holiday;
		ApplicationDatapoint morning;
		ApplicationDatapoint counter;
		WeeklySchedule entry;
		WriteAction write;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			dpt = create("ehome.datapoint:DatapointMainType");
			dpt.tUpdateByName("number", Integer.valueOf(1));
			dpt.tUpdateByName("extId", "TEST-APPLICATION-DATAPOINTS-1");
			project = create("ehome.project:Project");
			project.setName("Application datapoints");
			project.setExtId("P-TEST-59");
			GroupAddress address = create("ehome.logical:GroupAddress");
			address.setName("Licht");
			address.setAddress(new GA(0x0801, GroupAddressStyle.ThreeLevel));
			address.setDatapointTypes(List.of(dpt));
			address.setRuntimeWritable(Boolean.TRUE);
			project.setGroupAddresses(Set.of(address));

			holiday = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			holiday.setIdentifier("holiday");
			holiday.setLabel(ResKey.builder().add(Locale.GERMAN, "Urlaub").add(Locale.ENGLISH, "Holiday").build());
			holiday.setWritable(true);
			holiday.setPersist(true);
			morning = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			morning.setIdentifier("schedule.morning");
			// A label is entered, never preset (haumacher/ehome #205).
			morning.setLabel(ResKey.builder().add(Locale.GERMAN, "Morgen").add(Locale.ENGLISH, "Morning").build());
			counter = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			counter.setLabel(ResKey.builder().add(Locale.GERMAN, "Zähler").add(Locale.ENGLISH, "Counter").build());
			counter.setType(valueType("unsigned"));
			counter.setWritable(true);
			counter.setUnit("x");
			// A history set by hand (haumacher/ehome #73).
			counter.setHistoryManual(true);
			counter.setHistoryRetention(Integer.valueOf(3600));
			counter.setHistorySpacing(Integer.valueOf(60));

			entry = (WeeklySchedule) RuleFunctions.create(project, "schedules", runtime("WeeklySchedule"));
			entry.setOutput(morning);
			entry.setLabel(ResKey.builder().add(Locale.GERMAN, "Morgens").add(Locale.ENGLISH, "Mornings").build());
			TLObject rule = RuleFunctions.create(project, "rules", runtime("Rule"));
			((Rule) rule).setNameKey(ResKey.builder().add(Locale.GERMAN, "Zählen").add(Locale.ENGLISH, "Count").build());
			Reaction reaction = (Reaction) RuleFunctions.create(rule, "reactions", runtime("Reaction"));
			((DatapointTrigger) RuleFunctions.create(reaction, "triggers", runtime("DatapointTrigger")))
				.setDatapoint(morning);
			reaction.setCondition("@schedule.morning and not @holiday");
			write = (WriteAction) RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			write.setDatapoint(counter);
			write.setValue("1");
			WriteAction light = (WriteAction) RuleFunctions.create(reaction, "actions", runtime("WriteAction"));
			light.setDatapoint(address);
			light.setValue("true");
			tx.commit();
		}
		try {
			assertEquals("A new application datapoint gets an id of its own.", "value1", counter.getIdentifier());

			// The export: after the group address's datapoint, without a binding.
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			List<?> datapoints = (List<?>) image.getJson().get("datapoints");
			assertEquals(4, datapoints.size());
			assertEquals("1/0/1", ((Map<?, ?>) datapoints.get(0)).get("id"));
			assertEquals(map("id", "holiday", "label", map("de", "Urlaub", "en", "Holiday"), "type", "boolean",
				"writable", Boolean.TRUE, "persist", Boolean.TRUE, "virtual", Map.of(), "initial", Boolean.FALSE),
				datapoints.get(1));
			Map<?, ?> exportedMorning = (Map<?, ?>) datapoints.get(2);
			assertEquals(List.of("id", "label", "type", "virtual", "initial"), List.copyOf(exportedMorning.keySet()));
			assertEquals("schedule.morning", exportedMorning.get("id"));
			assertEquals(Map.of("en", "Morning", "de", "Morgen"), exportedMorning.get("label"));
			assertEquals(map("id", "value1", "label", Map.of("en", "Counter", "de", "Zähler"),
				"type", "unsigned", "unit", "x", "writable", Boolean.TRUE,
				"history", map("retention", Long.valueOf(3600), "spacing", Long.valueOf(60)), "virtual", Map.of(),
				"initial", Long.valueOf(0)), datapoints.get(3));
			assertSame(holiday, image.elementAt("datapoints[1].id"));
			assertEquals("schedule.morning", ((Map<?, ?>) ((List<?>) image.getJson().get("schedules")).get(0)).get("output"));

			// The rules' table of datapoints.
			List<Object> table = RuleFunctions.datapoints(project);
			assertEquals(5, table.size());
			Resources resources = Resources.getInstance();
			// The datapoint of the rule's enable switch (spec/rules.md 8.6), after the image's own.
			assertEquals(map("id", "rule/rule-1/enabled", "label", "Count",
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

			// A rule writing to an application datapoint that is not writable: rules write every
			// application datapoint, writable is the user's (haumacher/ehome #138, homecontrol spec/rules.md
			// 7.3; the entry here has no time, so the image has no output of a schedule entry, which a rule
			// may not write, corpus case 39).
			RuleForm writeForm = new RuleForm(write);
			assertEquals(List.of(), writeForm.warnings("datapoint"));
			writeForm.set("datapoint", morning);
			assertEquals(List.of(), writeForm.warnings("datapoint"));

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
				counter.setIdentifier("1/0/1");
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
	 * The initial value of an application datapoint (haumacher/ehome #217, homecontrol
	 * <code>spec/runtime-image.md</code> 3.3, schema 1.9): entered as a literal of the rule language of
	 * its type and checked at the field; while none is entered the type's neutral value applies. The
	 * image carries it for every application datapoint (schema 1.9, binary format 1.2), through the
	 * binary image and back; a change of the type that the entered value does not fit is refused; the
	 * import of an image's datapoints takes it back where it is not the neutral value.
	 */
	public void testInitialValues() throws Exception {
		Project project;
		ApplicationDatapoint flag;
		ApplicationDatapoint setpoint;
		ApplicationDatapoint wake;
		ApplicationDatapoint step;
		ApplicationDatapoint mode;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.setName("Initial values");
			project.setExtId("P-TEST-217");
			flag = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			flag.setIdentifier("sonnenschutz");
			flag.setWritable(true);
			flag.setPersist(true);
			setpoint = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			setpoint.setIdentifier("setpoint");
			setpoint.setType(valueType("real"));
			setpoint.setInitialValue("21.5");
			wake = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			wake.setIdentifier("wake");
			wake.setType(valueType("timeOfDay"));
			wake.setInitialValue("06:30");
			step = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			step.setIdentifier("step");
			step.setType(valueType("controlled"));
			step.setInitialValue("controlled(true, 3)");
			mode = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			mode.setIdentifier("mode");
			mode.setType(valueType("enumeration"));
			tx.commit();
		}
		Project other = null;
		try {
			// The field: the value entered, else the type's neutral value.
			assertEquals("false", flag.getInitial());
			assertEquals("0", mode.getInitial());
			assertEquals("21.5", setpoint.getInitial());

			// The image: every application datapoint has its initial value, schema 1.9, format 1.2.
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertTrue(report.toString(), report.isValid());
			assertEquals("1.9", ((Map<?, ?>) image.getJson().get("image")).get("schemaVersion"));
			List<?> datapoints = (List<?>) image.getJson().get("datapoints");
			assertEquals(Boolean.FALSE, ((Map<?, ?>) datapoints.get(0)).get("initial"));
			assertEquals(Double.valueOf(21.5), ((Map<?, ?>) datapoints.get(1)).get("initial"));
			assertEquals(map("control", Boolean.TRUE, "value", Long.valueOf(3)), ((Map<?, ?>) datapoints.get(3)).get("initial"));
			assertEquals(Long.valueOf(0), ((Map<?, ?>) datapoints.get(4)).get("initial"));
			byte[] binary = image.compile(null);
			assertEquals(2, ImageDecoder.header(binary).minor());
			List<?> decoded = (List<?>) ImageDecoder.decode(binary).get("datapoints");
			for (int n = 0; n < datapoints.size(); n++) {
				Map<?, ?> exported = (Map<?, ?>) datapoints.get(n);
				Map<?, ?> back = (Map<?, ?>) decoded.get(n);
				assertEquals(exported.get("id").toString(), Json.write(exported.get("initial")), Json.write(back.get("initial")));
			}
			// A controller reading format 1.1 cannot take it (homecontrol spec/runtime-image.md 11).
			assertNotNull(Deployer.formatRefusal("1.1", "C", 2));
			assertNull(Deployer.formatRefusal("1.2", "C", 2));

			// At the field: a literal of the datapoint's type.
			Resources resources = Resources.getInstance();
			RuleForm form = new RuleForm(setpoint);
			form.set("initial", "21");
			assertEquals(List.of(), form.errors("initial"));
			form.set("initial", "warm");
			assertEquals(1, form.errors("initial").size());
			form.set("initial", "06:30");
			assertEquals(List.of(resources.getString(I18NConstants.INITIAL_VALUE_INVALID__TYPE_REASON.fill("real",
				"syntax error at character 1: a real literal expected, not a timeOfDay literal"))), form.errors("initial"));
			RuleForm stepForm = new RuleForm(step);
			stepForm.set("initial", "controlled(true, 300)");
			assertEquals(1, stepForm.errors("initial").size());

			// The import of the image's datapoints takes the entered values back, not the neutral ones.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				other = create("ehome.project:Project");
				other.setName("Initial values, imported");
				other.setExtId("P-TEST-217-B");
				new RuleImport().importDatapoints(other, datapoints);
				tx.commit();
			}
			List<TLObject> imported = ApplicationDatapoints.of(other);
			assertEquals(5, imported.size());
			assertNull(((ApplicationDatapoint) imported.get(0)).getInitialValue());
			assertEquals("21.5", ((ApplicationDatapoint) imported.get(1)).getInitialValue());
			assertEquals("06:30", ((ApplicationDatapoint) imported.get(2)).getInitialValue());
			assertEquals("controlled(true, 3)", ((ApplicationDatapoint) imported.get(3)).getInitialValue());
			assertNull(((ApplicationDatapoint) imported.get(4)).getInitialValue());

			// An entered value the type no longer takes: the change of the type is refused on commit.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				setpoint.setType(valueType("unsigned"));
				tx.commit();
				fail("The initial value 21.5 is no unsigned value.");
			} catch (TopLogicException ex) {
				// Expected.
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				setpoint.setType(valueType("unsigned"));
				setpoint.setInitialValue("21");
				tx.commit();
			}
			assertEquals(Long.valueOf(21),
				((Map<?, ?>) ((List<?>) RuntimeImage.build(project, 2, NOW).getJson().get("datapoints")).get(1)).get("initial"));
		} finally {
			SharedData.deleteProjects(other == null ? List.of(project) : List.of(project, other));
		}
	}

	/**
	 * Roles of functions bound to application datapoints (haumacher/ehome #61): the image binds them
	 * to the datapoint's id, the validator accepts a fitting one and refuses one of a value type the
	 * role does not take or a read-only one where the role commands; the options of a role offer the
	 * group addresses and the application datapoints.
	 */
	public void testFunctionRoles() throws Exception {
		Project project;
		GroupAddress address;
		ApplicationDatapoint virtualSwitch;
		ApplicationDatapoint level;
		Function light;
		Function dimmer;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.setName("Function roles");
			project.setExtId("P-TEST-61");
			address = create("ehome.logical:GroupAddress");
			address.setName("Licht");
			address.setAddress(new GA(0x0801, GroupAddressStyle.ThreeLevel));
			project.setGroupAddresses(Set.of(address));

			virtualSwitch = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			virtualSwitch.setIdentifier("virtual.switch");
			virtualSwitch.setWritable(true);
			level = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			level.setIdentifier("virtual.level");
			level.setType(valueType("unsigned"));
			level.setUnit("%");

			light = create("ehome.runtime:Function");
			light.setNameKey(ResKey.builder().add(Locale.GERMAN, "Virtueller Schalter").build());
			light.setIdentifier("virtual-light");
			light.setKind(FunctionProposal.classifier("switch"));
			light.setCommand(virtualSwitch);
			light.setState(virtualSwitch);
			dimmer = create("ehome.runtime:Function");
			dimmer.setNameKey(ResKey.builder().add(Locale.GERMAN, "Virtueller Dimmer").build());
			dimmer.setIdentifier("virtual-dimmer");
			dimmer.setKind(FunctionProposal.classifier("dimmer"));
			dimmer.setLevelCommand(virtualSwitch);
			dimmer.setLevel(level);
			project.setFunctions(List.of(light, dimmer));
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
				dimmer.setLevelCommand(level);
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
				level.setWritable(true);
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
		Project project;
		Project other;
		ApplicationDatapoint virtualSwitch;
		ApplicationDatapoint foreign;
		Function light;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.setName("Roles follow");
			project.setExtId("P-PROBE-61A");
			other = create("ehome.project:Project");
			other.setName("Other");
			other.setExtId("P-PROBE-61B");
			virtualSwitch = (ApplicationDatapoint) RuleFunctions.create(project, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			virtualSwitch.setIdentifier("virtual.switch");
			virtualSwitch.setWritable(true);
			foreign = (ApplicationDatapoint) RuleFunctions.create(other, "applicationDatapoints",
				runtime("ApplicationDatapoint"));
			foreign.setIdentifier("foreign.switch");
			foreign.setWritable(true);
			light = create("ehome.runtime:Function");
			light.setNameKey(ResKey.builder().add(Locale.GERMAN, "Licht").build());
			light.setIdentifier("light");
			light.setKind(FunctionProposal.classifier("switch"));
			light.setCommand(virtualSwitch);
			project.setFunctions(List.of(light));
			tx.commit();
		}
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				virtualSwitch.setIdentifier("virtual.renamed");
				tx.commit();
			}
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			Map<?, ?> exported = (Map<?, ?>) ((List<?>) image.getJson().get("functions")).get(0);
			assertEquals(map("command", "virtual.renamed"), exported.get("datapoints"));

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				light.setCommand(foreign);
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			assertFalse("Datapoint of another project accepted: " + Json.write(image.getJson().get("functions")),
				image.validate().isValid());

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				light.setCommand(virtualSwitch);
				tx.commit();
			}
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				virtualSwitch.tDelete();
				tx.commit();
			}
			assertNull(light.getCommand());
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

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T create(String type) {
		return (T) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLClass runtime(String name) {
		return (TLClass) TLModelUtil.findType("ehome.runtime:" + name);
	}

	private static TLClassifier valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestApplicationDatapoints.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
