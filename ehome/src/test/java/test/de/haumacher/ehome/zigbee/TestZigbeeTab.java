/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.zigbee.I18NConstants;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeStatus;

/**
 * The Zigbee tab as a product (haumacher/ehome #64): the functions by their names in both languages
 * (an unknown key of a newer controller as it is, an IAS zone by the datapoint its zone type gives),
 * the devices of a room, and the removal of the functions the controller no longer exports.
 *
 * <p>
 * The motion sensor of <code>src/test/fixtures/zigbee/motion-sensor.export.json</code> with two more
 * functions (an IAS contact zone and a key E-Home does not know) is imported into a project with a
 * building and a room; the sensor is placed in the room. Occupancy and illuminance get datapoints,
 * then an export without them marks both absent; illuminance is unbound, and the cleanup removes it
 * and keeps occupancy, naming its datapoint. Costs well below a second: no ETS import.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeTab extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	private static final String IEEE = "0017880106498a49";

	public void testTab() throws Exception {
		Map<String, Object> export = fixture();
		List<Object> functions = functions(export);
		functions.add(function("zigbee." + IEEE + ".2.contact", "ias_zone", 1280, 2, "boolean"));
		functions.add(function("zigbee." + IEEE + ".2.foo_bar", "foo_bar", 64512, 0, "unsigned"));

		TLObject project;
		TLObject room;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "Zigbee tab");
			TLObject building = create("ehome.building:Building");
			building.tUpdateByName("name", "House");
			room = create("ehome.building:Room");
			room.tUpdateByName("name", "Living room");
			building.tUpdateByName("parts", List.of(room));
			project.tUpdateByName("buildings", List.of(building));
			tx.commit();
		}
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.read(export));
				tx.commit();
			}
			TLObject device = ZigbeeImport.devices(project).get(0);
			TLObject occupancy = function(device, "occupancy");
			TLObject illuminance = function(device, "illuminance");
			TLObject contact = function(device, "ias_zone");
			TLObject unknown = function(device, "foo_bar");

			// The functions by their names.
			assertEquals("Philips SML002 Anwesenheit (2)", inLocale(Locale.GERMAN, occupancy, "displayName"));
			assertEquals("Philips SML002 Occupancy (2)", inLocale(Locale.ENGLISH, occupancy, "displayName"));
			assertEquals("Philips SML002 foo_bar (2)", inLocale(Locale.GERMAN, unknown, "displayName"));
			assertEquals("Philips SML002 foo_bar (2)", inLocale(Locale.ENGLISH, unknown, "displayName"));
			assertEquals("Philips SML002 Kontakt (2)", inLocale(Locale.GERMAN, contact, "displayName"));
			assertEquals("Contact", label(Locale.ENGLISH, contact));
			assertEquals("Ein/Aus", label(Locale.GERMAN, "on_off"));
			assertEquals("Helligkeit", label(Locale.GERMAN, "level"));
			assertEquals("Farbtemperatur", label(Locale.GERMAN, "color_temperature"));
			assertEquals("Temperatur", label(Locale.GERMAN, "temperature"));
			assertEquals("Luftfeuchte", label(Locale.GERMAN, "humidity"));
			assertEquals("Beleuchtungsstärke", label(Locale.GERMAN, "illuminance"));
			assertEquals("Bewegung", label(Locale.GERMAN, "motion"));
			assertEquals("Rauch", label(Locale.GERMAN, "smoke"));
			assertEquals("Wasser", label(Locale.GERMAN, "water"));
			assertEquals("Alarm", label(Locale.GERMAN, "alarm"));
			assertEquals("Batterie", label(Locale.GERMAN, "battery"));
			assertEquals("Mehrzustand", label(Locale.GERMAN, "multistate"));
			assertEquals("Aktion", label(Locale.GERMAN, "action"));

			// The room lists the device placed in it.
			assertTrue(((Collection<?>) room.tValueByName("zigbeeDevices")).isEmpty());
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				device.tUpdateByName("location", room);
				tx.commit();
			}
			assertEquals(List.of(device), new ArrayList<>((Collection<?>) room.tValueByName("zigbeeDevices")));

			// Nothing absent: the cleanup refuses.
			assertEquals(I18NConstants.VETO_NOTHING_ABSENT, ZigbeeStatus.removeAbsentVeto(device));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeStatus.removeAbsent(device);
				fail("Nothing is absent.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.VETO_NOTHING_ABSENT, ex.getErrorKey());
			}

			// Two functions bound, then no longer exported: both kept as absent; one unbound.
			TLObject occupancyDp;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				occupancyDp = ZigbeeBindings.createDatapoint(occupancy);
				ZigbeeBindings.createDatapoint(illuminance);
				tx.commit();
			}
			Map<String, Object> reduced = fixture();
			functions(reduced).removeIf(f -> List.of("occupancy", "illuminance").contains(((Map<?, ?>) f).get("function")));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.read(reduced));
				ZigbeeBindings.unbind(illuminance);
				tx.commit();
			}
			assertEquals(Boolean.TRUE, occupancy.tValueByName("absent"));
			assertEquals(Boolean.TRUE, illuminance.tValueByName("absent"));
			assertNull(ZigbeeStatus.removeAbsentVeto(device));

			String message;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				message = ZigbeeStatus.removeAbsent(device);
				tx.commit();
			}
			assertEquals(Resources.getInstance().getString(I18NConstants.ABSENT_REMOVED__REMOVED_KEPT_DETAILS.fill(
				Integer.valueOf(1), Integer.valueOf(1),
				Resources.getInstance().getString(I18NConstants.ABSENT_KEPT__FUNCTION_DATAPOINTS.fill(
					"zigbee." + IEEE + ".2.occupancy", DatapointNames.name(occupancyDp))))), message);
			assertFalse(illuminance.tValid());
			assertTrue(occupancy.tValid());
			assertTrue(((Collection<?>) device.tValueByName("functions")).contains(occupancy));
			assertEquals(List.of(occupancyDp), ZigbeeBindings.boundDatapoints(occupancy));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> fixture() throws Exception {
		return (Map<String, Object>) Json.parse(Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8), 16);
	}

	@SuppressWarnings("unchecked")
	private static List<Object> functions(Map<String, Object> export) {
		Map<String, Object> device = (Map<String, Object>) ((List<Object>) export.get("devices")).get(0);
		List<Object> result = new ArrayList<>((List<Object>) device.get("functions"));
		device.put("functions", result);
		return result;
	}

	private static Map<String, Object> function(String name, String key, int cluster, int attribute, String type) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("name", name);
		result.put("function", key);
		result.put("endpoint", Long.valueOf(2));
		result.put("cluster", Long.valueOf(cluster));
		result.put("attribute", Long.valueOf(attribute));
		result.put("type", type);
		result.put("writable", Boolean.FALSE);
		return result;
	}

	private static TLObject function(TLObject device, String key) {
		for (Object function : (Collection<?>) device.tValueByName("functions")) {
			if (key.equals(((TLObject) function).tValueByName("function"))) {
				return (TLObject) function;
			}
		}
		throw new AssertionError("No function " + key);
	}

	private static String label(Locale locale, String key) {
		return inLocale(locale, () -> ZigbeeStatus.functionLabel(key));
	}

	private static String label(Locale locale, TLObject function) {
		return inLocale(locale, () -> ZigbeeStatus.functionLabel(function));
	}

	private static Object inLocale(Locale locale, TLObject obj, String attribute) {
		return inLocale(locale, () -> obj.tValueByName(attribute));
	}

	private static <T> T inLocale(Locale locale, java.util.function.Supplier<T> value) {
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

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeTab.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
