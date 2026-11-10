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
			TLObject ga3 = knx(project, P + "GA-3");
			TLObject flur = knx(project, P + "DI-2");
			TLObject kueche = knx(project, P + "BP-3");
			TLObject erdgeschoss = knx(project, P + "BP-2");
			TLObject rule;
			TLObject trigger;
			TLObject write;
			TLObject reaction;
			TLObject function;
			TLObject zigbee;
			TLObject telegram;
			TLObject controller;
			try (Transaction tx = kb().beginTransaction()) {
				ga3.tUpdateByName("runtimeWritable", Boolean.TRUE);
				rule = RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				rule.tUpdateByName("identifier", "licht");
				rule.tUpdateByName("name", text("Licht"));
				reaction = RuleFunctions.create(rule, "reactions", type("ehome.runtime:Reaction"));
				trigger = RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.tUpdateByName("datapoint", ga3);
				write = RuleFunctions.create(reaction, "actions", type("ehome.runtime:WriteAction"));
				write.tUpdateByName("datapoint", ga3);
				write.tUpdateByName("value", "true");
				reaction.tUpdateByName("condition", "@\"0/0/3\"");

				function = create("ehome.runtime:Function");
				function.tUpdateByName("name", text("Licht Küche"));
				function.tUpdateByName("kind", light());
				function.tUpdateByName("command", ga3);
				function.tUpdateByName("device", flur);
				function.tUpdateByName("room", kueche);
				project.tUpdateByName("functions", List.of(function));

				zigbee = create("ehome.zigbee:ZigbeeDevice");
				zigbee.tUpdateByName("ieee", "001788010f069c8e");
				zigbee.tUpdateByName("location", kueche);
				project.tUpdateByName("zigbeeDevices", List.of(zigbee));

				TLObject recording = create("ehome.monitor:Recording");
				recording.tUpdateByName("name", "Aufzeichnung");
				telegram = create("ehome.monitor:Telegram");
				telegram.tUpdateByName("groupAddress", ga3);
				telegram.tUpdateByName("address", ga3.tValueByName("address"));
				telegram.tUpdateByName("sender", flur);
				recording.tUpdateByName("telegrams", List.of(telegram));
				project.tUpdateByName("recordings", List.of(recording));

				controller = create("ehome.runtime:Controller");
				controller.tUpdateByName("name", "Flur");
				controller.tUpdateByName("port", Integer.valueOf(80));
				project.tUpdateByName("controllers", List.of(controller));
				project.tUpdateByName("controller", controller);
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
			assertEquals("Licht Küche", ga3.tValueByName("name"));
			assertEquals(Boolean.TRUE, ga3.tValueByName("runtimeWritable"));
			assertSame(ga3, trigger.tValueByName("datapoint"));
			assertSame(ga3, write.tValueByName("datapoint"));
			assertSame(ga3, function.tValueByName("command"));
			assertSame(ga3, telegram.tValueByName("groupAddress"));
			// The expression naming the address follows it (IdRenames).
			assertEquals("@\"0/0/4\"", reaction.tValueByName("condition"));

			// The device moved to another line keeps its identity and its function.
			assertTrue(flur.tValid());
			assertSame(flur, function.tValueByName("device"));
			assertSame(flur, telegram.tValueByName("sender"));
			assertEquals(P + "L-2", ExtIds.extId(flur.tContainer()));
			assertSame(kueche, zigbee.tValueByName("location"));
			assertSame(controller, project.tValueByName("controller"));

			// Additions and removals.
			assertEquals("Neu", knx(project, P + "GA-13").tValueByName("name"));
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
			TLObject ga1 = knx(project, P + "GA-1");
			TLObject ga3 = knx(project, P + "GA-3");
			Object etsName = ga1.tValueByName("name");
			Object etsDescription = ga1.tValueByName("description");
			assertFalse(String.valueOf(etsName).isBlank());

			// An export of ETS that has no name and no description for GA-1.
			try (Transaction tx = kb().beginTransaction()) {
				ga1.tUpdateByName("name", null);
				ga1.tUpdateByName("description", null);
				tx.commit();
			}
			Path unnamed = Path.of("target/update/TestUpdateUnnamed.knxproj");
			Files.createDirectories(unnamed.getParent());
			Files.write(unnamed, export(project));

			TLObject reaction;
			TLObject write;
			try (Transaction tx = kb().beginTransaction()) {
				ga1.tUpdateByName("name", "Zentral Licht");
				ga1.tUpdateByName("description", "Aus E-Home");
				ga3.tUpdateByName("name", "Mein Name");
				ga3.tUpdateByName("identifier", "kueche.licht");
				ga3.tUpdateByName("runtimeWritable", Boolean.TRUE);
				TLObject rule = RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				rule.tUpdateByName("identifier", "licht");
				reaction = RuleFunctions.create(rule, "reactions", type("ehome.runtime:Reaction"));
				TLObject trigger = RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.tUpdateByName("datapoint", ga1);
				write = RuleFunctions.create(reaction, "actions", type("ehome.runtime:WriteAction"));
				write.tUpdateByName("datapoint", ga3);
				write.tUpdateByName("value", "not @\"0/0/3\"");
				tx.commit();
			}
			// Entered with the address, stored with the identifier.
			assertEquals("not @kueche.licht", write.tValueByName("value"));

			// The file without the name of GA-1 keeps E-Home's name and description; GA-3's name in the
			// file wins over the one given in E-Home.
			update(project, unnamed.toString());
			assertEquals("Zentral Licht", ga1.tValueByName("name"));
			assertEquals("Aus E-Home", ga1.tValueByName("description"));
			assertEquals("GA3", ga3.tValueByName("name"));
			assertEquals("kueche.licht", ga3.tValueByName("identifier"));

			// Re-addressed in ETS: the identifier, the rule and the datapoint's id stay, the binding follows.
			update(project, NEW);
			assertTrue(ga3.tValid());
			assertEquals("Licht Küche", ga3.tValueByName("name"));
			assertEquals("kueche.licht", ga3.tValueByName("identifier"));
			assertEquals("kueche.licht", Pages.imageId(ga3));
			assertEquals("not @kueche.licht", write.tValueByName("value"));
			assertSame(ga3, write.tValueByName("datapoint"));
			assertEquals(etsName, ga1.tValueByName("name"));
			assertEquals(etsDescription == null ? "Aus E-Home" : etsDescription, ga1.tValueByName("description"));
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
			TLObject jalousie = knx(project, P + "GA-12");
			TLObject used;
			TLObject named;
			try (Transaction tx = kb().beginTransaction()) {
				used = RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				used.tUpdateByName("identifier", "jalousie");
				used.tUpdateByName("name", text("Jalousie fahren"));
				TLObject reaction = RuleFunctions.create(used, "reactions", type("ehome.runtime:Reaction"));
				TLObject trigger = RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.tUpdateByName("datapoint", jalousie);
				named = RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				named.tUpdateByName("identifier", "unbenutzt");
				named.tUpdateByName("name", text("Unbenutzt prüfen"));
				TLObject other = RuleFunctions.create(named, "reactions", type("ehome.runtime:Reaction"));
				other.tUpdateByName("condition", "@\"1/0/1\"");
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
				public Modification createModification(KnowledgeBase kb, CommitActions actions,
						Map<ObjectKey, ? extends KnowledgeItem> created, Map<ObjectKey, ? extends KnowledgeItem> updated,
						Map<ObjectKey, ? extends KnowledgeItem> removed) {
					for (Map<ObjectKey, ? extends KnowledgeItem> changes : List.of(updated, removed)) {
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
			TLObject erdgeschoss = knx(project, P + "BP-2");
			try (Transaction tx = kb().beginTransaction()) {
				List<TLObject> parts = new ArrayList<>(ProjectParts.values(erdgeschoss.tValueByName("parts")));
				parts.add(0, parts.remove(2));
				erdgeschoss.tUpdateByName("parts", parts);
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
			TLObject address;
			TLObject trigger;
			TLObject function;
			try (Transaction tx = kb().beginTransaction()) {
				planned = DevicePlanning.create(line, (TLObject) kueche.tValueByName("type"),
					(TLObject) kueche.tValueByName("program"), Integer.valueOf(5), "Geplant", null);
				address = GroupAddresses.create(range, Integer.valueOf(5), "Eigene", null);
				TLObject rule = RuleFunctions.create(project, "rules", type("ehome.runtime:Rule"));
				rule.tUpdateByName("identifier", "eigene");
				rule.tUpdateByName("name", text("Eigene"));
				TLObject reaction = RuleFunctions.create(rule, "reactions", type("ehome.runtime:Reaction"));
				trigger = RuleFunctions.create(reaction, "triggers", type("ehome.runtime:DatapointTrigger"));
				trigger.tUpdateByName("datapoint", address);
				function = create("ehome.runtime:Function");
				function.tUpdateByName("name", text("Geplant"));
				function.tUpdateByName("kind", light());
				function.tUpdateByName("device", planned);
				project.tUpdateByName("functions", List.of(function));
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
			assertEquals("Neu", address.tValueByName("name"));
			assertFalse(ExtIds.isCreatedInApp(address));
			assertSame(address, trigger.tValueByName("datapoint"));
			assertTrue(messages.toString(), messages.stream().anyMatch(m -> m.contains("adopted") && m.contains("Neu (0/0/5)")));
			assertEquals(1, SharedData.within("ehome.logical:GroupAddress", List.of(project)).stream()
				.filter(a -> "0/0/5".equals(Pages.imageId(a))).count());

			// The file has a device of the same product at the place of the planned one: adopted.
			messages = messages(update(project, ADOPT));
			assertTrue(planned.tValid());
			assertEquals(P + "DI-5", ExtIds.extId(planned));
			assertEquals("Taster Diele", planned.tValueByName("title"));
			assertFalse(ExtIds.isCreatedInApp(planned));
			assertSame(planned, function.tValueByName("device"));
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

	private static List<String> names(TLObject part) {
		List<String> result = new ArrayList<>();
		for (TLObject child : ProjectParts.values(part.tValueByName("parts"))) {
			result.add((String) child.tValueByName("name"));
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
