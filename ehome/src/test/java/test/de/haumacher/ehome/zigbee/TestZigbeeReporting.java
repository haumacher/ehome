/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.TLConstraints;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;
import de.haumacher.ehome.zigbee.I18NConstants;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeExport;
import de.haumacher.ehome.zigbee.ZigbeeFunctionTable;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeReporting;

/**
 * The reporting overrides of Zigbee functions (haumacher/ehome #64, homecontrol
 * <code>spec/zigbee.md</code> "Reporting overrides") on the motion sensor of
 * <code>src/test/fixtures/zigbee/motion-sensor.export.json</code>: the installer's override written
 * into the image (only the members set, nothing without one, so the image of an untouched project
 * stays as it was); an override the controller refuses is refused when saved, by the constraint of
 * the function's attributes with the controller's reason (also at the commit); the export's effective reporting mirrored into
 * the read-only attributes without touching the override, and what the tab shows from it or from the
 * defaults.
 *
 * <p>
 * The conformance cases of the controller (<code>refuse-zigbee-reporting.json</code>,
 * <code>accept-zigbee-reporting.json</code>) run in
 * {@link test.de.haumacher.ehome.runtime.TestImageConformance}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestZigbeeReporting extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static final String FIXTURE = "src/test/fixtures/zigbee/motion-sensor.export.json";

	public void testOverrides() throws Exception {
		String text = Files.readString(Path.of(FIXTURE), StandardCharsets.UTF_8);
		TLObject project = project("Zigbee reporting", "P-TEST-64-REPORTING");
		try {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
				tx.commit();
			}
			List<TLObject> functions = objects(ZigbeeImport.devices(project).get(0), "functions");
			TLObject temperature = functions.get(0);
			TLObject illuminance = functions.get(1);
			TLObject occupancy = functions.get(2);
			TLObject battery = functions.get(3);
			TLObject action = functions.get(4);
			assertEquals("temperature", temperature.tValueByName("function"));
			assertEquals("action", action.tValueByName("function"));

			// An export without reporting (an older controller): nothing reported, the tab shows the defaults.
			assertNull(temperature.tValueByName("reportedMin"));
			assertNull(temperature.tValueByName("reportedChange"));
			assertEquals("Reports: every 30..600 s, change 0.1 °C (default)", summary(Locale.ENGLISH, temperature));
			assertEquals("Berichte: alle 30..600 s, Änderung 0,1 °C (Standard)", summary(Locale.GERMAN, temperature));
			assertEquals("Berichte: alle 10..600 s, Änderung nach Standard des Controllers (Standard)",
				summary(Locale.GERMAN, illuminance));
			assertEquals("Reports: every 0..600 s (default)", summary(Locale.ENGLISH, occupancy));
			assertEquals("Reports: every 3600..21600 s, change 1 % (default)", summary(Locale.ENGLISH, battery));
			assertEquals("Reports: not configured (events or notifications of the device)", summary(Locale.ENGLISH, action));

			TLObject occupancyDp;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				occupancyDp = ZigbeeBindings.createDatapoint(occupancy);
				ZigbeeBindings.createDatapoint(temperature);
				tx.commit();
			}
			RuntimeImage untouched = RuntimeImage.build(project, 1, NOW);
			assertTrue(untouched.validate().toString(), untouched.validate().isValid());
			assertFalse(untouched.getText(), untouched.getText().contains("\"reporting\""));

			// The installer's override: exactly the members set.
			update(temperature, Map.of(ZigbeeReporting.MIN, Integer.valueOf(30), ZigbeeReporting.MAX, Integer.valueOf(900),
				ZigbeeReporting.CHANGE, Double.valueOf(0.5)));
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			assertEquals(map("min", Long.valueOf(30), "max", Long.valueOf(900), "change", Double.valueOf(0.5)),
				zigbee(image, 1).get("reporting"));
			assertFalse("A function without an override writes no reporting.", zigbee(image, 0).containsKey("reporting"));
			update(temperature, map(ZigbeeReporting.MIN, null, ZigbeeReporting.CHANGE, null));
			assertEquals(map("max", Long.valueOf(900)), zigbee(RuntimeImage.build(project, 1, NOW), 1).get("reporting"));

			// Without the override the image is the one of the untouched project again.
			update(temperature, map(ZigbeeReporting.MAX, null));
			assertEquals(untouched.getText(), RuntimeImage.build(project, 1, NOW).getText());

			// An override the controller refuses cannot be stored: the model checks the constraints at the commit.
			try {
				update(occupancy, map(ZigbeeReporting.CHANGE, Double.valueOf(1)));
				fail("An override the controller refuses is stored.");
			} catch (TopLogicException ex) {
				assertNull(occupancy.tValueByName(ZigbeeReporting.CHANGE));
			}
			assertNotNull(occupancyDp);

			// Saved in the UI: the constraint of the attributes refuses with the controller's reason.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				occupancy.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(1));
				ResKey problem = problem(occupancy, ZigbeeReporting.CHANGE);
				assertEquals(I18NConstants.REPORTING_NO_CHANGE_DISCRETE__FUNCTION.fill("occupancy"), problem);
				assertSameText("the function occupancy has no reportable change (a discrete value reports every change)",
					problem);
				assertNull(problem(occupancy, ZigbeeReporting.MIN));

				temperature.tUpdateByName(ZigbeeReporting.MAX, Integer.valueOf(65535));
				assertNull(problem(temperature, ZigbeeReporting.MAX));
				temperature.tUpdateByName(ZigbeeReporting.MIN, Integer.valueOf(900));
				temperature.tUpdateByName(ZigbeeReporting.MAX, Integer.valueOf(30));
				assertEquals(I18NConstants.REPORTING_MAX_BELOW_MIN, problem(temperature, ZigbeeReporting.MAX));
				assertNull(problem(temperature, ZigbeeReporting.MIN));
				temperature.tUpdateByName(ZigbeeReporting.MAX, null);
				assertEquals("An absent max is the default 600.", I18NConstants.REPORTING_MIN_ABOVE_MAX,
					problem(temperature, ZigbeeReporting.MIN));
				temperature.tUpdateByName(ZigbeeReporting.MIN, Integer.valueOf(65535));
				assertEquals(I18NConstants.REPORTING_MIN_RANGE, problem(temperature, ZigbeeReporting.MIN));
				temperature.tUpdateByName(ZigbeeReporting.MIN, null);
				temperature.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(327.68));
				assertEquals(I18NConstants.REPORTING_CHANGE_TEMPERATURE, problem(temperature, ZigbeeReporting.CHANGE));
				temperature.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(-0.1));
				assertEquals("Refused, not clamped.", I18NConstants.REPORTING_CHANGE_TEMPERATURE,
					problem(temperature, ZigbeeReporting.CHANGE));
				battery.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(100.5));
				assertEquals(I18NConstants.REPORTING_CHANGE_PERCENT, problem(battery, ZigbeeReporting.CHANGE));
				illuminance.tUpdateByName(ZigbeeReporting.CHANGE, Double.valueOf(10));
				assertEquals(I18NConstants.REPORTING_NO_CHANGE_ILLUMINANCE, problem(illuminance, ZigbeeReporting.CHANGE));
				action.tUpdateByName(ZigbeeReporting.MAX, Integer.valueOf(600));
				assertEquals(I18NConstants.REPORTING_NONE_ACTION, problem(action, ZigbeeReporting.MAX));
				assertNull("Only the fields with a value show the refusal of the whole.", problem(action, ZigbeeReporting.MIN));
				tx.rollback();
			}

			// An export with the effective reporting: mirrored, the installer's override stays.
			update(temperature, map(ZigbeeReporting.MIN, Integer.valueOf(60)));
			Map<?, ?> export = (Map<?, ?>) Json.parse(text, 100);
			List<?> exported = (List<?>) ((Map<?, ?>) ((List<?>) export.get("devices")).get(0)).get("functions");
			put(exported.get(0), "reporting", map("min", Long.valueOf(60), "max", Long.valueOf(600), "change", Double.valueOf(0.1)));
			put(exported.get(2), "reporting", map("min", Long.valueOf(0), "max", Long.valueOf(0)));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.read(export));
				tx.commit();
			}
			assertEquals(Integer.valueOf(60), temperature.tValueByName("reportedMin"));
			assertEquals(Integer.valueOf(600), temperature.tValueByName("reportedMax"));
			assertEquals(Double.valueOf(0.1), temperature.tValueByName("reportedChange"));
			assertEquals(Integer.valueOf(60), temperature.tValueByName(ZigbeeReporting.MIN));
			assertNull(temperature.tValueByName(ZigbeeReporting.MAX));
			assertEquals("Berichte: alle 60..600 s, Änderung 0,1 °C (laut Controller)", summary(Locale.GERMAN, temperature));
			assertEquals("Reports: on a change only, at most every 0 s (as the controller reports)",
				summary(Locale.ENGLISH, occupancy));
			assertNull(occupancy.tValueByName("reportedChange"));
			assertNull(action.tValueByName("reportedMax"));

			// The same export again changes nothing; the older one empties the mirror, not the override.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				ZigbeeImport.importExport(project, ZigbeeExport.parse(text));
				tx.commit();
			}
			assertNull(temperature.tValueByName("reportedMin"));
			assertEquals(Integer.valueOf(60), temperature.tValueByName(ZigbeeReporting.MIN));
		} finally {
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * The English text of every refusal is the controller's (the doclet capitalizes its first letter).
	 */
	public void testTexts() {
		Object[][] cases = {
			{ "action", null, Long.valueOf(600), null },
			{ "multistate", null, Long.valueOf(600), null },
			{ "ias_zone", Long.valueOf(1), null, null },
			{ "temperature", Long.valueOf(65535), null, null },
			{ "temperature", null, Long.valueOf(65536), null },
			{ "temperature", Long.valueOf(900), Long.valueOf(30), null },
			{ "battery", Long.valueOf(30000), null, null },
			{ "on_off", null, null, Double.valueOf(1) },
			{ "illuminance", null, null, Double.valueOf(1) },
			{ "color_temperature", null, null, Double.valueOf(1) },
			{ "temperature", null, null, Double.valueOf(400) },
			{ "level", null, null, Double.valueOf(101) },
		};
		for (Object[] c : cases) {
			ZigbeeReporting.Refusal refusal = ZigbeeReporting.check(ZigbeeFunctionTable.byKey((String) c[0]), (Long) c[1],
				(Long) c[2], (Double) c[3]);
			assertNotNull(c[0].toString(), refusal);
			assertSameText(refusal.text(), refusal.reason());
		}
		ZigbeeFunctionTable.Info temperature = ZigbeeFunctionTable.byKey("temperature");
		assertNull(ZigbeeReporting.check(temperature, Long.valueOf(30), Long.valueOf(0), Double.valueOf(327.67)));
		assertNull("Rounded to the step as the controller does.",
			ZigbeeReporting.check(temperature, null, null, Double.valueOf(327.674)));
		assertNotNull(ZigbeeReporting.check(temperature, null, null, Double.valueOf(327.68)));
		assertNull(ZigbeeReporting.check(ZigbeeFunctionTable.byKey("level"), null, null, Double.valueOf(100)));
	}

	private static void assertSameText(String controller, ResKey reason) {
		String english = Resources.getInstance(Locale.ENGLISH).getString(reason);
		assertEquals(controller.substring(0, 1).toUpperCase(Locale.ROOT) + controller.substring(1), english);
	}

	private static String summary(Locale locale, TLObject function) {
		return inLocale(locale, () -> ZigbeeReporting.summary(function));
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

	private static Map<?, ?> zigbee(RuntimeImage image, int index) {
		return (Map<?, ?>) ((Map<?, ?>) ((List<?>) image.getJson().get("datapoints")).get(index)).get("zigbee");
	}

	@SuppressWarnings("unchecked")
	private static void put(Object object, String member, Object value) {
		((Map<String, Object>) object).put(member, value);
	}

	private static void update(TLObject obj, Map<String, Object> values) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			for (Map.Entry<String, Object> entry : values.entrySet()) {
				obj.tUpdateByName(entry.getKey(), entry.getValue());
			}
			tx.commit();
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

	private static List<TLObject> objects(TLObject owner, String reference) {
		List<TLObject> result = new ArrayList<>();
		for (Object element : (Iterable<?>) owner.tValueByName(reference)) {
			result.add((TLObject) element);
		}
		return result;
	}

	private static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int n = 0; n < keyValues.length; n += 2) {
			result.put((String) keyValues[n], keyValues[n + 1]);
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestZigbeeReporting.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
