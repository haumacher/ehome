/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeCatalog;
import de.haumacher.ehome.zigbee.ZigbeeCatalogJson;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * Probe of the Zigbee catalog composed with the installer's own naming (haumacher/ehome #64): a
 * device the installer named with umlauts and spaces gets datapoints whose ids are valid in the
 * runtime image and whose labels keep the name; a catalog file that renames a function entry changes
 * what every existing device shows the moment it is imported (the match is derived), and the import
 * of the unchanged file reports nothing changed.
 */
@SuppressWarnings("javadoc")
public class TestZigbeeCatalogProbe extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	private static final String SEED = "src/main/webapp/WEB-INF/zigbee-catalog/philips-sml002.json";

	private TLObject _seededBefore;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_seededBefore = ZigbeeCatalog.type(ZigbeeCatalog.root(), "Philips", "SML002");
		if (_seededBefore == null) {
			// An earlier test removed the seeded type: bring it back from the shipped file.
			String seed = Files.readString(Path.of(SEED), StandardCharsets.UTF_8);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), seed, ZigbeeCatalogJson.Mode.UPDATE);
				tx.commit();
			}
		}
	}

	@Override
	protected void tearDown() throws Exception {
		// Leave the catalog as the other Zigbee tests expect it: without the Philips type.
		TLObject type = ZigbeeCatalog.type(ZigbeeCatalog.root(), "Philips", "SML002");
		if (type != null) {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				type.tDelete();
				tx.commit();
			}
		}
		super.tearDown();
	}

	public void testNamesAndRenaming() throws Exception {
		String text = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		TLObject project = project("Zigbee catalog probe", "P-TEST-64-CATALOG-PROBE");
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
				tx.commit();
			}
			TLObject device = ZigbeeImport.devices(project).get(0);
			assertNotNull(ZigbeeCatalog.type(device));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				device.tUpdateByName("name", "Küche Süd / Melder");
				tx.commit();
			}
			List<TLObject> created;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				created = inLocale(Locale.GERMAN, () -> ZigbeeBindings.createDatapoints(device));
				tx.commit();
			}
			assertEquals(4, created.size());
			for (TLObject datapoint : created) {
				String id = ApplicationDatapoints.id(datapoint);
				assertTrue(id, id.matches("[a-z][a-z0-9._-]*"));
				assertTrue(id, id.endsWith("-temperatur") || id.endsWith("-helligkeit") || id.endsWith("-bewegung")
					|| id.endsWith("-batterie"));
			}
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			TLObject occupancy = function(device, "occupancy");
			String label = String.valueOf(derived(Locale.GERMAN, ZigbeeBindings.boundDatapoints(occupancy).get(0), "label"));
			assertTrue(label, label.contains("Küche Süd") && label.contains("Bewegung"));

			// The catalog file renames the occupancy entry: every device shows it at once.
			String seed = Files.readString(Path.of(SEED), StandardCharsets.UTF_8);
			assertTrue(seed, seed.contains("\"Bewegung\""));
			String renamed = seed.replace("\"Bewegung\"", "\"Anwesenheit\"");
			ZigbeeCatalogJson.Result first;
			ZigbeeCatalogJson.Result again;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				first = ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), renamed, ZigbeeCatalogJson.Mode.UPDATE);
				tx.commit();
			}
			assertEquals(first.toString(), 1, ZigbeeCatalog.types(ZigbeeCatalog.root()).stream()
				.filter(t -> "SML002".equals(t.tValueByName("model"))).count());
			assertEquals("Anwesenheit", inLocale(Locale.GERMAN, () -> ZigbeeStatus.functionLabel(occupancy)));
			assertEquals("Motion", inLocale(Locale.ENGLISH, () -> ZigbeeStatus.functionLabel(occupancy)));
			// The datapoint created before keeps its id and label: the catalog names new ones only.
			assertTrue(label, label.equals(String.valueOf(derived(Locale.GERMAN,
				ZigbeeBindings.boundDatapoints(occupancy).get(0), "label"))));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				again = ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), renamed, ZigbeeCatalogJson.Mode.UPDATE);
				tx.commit();
			}
			assertEquals(1, first.updated());
			assertEquals(0, first.created());
			assertEquals("The same file again changes nothing.", 1, again.unchanged());
			assertEquals(0, again.updated());
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	private static TLObject function(TLObject device, String key) {
		for (Object function : (Iterable<?>) device.tValueByName("functions")) {
			if (key.equals(((TLObject) function).tValueByName("function"))) {
				return (TLObject) function;
			}
		}
		throw new AssertionError("No function " + key);
	}

	private static Object derived(Locale locale, TLObject obj, String attribute) {
		return inLocale(locale, () -> obj.tValueByName(attribute));
	}

	private static <T> T inLocale(Locale locale, Supplier<T> value) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			return value.get();
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static TLObject project(String name, String extId) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", name);
			project.tUpdateByName("extId", extId);
			tx.commit();
			return project;
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeCatalogProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
