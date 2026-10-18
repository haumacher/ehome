/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.SimpleInstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.TLConstraints;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.I18NConstants;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeCatalog;
import de.haumacher.ehome.zigbee.ZigbeeCatalogJson;
import de.haumacher.ehome.zigbee.ZigbeeCatalogSeed;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeReporting;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * The catalog of known Zigbee devices (haumacher/ehome #64, <code>doc/zigbee.md</code> "Catalog") on
 * the motion sensor of <code>src/test/fixtures/zigbee/motion-sensor.export.json</code> (a Philips Hue
 * SML002, the device type E-Home ships in <code>WEB-INF/zigbee-catalog/philips-sml002.json</code>):
 * the match of a device to its type derived when it is read, the catalog's names in both languages,
 * the hidden controller endpoint, "Create datapoints" with the recommended types, units and reporting
 * (an installer's override kept), a device without a type as before, a type added later, the
 * constraints of an entry, and the JSON round trip without duplicates.
 *
 * <p>
 * The device types this test creates live in a catalog of its own or are deleted again, the shipped
 * type included: the other Zigbee tests share the database and describe the fixture's motion sensor
 * as a device without a type (the first test of the database seeds it, the others import the shipped
 * file again).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeCatalog extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	private static final String SEED = "src/main/webapp/WEB-INF/zigbee-catalog/philips-sml002.json";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		// Once per database: the first call loads the shipped file, a later one (another test, a restart) nothing.
		boolean first = !ZigbeeCatalogSeed.seed().isEmpty();
		assertTrue(ZigbeeCatalogSeed.seed().isEmpty());
		if (!first) {
			// The type was removed by the tearDown of an earlier test: the seed does not bring it back.
			assertNull("A deleted shipped type does not come back.", seeded());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), Files.readString(Path.of(SEED), StandardCharsets.UTF_8),
					ZigbeeCatalogJson.Mode.KEEP);
				tx.commit();
			}
		}
		assertNotNull("The shipped device type is in the catalog.", seeded());
	}

	/**
	 * Removes the shipped type again: the other Zigbee tests share the database and describe the
	 * fixture's motion sensor as a device without a type.
	 */
	@Override
	protected void tearDown() throws Exception {
		TLObject type = seeded();
		if (type != null) {
			delete(type);
		}
		super.tearDown();
	}

	public void testMotionSensor() throws Exception {
		TLObject project = project("Zigbee catalog", "P-TEST-64-CATALOG");
		try {
			importFixture(project, Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8));
			TLObject device = ZigbeeImport.devices(project).get(0);
			assertSame(seeded(), ZigbeeCatalog.type(device));
			assertSame("Derived when read, not stored.", seeded(), derived(Locale.GERMAN, device, "deviceType"));
			assertEquals("Philips SML002", device.tValueByName("name"));

			List<TLObject> functions = objects(device, "functions");
			TLObject temperature = functions.get(0);
			TLObject illuminance = functions.get(1);
			TLObject occupancy = functions.get(2);
			TLObject battery = functions.get(3);
			TLObject action = functions.get(4);
			assertEquals("occupancy", occupancy.tValueByName("function"));
			assertEquals("Bewegung", inLocale(Locale.GERMAN, () -> ZigbeeStatus.functionLabel(occupancy)));
			assertEquals("Motion", inLocale(Locale.ENGLISH, () -> ZigbeeStatus.functionLabel(occupancy)));
			assertEquals("Philips SML002 Bewegung (2)", derived(Locale.GERMAN, occupancy, "displayName"));
			assertEquals("Philips SML002 Motion (2)", derived(Locale.ENGLISH, occupancy, "displayName"));
			assertEquals("Helligkeit", inLocale(Locale.GERMAN, () -> ZigbeeStatus.functionLabel(illuminance)));
			assertEquals(Boolean.TRUE, derived(Locale.GERMAN, action, "hidden"));
			assertEquals(Boolean.FALSE, derived(Locale.GERMAN, occupancy, "hidden"));
			assertEquals("A hidden function without a name of the catalog keeps the name of its key.", "Aktion",
				inLocale(Locale.GERMAN, () -> ZigbeeStatus.functionLabel(action)));

			// The installer's override of the battery's reporting is kept, not completed by the recommendation.
			update(battery, ZigbeeReporting.MAX, Integer.valueOf(30000));
			List<TLObject> created;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				created = inLocale(Locale.GERMAN, () -> ZigbeeBindings.createDatapoints(device));
				tx.commit();
			}
			assertEquals(4, created.size());
			assertDatapoint(created.get(0), temperature, "philips-sml002-temperatur", "real", "°C",
				"Philips SML002 Temperatur", "Philips SML002 Temperature");
			assertDatapoint(created.get(1), illuminance, "philips-sml002-helligkeit", "real", "lx",
				"Philips SML002 Helligkeit", "Philips SML002 Illuminance");
			assertDatapoint(created.get(2), occupancy, "philips-sml002-bewegung", "boolean", null,
				"Philips SML002 Bewegung", "Philips SML002 Motion");
			assertDatapoint(created.get(3), battery, "philips-sml002-batterie", "real", "%",
				"Philips SML002 Batterie", "Philips SML002 Battery");
			assertTrue("The hidden function stays unbound.", ZigbeeBindings.boundDatapoints(action).isEmpty());

			assertReporting(temperature, Integer.valueOf(60), Integer.valueOf(3600), Double.valueOf(0.5));
			assertReporting(illuminance, Integer.valueOf(60), Integer.valueOf(3600), null);
			assertReporting(occupancy, null, null, null);
			assertReporting(battery, null, Integer.valueOf(30000), null);
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());

			// A second call creates nothing more, and the command says why.
			assertEquals(I18NConstants.VETO_NOTHING_TO_CREATE, ZigbeeBindings.createDatapointsVeto(device));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				assertTrue(ZigbeeBindings.createDatapoints(device).isEmpty());
				tx.commit();
			}
			assertEquals(4, ApplicationDatapoints.of(project).size());

			// "Create datapoint" on the hidden function itself works: the installer chose it.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject datapoint = inLocale(Locale.GERMAN, () -> ZigbeeBindings.createDatapoint(action));
				assertEquals("philips-sml002-action", ApplicationDatapoints.id(datapoint));
				assertEquals("enumeration", ApplicationDatapoints.valueType(datapoint));
				tx.rollback();
			}
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	public void testUnknownDeviceAndTypeAddedLater() throws Exception {
		Map<?, ?> export = (Map<?, ?>) Json.parse(Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8), 100);
		put(((List<?>) export.get("devices")).get(0), "model", "SML999-TEST");
		TLObject project = project("Zigbee catalog unknown", "P-TEST-64-CATALOG-UNKNOWN");
		TLObject later = null;
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.read(export));
				tx.commit();
			}
			TLObject device = ZigbeeImport.devices(project).get(0);
			TLObject occupancy = objects(device, "functions").get(2);
			TLObject action = objects(device, "functions").get(4);
			assertNull(ZigbeeCatalog.type(device));
			assertNull(derived(Locale.GERMAN, device, "deviceType"));
			assertEquals("Anwesenheit", inLocale(Locale.GERMAN, () -> ZigbeeStatus.functionLabel(occupancy)));
			assertEquals("Philips SML999-TEST Occupancy (2)", derived(Locale.ENGLISH, occupancy, "displayName"));
			assertEquals(Boolean.FALSE, derived(Locale.GERMAN, action, "hidden"));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject datapoint = ZigbeeBindings.createDatapoint(occupancy);
				assertEquals("philips-sml999-test-occupancy", ApplicationDatapoints.id(datapoint));
				assertNull("No recommendation, no override.", occupancy.tValueByName(ZigbeeReporting.MIN));
				tx.rollback();
			}

			// A type added later applies to the device imported before, without importing it again.
			String text = Files.readString(Path.of(SEED), StandardCharsets.UTF_8).replace("SML002", "SML999-TEST");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				later = ZigbeeCatalogJson.importText(ZigbeeCatalog.root(), text, ZigbeeCatalogJson.Mode.UPDATE).types()
					.get(0);
				tx.commit();
			}
			assertSame(later, derived(Locale.GERMAN, device, "deviceType"));
			assertEquals("Motion", inLocale(Locale.ENGLISH, () -> ZigbeeStatus.functionLabel(occupancy)));
			assertEquals(Boolean.TRUE, derived(Locale.GERMAN, action, "hidden"));
			assertNull("Nothing is copied into the project.", occupancy.tValueByName(ZigbeeReporting.MIN));
		} finally {
			SharedData.deleteProjects(List.of(project));
			if (later != null) {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					later.tDelete();
					tx.commit();
				}
			}
		}
	}

	public void testConstraints() throws Exception {
		TLObject catalog = catalog();
		try {
			TLObject entry;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject type = create(ZigbeeCatalog.DEVICE_TYPE);
				type.tUpdateByName("manufacturer", "Test");
				type.tUpdateByName("model", "T-1");
				type.tUpdateByName("name", FunctionProposal.text(ResKey.text("Test device")));
				entry = create(ZigbeeCatalog.FUNCTION_ENTRY);
				entry.tUpdateByName("endpoint", Integer.valueOf(1));
				entry.tUpdateByName("function", "temperature");
				type.tUpdateByName("functions", List.of(entry));
				catalog.tUpdateByName("types", List.of(type));
				tx.commit();
			}

			// Saved in the UI: the constraint refuses with the reason; at the commit the model refuses as well.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				entry.tUpdateByName("type", valueType("boolean"));
				ResKey problem = problem(entry, "type");
				assertEquals(I18NConstants.CATALOG_TYPE_MISFIT__TYPE_FUNCTION_VALUE_TYPE.fill("boolean", "temperature",
					"real"), problem);
				assertEquals("The datapoint type boolean does not fit the function temperature, which carries the value type real.",
					Resources.getInstance(Locale.ENGLISH).getString(problem));
				entry.tUpdateByName("type", valueType("signed"));
				assertNull("The numeric types are interchangeable, as for a binding.", problem(entry, "type"));
				entry.tUpdateByName("type", null);
				entry.tUpdateByName(ZigbeeReporting.MIN, Integer.valueOf(900));
				assertEquals(I18NConstants.REPORTING_MIN_ABOVE_MAX, problem(entry, ZigbeeReporting.MIN));
				entry.tUpdateByName(ZigbeeReporting.MIN, null);
				entry.tUpdateByName("function", "occupancy");
				entry.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(1));
				assertEquals(I18NConstants.REPORTING_NO_CHANGE_DISCRETE__FUNCTION.fill("occupancy"),
					problem(entry, ZigbeeReporting.CHANGE));
				tx.rollback();
			}
			assertRefused(entry, "type", valueType("boolean"));
			assertRefused(entry, ZigbeeReporting.CHANGE, Double.valueOf(400));

			// The import refuses the same, before it writes anything.
			String misfit = Files.readString(Path.of(SEED), StandardCharsets.UTF_8)
				.replace("\"type\": \"real\",\n\t\t\t\t\t\"unit\": \"°C\"", "\"type\": \"boolean\",\n\t\t\t\t\t\"unit\": \"°C\"");
			assertFalse(misfit.equals(Files.readString(Path.of(SEED), StandardCharsets.UTF_8)));
			assertImportRefused(catalog, misfit, "types[0].functions[1].type");
			String reporting = Files.readString(Path.of(SEED), StandardCharsets.UTF_8).replace("\"max\": 43200",
				"\"max\": 60");
			assertImportRefused(catalog, reporting, "types[0].functions[3].reporting.max");
			assertEquals("Nothing was written.", 1, ZigbeeCatalog.types(catalog).size());
		} finally {
			delete(catalog);
		}
	}

	public void testJsonRoundTrip() throws Exception {
		String text = ZigbeeCatalogJson.write(List.of(seeded()));
		assertEquals(Json.parse(Files.readString(Path.of(SEED), StandardCharsets.UTF_8), 100), Json.parse(text, 100));
		TLObject catalog = catalog();
		try {
			ZigbeeCatalogJson.Result first = importText(catalog, text);
			assertEquals(1, first.created());
			TLObject copy = first.types().get(0);
			assertNotSame(seeded(), copy);
			assertEquals(ZigbeeCatalogJson.write(List.of(seeded())), ZigbeeCatalogJson.write(List.of(copy)));
			assertEquals("Philips SML002", copy.tValueByName("manufacturer") + " " + copy.tValueByName("model"));
			assertEquals("Hue Bewegungsmelder", Resources.getInstance(Locale.GERMAN).getString((ResKey) copy.tValueByName("name")));
			assertEquals(5, ZigbeeCatalog.entries(copy).size());

			ZigbeeCatalogJson.Result second = importText(catalog, text);
			assertEquals(0, second.created());
			assertEquals(1, second.unchanged());
			assertSame(copy, second.types().get(0));
			assertEquals("Matched by manufacturer and model, not duplicated.", 1, ZigbeeCatalog.types(catalog).size());

			// A changed file updates the type in place: a renamed function, one dropped.
			String changed = text.replace("\"Helligkeit\"", "\"Lichtstärke\"").replaceAll(
				",\\s*\\{\\s*\"endpoint\": 1,\\s*\"function\": \"action\",\\s*\"hidden\": true\\s*\\}", "");
			ZigbeeCatalogJson.Result third = importText(catalog, changed);
			assertEquals(1, third.updated());
			assertSame(copy, third.types().get(0));
			assertEquals(4, ZigbeeCatalog.entries(copy).size());
			assertEquals("Lichtstärke", Resources.getInstance(Locale.GERMAN).getString(
				(ResKey) ZigbeeCatalog.entry(copy, Integer.valueOf(2), "illuminance").tValueByName("name")));
			assertEquals("seeded type untouched", 5, ZigbeeCatalog.entries(seeded()).size());
		} finally {
			delete(catalog);
		}
	}

	private static ZigbeeCatalogJson.Result importText(TLObject catalog, String text) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeCatalogJson.Result result = ZigbeeCatalogJson.importText(catalog, text, ZigbeeCatalogJson.Mode.UPDATE);
			tx.commit();
			return result;
		}
	}

	private static void assertImportRefused(TLObject catalog, String text, String path) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeCatalogJson.importText(catalog, text, ZigbeeCatalogJson.Mode.UPDATE);
			fail("Refused import accepted: " + path);
		} catch (TopLogicException ex) {
			String message = Resources.getInstance(Locale.ENGLISH).getString(ex.getErrorKey());
			assertTrue(message, message.startsWith("The catalog file is refused at " + path + ": "));
		}
	}

	private static void assertRefused(TLObject obj, String attribute, Object value) {
		Object before = obj.tValueByName(attribute);
		try {
			update(obj, attribute, value);
			fail("Stored a value the constraint refuses: " + attribute);
		} catch (TopLogicException ex) {
			assertEquals(before, obj.tValueByName(attribute));
		}
	}

	private static void assertDatapoint(TLObject datapoint, TLObject function, String id, String type, String unit,
			String de, String en) {
		assertSame(function, ZigbeeBindings.function(datapoint));
		assertEquals(id, ApplicationDatapoints.id(datapoint));
		assertEquals(type, ApplicationDatapoints.valueType(datapoint));
		assertEquals(unit, datapoint.tValueByName("unit"));
		ResKey label = (ResKey) datapoint.tValueByName("label");
		assertEquals(de, Resources.getInstance(Locale.GERMAN).getString(label));
		assertEquals(en, Resources.getInstance(Locale.ENGLISH).getString(label));
	}

	private static void assertReporting(TLObject function, Integer min, Integer max, Double change) {
		assertEquals(min, function.tValueByName(ZigbeeReporting.MIN));
		assertEquals(max, function.tValueByName(ZigbeeReporting.MAX));
		assertEquals(change, function.tValueByName(ZigbeeReporting.CHANGE));
	}

	private static TLObject seeded() {
		return ZigbeeCatalog.type(ZigbeeCatalog.root(), "Philips", "SML002");
	}

	private static void importFixture(TLObject project, String text) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
			tx.commit();
		}
	}

	/** A catalog of the test's own, beside the application's. */
	private static TLObject catalog() {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject result = create(ZigbeeCatalog.CATALOG);
			tx.commit();
			return result;
		}
	}

	private static void delete(TLObject obj) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tDelete();
			tx.commit();
		}
	}

	private static TLClassifier valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	/** The refusal of the attribute's constraints, as a form shows it when the value is saved. */
	private static ResKey problem(TLObject obj, String attribute) {
		TLStructuredTypePart part = obj.tType().getPart(attribute);
		TLConstraints constraints = part.getAnnotation(TLConstraints.class);
		assertNotNull(attribute, constraints);
		for (PolymorphicConfiguration<? extends ConstraintCheck> config : constraints.getConstraints()) {
			ResKey problem = SimpleInstantiationContext.CREATE_ALWAYS_FAIL_IMMEDIATELY.getInstance(config).check(obj, part);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	/** A derived attribute, read as root (derived attributes are computed with the reader's rights). */
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

	@SuppressWarnings("unchecked")
	private static void put(Object object, String member, Object value) {
		((Map<String, Object>) object).put(member, value);
	}

	private static void update(TLObject obj, String attribute, Object value) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			obj.tUpdateByName(attribute, value);
			tx.commit();
		}
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static TLObject project(String name, String extId) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject project = create("ehome.project:Project");
			project.tUpdateByName("name", name);
			project.tUpdateByName("extId", extId);
			tx.commit();
			return project;
		}
	}

	private static List<TLObject> objects(TLObject owner, String reference) {
		List<TLObject> result = new ArrayList<>();
		for (Object element : (Iterable<?>) owner.tValueByName(reference)) {
			result.add((TLObject) element);
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeCatalog.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
