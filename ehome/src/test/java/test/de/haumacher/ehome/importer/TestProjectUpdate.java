/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.roundtrip.KnxprojDiff;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.knowledge.service.event.PendingChanges;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ImportEtsProjectJob;
import de.haumacher.ehome.etsImport.UpdateFromEtsJob;
import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ProjectParts;
import de.haumacher.ehome.model.building.BuildingDefinition;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.monitor.Recording;
import de.haumacher.ehome.model.monitor.Telegram;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.WriteAction;
import de.haumacher.ehome.model.zigbee.ZigbeeDevice;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.runtime.IdRenames;
import de.haumacher.ehome.runtime.Pages;
import de.haumacher.ehome.runtime.RuleFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The update of a project from a new export of the same project in ETS (haumacher/ehome #125,
 * {@code de.haumacher.ehome.importer.ProjectUpdate}), with the fixtures
 * <code>TestUpdateBase.knxproj</code>, <code>TestUpdateNew.knxproj</code> and
 * <code>TestUpdateAdopt.knxproj</code> (see <code>make-fixtures.py</code>).
 *
 * <p>
 * Each test imports the base once (the program of the fixtures is the one of the Test project, known
 * in a database of the suite) and updates it, sharing the import between its checks.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestProjectUpdate extends BasicTestCase {

	private static final String BASE = "src/test/fixtures/TestUpdateBase.knxproj";

	private static final String NEW = "src/test/fixtures/TestUpdateNew.knxproj";

	private static final String ADOPT = "src/test/fixtures/TestUpdateAdopt.knxproj";

	/** A file of another project (P-01A0). */
	private static final String OTHER = "src/test/fixtures/TestFunctionKeys.knxproj";

	private static final String P = "P-0125-0_";

	private static final String PROJECT_TYPE = "ehome.project:Project";

	/**
	 * The update keeps the identity of the objects the file still has and E-Home's data on them, adds
	 * and removes what ETS added and removed, and exports as a fresh import of the file does.
	 */
	public void testUpdate() throws Exception {
		List<TLObject> before = instances(PROJECT_TYPE);
		List<TLObject> projects = new ArrayList<>();
		try {
			TLObject project = importProject(BASE, before, projects);
			GroupAddress ga3 = (GroupAddress) knx(project, P + "GA-3");
			TLObject flur = knx(project, P + "DI-2");
			BuildingDefinition kueche = (BuildingDefinition) knx(project, P + "BP-3");
			BuildingDefinition erdgeschoss = (BuildingDefinition) knx(project, P + "BP-2");
			Rule rule;
			DatapointTrigger trigger;
			WriteAction write;
			Reaction reaction;
			Function function;
			ZigbeeDevice zigbee;
			Telegram telegram;
			Controller controller;
			try (Transaction tx = kb().beginTransaction()) {
				ga3.setRuntimeWritable(Boolean.TRUE);
				rule = (Rule) RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				rule.setIdentifier("licht");
				rule.setNameKey(text("Licht"));
				reaction = (Reaction) RuleFunctions.create(rule, "reactions", type("ehome.runtime:Reaction"));
				trigger = (DatapointTrigger) RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.setDatapoint(ga3);
				write = (WriteAction) RuleFunctions.create(reaction, "actions", type("ehome.runtime:WriteAction"));
				write.setDatapoint(ga3);
				write.setValue("true");
				reaction.setCondition("@\"0/0/3\"");

				function = (Function) create("ehome.runtime:Function");
				function.setNameKey(text("Licht Küche"));
				function.setKind(light());
				function.setCommand(ga3);
				function.setDevice(flur);
				function.setRoom(kueche);
				((Project) project).setFunctions(List.of(function));

				zigbee = (ZigbeeDevice) create("ehome.zigbee:ZigbeeDevice");
				zigbee.setIeee("001788010f069c8e");
				zigbee.setLocation(kueche);
				((Project) project).setZigbeeDevices(List.of(zigbee));

				Recording recording = (Recording) create("ehome.monitor:Recording");
				recording.setName("Aufzeichnung");
				telegram = (Telegram) create("ehome.monitor:Telegram");
				telegram.setGroupAddress(ga3);
				telegram.setAddress(ga3.getAddress());
				telegram.setSender(flur);
				recording.setTelegrams(Set.of(telegram));
				((Project) project).setRecordings(Set.of(recording));

				controller = (Controller) create("ehome.runtime:Controller");
				controller.setName("Flur");
				controller.setPort(80);
				((Project) project).setControllers(List.of(controller));
				((Project) project).setController(controller);
				tx.commit();
			}

			Map<?, ?> report = update(project, NEW);
			List<String> messages = messages(report);

			// Exactly one project of the ID: the one updated, the temporary project of the file is gone.
			assertEquals(List.of(project), SharedData.newProjects(before));
			assertSame(project, report.get(ImportEtsProjectJob.PROJECT));

			// The re-addressed and renamed group address keeps its identity and E-Home's data.
			assertTrue(ga3.tValid());
			assertEquals("0/0/4", Pages.imageId(ga3));
			assertEquals("Licht Küche", ga3.getName());
			assertEquals(Boolean.TRUE, ga3.getRuntimeWritable());
			assertSame(ga3, trigger.getDatapoint());
			assertSame(ga3, write.getDatapoint());
			assertSame(ga3, function.getCommand());
			assertSame(ga3, telegram.getGroupAddress());
			// The expression naming the address follows it (IdRenames).
			assertEquals("@\"0/0/4\"", reaction.getCondition());

			// The device moved to another line keeps its identity and its function.
			assertTrue(flur.tValid());
			assertSame(flur, function.getDevice());
			assertSame(flur, telegram.getSender());
			assertEquals(P + "L-2", ExtIds.extId(flur.tContainer()));
			assertSame(kueche, zigbee.getLocation());
			assertSame(controller, ((Project) project).getController());

			// Additions and removals.
			assertEquals("Neu", ((GroupAddress) knx(project, P + "GA-13")).getName());
			TLObject bad = knx(project, P + "DI-4");
			assertEquals(P + "L-2", ExtIds.extId(bad.tContainer()));
			assertEquals(P + "BP-6", ExtIds.extId((TLObject) bad.tValueByName("location")));
			for (String removed : List.of("GA-11", "GA-12", "DI-3")) {
				assertNull(removed, find(project, P + removed));
			}
			assertEquals(List.of("Wohnzimmer", "Küche", "Verteiler", "Bad"), names(erdgeschoss));

			// The changed parameter.
			TLObject kuecheDevice = knx(project, P + "DI-1");
			assertEquals(Long.valueOf(30), setting(kuecheDevice, "_P-2384_R-2384").tValueByName("number"));

			// No doubled shared objects.
			SharedData.assertOnce("ehome.catalog:Manufacturer", SharedData.ids(NEW, "knx_master.xml", "Manufacturer"));
			SharedData.assertOnce("ehome.catalog:Product", SharedData.ids(NEW, "M-[^/]+/Hardware\\.xml", "Hardware"));
			SharedData.assertOnce("ehome.operational:Program",
				SharedData.ids(NEW, "M-[^/]+/M-[^/]+_A-[^/]+\\.xml", "ApplicationProgram"));

			// The report names the changes per kind.
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("Neu (0/0/5)")));
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("Taster Reserve")));
			assertTrue(messages.toString(), messages.stream().noneMatch(m -> m.contains("no changes")));

			// The export of the updated project equals the export of a fresh import of the file.
			byte[] updated = export(project);
			TLObject fresh = importProject(NEW, instances(PROJECT_TYPE), projects);
			List<KnxprojDiff.Difference> differences = new KnxprojDiff(new KnxprojDiff.Rules())
				.compare(KnxprojDiff.unzip(export(fresh)), KnxprojDiff.unzip(updated));
			assertEquals(KnxprojDiff.summary(differences, 10), List.of(), differences);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * E-Home's identifier of a group address and the rules naming it by it stay through a
	 * re-addressing in ETS (haumacher/ehome #130); a name and description assigned in E-Home survive
	 * an update from a file that has none for the address, a name the file has wins.
	 */
	public void testIdentifiersAndNames() throws Exception {
		List<TLObject> before = instances(PROJECT_TYPE);
		List<TLObject> projects = new ArrayList<>();
		try {
			TLObject project = importProject(BASE, before, projects);
			GroupAddress ga1 = (GroupAddress) knx(project, P + "GA-1");
			GroupAddress ga3 = (GroupAddress) knx(project, P + "GA-3");
			Object etsName = ga1.getName();
			Object etsDescription = ga1.getDescription();
			assertFalse(String.valueOf(etsName).isBlank());

			// An export of ETS that has no name and no description for GA-1.
			try (Transaction tx = kb().beginTransaction()) {
				ga1.setName(null);
				ga1.setDescription(null);
				tx.commit();
			}
			Path unnamed = Path.of("target/update/TestUpdateUnnamed.knxproj");
			Files.createDirectories(unnamed.getParent());
			Files.write(unnamed, export(project));

			TLObject reaction;
			WriteAction write;
			try (Transaction tx = kb().beginTransaction()) {
				ga1.setName("Zentral Licht");
				ga1.setDescription("Aus E-Home");
				ga3.setName("Mein Name");
				ga3.setIdentifier("kueche.licht");
				ga3.setRuntimeWritable(Boolean.TRUE);
				Rule rule = (Rule) RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				rule.setNameKey(ResKey.text("licht"));
				rule.setIdentifier("licht");
				reaction = RuleFunctions.create(rule, "reactions", type("ehome.runtime:Reaction"));
				DatapointTrigger trigger = (DatapointTrigger) RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.setDatapoint(ga1);
				write = (WriteAction) RuleFunctions.create(reaction, "actions", type("ehome.runtime:WriteAction"));
				write.setDatapoint(ga3);
				write.setValue("not @\"0/0/3\"");
				tx.commit();
			}
			// Entered with the address, stored with the identifier.
			assertEquals("not @kueche.licht", write.getValue());

			// The file without the name of GA-1 keeps E-Home's name and description; GA-3's name in the
			// file wins over the one given in E-Home.
			update(project, unnamed.toString());
			assertEquals("Zentral Licht", ga1.getName());
			assertEquals("Aus E-Home", ga1.getDescription());
			assertEquals("GA3", ga3.getName());
			assertEquals("kueche.licht", ga3.getIdentifier());

			// Re-addressed in ETS: the identifier, the rule and the datapoint's id stay, the binding follows.
			update(project, NEW);
			assertTrue(ga3.tValid());
			assertEquals("Licht Küche", ga3.getName());
			assertEquals("kueche.licht", ga3.getIdentifier());
			assertEquals("kueche.licht", Pages.imageId(ga3));
			assertEquals("not @kueche.licht", write.getValue());
			assertSame(ga3, write.getDatapoint());
			assertEquals(etsName, ga1.getName());
			assertEquals(etsDescription == null ? "Aus E-Home" : etsDescription, ga1.getDescription());
			boolean found = false;
			for (Object datapoint : (List<?>) RuntimeImage.build(project, 1, Instant.now()).getJson().get("datapoints")) {
				if ("kueche.licht".equals(((Map<?, ?>) datapoint).get("id"))) {
					assertEquals("0/0/4", ((Map<?, ?>) ((Map<?, ?>) datapoint).get("knx")).get("send"));
					found = true;
				}
			}
			assertTrue(found);
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * Refusals change nothing; an unchanged file changes no object; the user's order of the rooms is
	 * kept.
	 */
	public void testRefusalsAndOrder() throws Exception {
		List<TLObject> before = instances(PROJECT_TYPE);
		List<TLObject> projects = new ArrayList<>();
		try {
			TLObject project = importProject(BASE, before, projects);
			GroupAddress jalousie = (GroupAddress) knx(project, P + "GA-12");
			Rule used;
			Rule named;
			try (Transaction tx = kb().beginTransaction()) {
				used = (Rule) RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				used.setIdentifier("jalousie");
				used.setNameKey(text("Jalousie fahren"));
				TLObject reaction = RuleFunctions.create(used, "reactions", type("ehome.runtime:Reaction"));
				DatapointTrigger trigger = (DatapointTrigger) RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.setDatapoint(jalousie);
				named = (Rule) RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				named.setIdentifier("unbenutzt");
				named.setNameKey(text("Unbenutzt prüfen"));
				Reaction other = (Reaction) RuleFunctions.create(named, "reactions", type("ehome.runtime:Reaction"));
				other.setCondition("@\"1/0/1\"");
				tx.commit();
			}

			// A rule on a group address the file removes, and an expression naming another: refused,
			// naming both, and the project is unchanged.
			String dump = dump(project);
			String message = refusal(project, NEW);
			assertTrue(message, message.contains("Jalousie (1/0/2)") && message.contains("Jalousie fahren"));
			assertTrue(message, message.contains("Unbenutzt (1/0/1)") && message.contains("Unbenutzt prüfen"));
			assertEquals(dump, dump(project));
			assertEquals(List.of(project), SharedData.newProjects(before));
			try (Transaction tx = kb().beginTransaction()) {
				used.tDelete();
				named.tDelete();
				tx.commit();
			}

			// A file of another project: refused with the reason.
			dump = dump(project);
			message = refusal(project, OTHER);
			assertTrue(message, message.contains("P-01A0") && message.contains("P-0125"));
			assertEquals(dump, dump(project));
			assertEquals(List.of(project), SharedData.newProjects(before));

			// The same file again: no changes, no object of the project touched.
			Set<TLObject> objects = contents(project);
			Set<ObjectKey> keys = new HashSet<>();
			for (TLObject obj : objects) {
				keys.add(obj.tId());
			}
			Set<String> touched = new LinkedHashSet<>();
			ModificationListener listener = new ModificationListener() {
				@Override
				public Modification createModification(KnowledgeBase kb, CommitActions actions, PendingChanges pending) {
					for (Map<ObjectKey, ? extends KnowledgeItem> changes : List.of(pending.getUpdated(), pending.getRemoved())) {
						for (ObjectKey key : changes.keySet()) {
							if (keys.contains(key)) {
								touched.add(key.toString());
							}
						}
					}
					return Modification.NONE;
				}

				@Override
				public Modification notifyUpcomingDeletion(KnowledgeBase kb, CommitActions actions, KnowledgeItem item) {
					return Modification.NONE;
				}
			};
			List<String> messages;
			kb().addModificationListener(listener);
			try {
				messages = messages(update(project, BASE));
			} finally {
				kb().removeModificationListener(listener);
			}
			assertEquals(Set.of(), touched);
			assertEquals(objects, contents(project));
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("no changes")));
			assertEquals(List.of(project), SharedData.newProjects(before));

			// The user's order of the rooms stays, the new room comes last.
			BuildingDefinition erdgeschoss = (BuildingDefinition) knx(project, P + "BP-2");
			try (Transaction tx = kb().beginTransaction()) {
				List<BuildingDefinition> parts = new ArrayList<>(erdgeschoss.getParts());
				parts.add(0, parts.remove(2));
				erdgeschoss.setParts(parts);
				tx.commit();
			}
			assertEquals(List.of("Verteiler", "Wohnzimmer", "Küche"), names(erdgeschoss));
			update(project, NEW);
			assertEquals(List.of("Verteiler", "Wohnzimmer", "Küche", "Bad"), names(erdgeschoss));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * Objects E-Home created (numbered in the app's range) are kept by an update whose file does not
	 * have them, and adopted by one that brings them at their place.
	 */
	public void testCreatedInApp() throws Exception {
		List<TLObject> before = instances(PROJECT_TYPE);
		List<TLObject> projects = new ArrayList<>();
		try {
			TLObject project = importProject(BASE, before, projects);
			TLObject kueche = knx(project, P + "DI-1");
			TLObject line = kueche.tContainer();
			TLObject range = knx(project, P + "GR-3");
			TLObject planned;
			GroupAddress address;
			DatapointTrigger trigger;
			Function function;
			try (Transaction tx = kb().beginTransaction()) {
				planned = DevicePlanning.create(line, (TLObject) kueche.tValueByName("type"),
					(TLObject) kueche.tValueByName("program"), Integer.valueOf(5), "Geplant", null);
				address = (GroupAddress) GroupAddresses.create(range, Integer.valueOf(5), "Eigene", null);
				Rule rule = (Rule) RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				rule.setIdentifier("eigene");
				rule.setNameKey(text("Eigene"));
				TLObject reaction = RuleFunctions.create(rule, "reactions", type("ehome.runtime:Reaction"));
				trigger = (DatapointTrigger) RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.setDatapoint(address);
				function = (Function) create("ehome.runtime:Function");
				function.setNameKey(text("Geplant"));
				function.setKind(light());
				function.setDevice(planned);
				((Project) project).setFunctions(List.of(function));
				tx.commit();
			}
			// Numbered in the app's range, above the numbers of ETS.
			assertEquals(P + "DI-" + ExtIds.APP_ID_BASE, ExtIds.extId(planned));
			assertEquals(P + "GA-" + ExtIds.APP_ID_BASE, ExtIds.extId(address));
			assertTrue(ExtIds.isCreatedInApp(planned));
			assertFalse(ExtIds.isCreatedInApp(kueche));

			// The file does not have the planned device: kept. It has an address at the place of the
			// one created in E-Home: adopted.
			List<String> messages = messages(update(project, NEW));
			assertTrue(planned.tValid());
			assertSame(line, planned.tContainer());
			assertEquals(P + "DI-" + ExtIds.APP_ID_BASE, ExtIds.extId(planned));
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("kept") && m.contains("Geplant")));
			assertTrue(address.tValid());
			assertEquals(P + "GA-13", ExtIds.extId(address));
			assertEquals("Neu", address.getName());
			assertFalse(ExtIds.isCreatedInApp(address));
			assertSame(address, trigger.getDatapoint());
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("adopted") && m.contains("Neu (0/0/5)")));
			assertEquals(1, SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
				.filter(a -> "0/0/5".equals(Pages.imageId(a))).count());

			// The file has a device of the same product at the place of the planned one: adopted.
			messages = messages(update(project, ADOPT));
			assertTrue(planned.tValid());
			assertEquals(P + "DI-5", ExtIds.extId(planned));
			assertEquals("Taster Diele", planned.tValueByName("title"));
			assertFalse(ExtIds.isCreatedInApp(planned));
			assertSame(planned, function.getDevice());
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("adopted") && m.contains("Taster Diele")));

			// After the import of the numbers of ETS, a device planned next continues the app's range.
			TLObject next;
			try (Transaction tx = kb().beginTransaction()) {
				next = DevicePlanning.create(line, (TLObject) kueche.tValueByName("type"),
					(TLObject) kueche.tValueByName("program"), Integer.valueOf(6), "Geplant 2", null);
				tx.commit();
			}
			assertEquals(P + "DI-" + (ExtIds.APP_ID_BASE + 1), ExtIds.extId(next));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/**
	 * Every stored attribute of every type of the KNX part is declared as owned by ETS or by
	 * E-Home ({@link ProjectParts}): a new attribute needs a decision.
	 */
	public void testOwnershipDeclared() {
		Set<TLClass> types = new LinkedHashSet<>();
		TLClass project = type(PROJECT_TYPE);
		for (String name : ProjectParts.PROJECT_REFERENCES) {
			addTypes(types, (TLClass) ((TLReference) project.getPart(name)).getType());
		}
		List<String> undeclared = new ArrayList<>();
		for (TLClass type : types) {
			for (TLStructuredTypePart part : type.getAllParts()) {
				if (ProjectParts.isStored(part) && !ProjectParts.isDeclared(part)) {
					undeclared.add(ProjectParts.qualifiedName(part));
				}
			}
		}
		assertEquals(List.of(), undeclared);
	}

	private static void addTypes(Set<TLClass> types, TLClass type) {
		for (TLClass concrete : TLModelUtil.getReflexiveTransitiveSpecializations(type)) {
			if (!types.add(concrete)) {
				continue;
			}
			for (TLReference reference : ProjectParts.knxCompositions(concrete)) {
				addTypes(types, (TLClass) reference.getType());
			}
		}
	}

	// ---- Helpers ------------------------------------------------------------------------------

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	private static TLObject importProject(String file, List<TLObject> before, List<TLObject> projects) throws Exception {
		importSample(file);
		List<TLObject> created = SharedData.newProjects(before);
		created.removeAll(projects);
		projects.addAll(created);
		assertEquals(1, created.size());
		return created.get(0);
	}

	private static Map<?, ?> update(TLObject project, String file) throws Exception {
		byte[] content = Files.readAllBytes(Path.of(file));
		return (Map<?, ?>) new RecordingJobMonitor().run(new UpdateFromEtsJob(null, null),
			List.of(BinaryDataFactory.createBinaryData(content, "application/zip", Path.of(file).getFileName().toString()),
				project));
	}

	private static String refusal(TLObject project, String file) throws Exception {
		try {
			update(project, file);
			fail("The update with " + file + " must be refused.");
			return null;
		} catch (TopLogicException ex) {
			return Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey());
		}
	}

	private static List<String> messages(Map<?, ?> report) {
		List<String> result = new ArrayList<>();
		for (Object message : (List<?>) report.get(ImportEtsProjectJob.MESSAGES)) {
			result.add(String.valueOf(((Map<?, ?>) message).get(ImportEtsProjectJob.MESSAGE)));
		}
		return result;
	}

	private static TLObject find(TLObject project, String extId) {
		for (TLObject obj : ProjectParts.objects(project)) {
			if (extId.equals(ExtIds.extId(obj))) {
				return obj;
			}
		}
		return null;
	}

	private static TLObject knx(TLObject project, String extId) {
		TLObject result = find(project, extId);
		assertNotNull("No " + extId, result);
		return result;
	}

	private static TLObject setting(TLObject device, String parameterSuffix) {
		for (TLObject setting : ProjectParts.values(device.tValueByName("settings"))) {
			if (ExtIds.extId((TLObject) setting.tValueByName("parameter")).endsWith(parameterSuffix)) {
				return setting;
			}
		}
		throw new AssertionError("No setting " + parameterSuffix);
	}

	private static List<String> names(BuildingDefinition part) {
		List<String> result = new ArrayList<>();
		for (BuildingDefinition child : part.getParts()) {
			result.add(child.getName());
		}
		return result;
	}

	private static ResKey text(String text) {
		return ResKey.builder().add(Locale.GERMAN, text).add(Locale.ENGLISH, text).build();
	}

	private static TLClassifier light() {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:FunctionKind")).getClassifier("light");
	}

	private static TLClass type(String name) {
		return (TLClass) TLModelUtil.findType(name);
	}

	private static TLObject create(String name) {
		return ModelService.getInstance().getFactory().createObject(type(name));
	}

	private static byte[] export(TLObject project) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		KnxprojExporter.export(project, out);
		return out.toByteArray();
	}

	/**
	 * The project with everything its composite references hold.
	 */
	private static Set<TLObject> contents(TLObject project) {
		Set<TLObject> result = new LinkedHashSet<>();
		collect(result, project);
		return result;
	}

	private static void collect(Set<TLObject> result, TLObject obj) {
		if (!result.add(obj)) {
			return;
		}
		for (TLStructuredTypePart part : ((TLStructuredType) obj.tType()).getAllParts()) {
			if (part instanceof TLReference reference && reference.isComposite() && ProjectParts.isStored(reference)) {
				for (TLObject child : ProjectParts.values(obj.tValue(reference))) {
					collect(result, child);
				}
			}
		}
	}

	/**
	 * Every stored value of every object of the project, by object.
	 */
	private static String dump(TLObject project) {
		Map<String, String> result = new TreeMap<>();
		for (TLObject obj : contents(project)) {
			StringBuilder values = new StringBuilder();
			for (TLStructuredTypePart part : ((TLStructuredType) obj.tType()).getAllParts()) {
				if (ProjectParts.isStored(part)) {
					Object value = obj.tValue(part);
					values.append(part.getName()).append('=')
						.append(value instanceof Collection<?> collection ? new ArrayList<>(collection) : value).append(';');
				}
			}
			result.put(String.valueOf(obj.tId()), values.toString());
		}
		return result.toString();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProjectUpdate.class, ModelService.Module.INSTANCE,
			LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE, IdRenames.Module.INSTANCE));
	}

}
