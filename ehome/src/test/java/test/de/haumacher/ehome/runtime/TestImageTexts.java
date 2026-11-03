/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.apply;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.createAddress;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.misc.TypedConfigUtil;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.form.TLObjectOverlay;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.annotate.TLConstraints;
import com.top_logic.model.annotate.util.ConstraintCheck;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.ProgramLanguages;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.runtime.ExportRuntimeImageAction;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The texts of the runtime image on the demo project (<code>samples/EHomeDemo.knxproj</code>): the
 * labels of the functions as maps of their languages (haumacher/ehome #51), the summary of the export
 * dialog (#52), and the check of the time master's addresses in the form (#53).
 */
@SuppressWarnings("javadoc")
public class TestImageTexts extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	/**
	 * A proposed function has a label of each language of its device's program; a name the user gave
	 * in two languages is exported in both, a long one cut to 63 bytes, and the image is valid.
	 */
	public void testLabels() throws Exception {
		withDemo(project -> {
			apply(project);
			RuntimeImage image = valid(project);
			List<?> functions = (List<?>) image.getJson().get("functions");
			assertEquals(6, functions.size());
			for (int n = 0; n < functions.size(); n++) {
				TLObject function = image.getFunctionSources().get(n);
				Object label = ((Map<?, ?>) functions.get(n)).get("label");
				assertTrue(String.valueOf(label), label instanceof Map<?, ?>);
				assertEquals(ProgramLanguages.languages(DeviceConfiguration.program((TLObject) function.tValueByName("device"))),
					new TreeSet<>(((Map<?, ?>) label).keySet()));
			}

			TLObject function = image.getFunctionSources().get(0);
			String longText = "Küche ".repeat(20);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				function.tUpdateByName("name", FunctionProposal.text(Map.of("de", longText, "en", "Kitchen light")));
				tx.commit();
			}
			image = valid(project);
			Map<?, ?> label = (Map<?, ?>) ((Map<?, ?>) ((List<?>) image.getJson().get("functions")).get(0)).get("label");
			assertEquals(List.of("de", "en"), List.copyOf(label.keySet()));
			assertEquals("Kitchen light", label.get("en"));
			assertTrue(longText.startsWith((String) label.get("de")));
			assertTrue(((String) label.get("de")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 63);
			assertTrue(image.getText(), image.getText().contains("\"label\":{\"de\":\"Küche"));

			// A function the user created is named in every application language.
			ResKey created = FunctionProposal.text(ResKey.text("Neu"));
			Map<String, String> expected = new TreeMap<>(Map.of("de", "Neu", "en", "Neu"));
			assertEquals(expected, new TreeMap<>(((ResKey.LiteralKey) created).getTranslations().entrySet().stream()
				.collect(java.util.stream.Collectors.toMap(e -> e.getKey().getLanguage(), Map.Entry::getValue))));
		});
	}

	/**
	 * The export dialog counts the datapoints, locations, functions and settings and names the time
	 * master's addresses.
	 */
	public void testSummary() throws Exception {
		withDemo(project -> {
			Map<String, Object> result = ExportRuntimeImageAction.export(project, NOW);
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			int datapoints = ((List<?>) image.getJson().get("datapoints")).size();
			int locations = ((List<?>) image.getJson().get("locations")).size();
			assertEquals("The runtime image is valid: " + datapoints + " datapoints, " + locations
				+ " locations, 0 functions, 0 settings, " + number(image.getBytes().length) + " bytes. The image has no time master.",
				english(result.get("summary")));

			apply(project);
			TLObject time = createAddress(project, "0/0/60", "Uhrzeit", "DPST-10-1");
			TLObject date = createAddress(project, "0/0/61", "Datum", "DPST-11-1");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("timeMasterTime", time);
				project.tUpdateByName("timeMasterDate", date);
				project.tUpdateByName("timeZone", "CET-1CEST,M3.5.0,M10.5.0/3");
				project.tUpdateByName("ntpServer", "pool.ntp.org");
				project.tUpdateByName("historyBudget", Integer.valueOf(16384));
				tx.commit();
			}
			result = ExportRuntimeImageAction.export(project, NOW);
			image = RuntimeImage.build(project, 1, NOW);
			assertNotNull(result.get("file"));
			assertEquals("The runtime image is valid: " + datapoints + " datapoints, " + locations
				+ " locations, 6 functions, 3 settings, " + number(image.getBytes().length)
				+ " bytes. The time master sends on 0/0/60, 0/0/61.", english(result.get("summary")));
		});
	}

	/**
	 * An address of the wrong datapoint type is refused for the time master when it is set in the
	 * form, with the reason; the right type is taken, and a stored address is not refused again (the
	 * export notes it).
	 */
	public void testTimeMasterCheck() throws Exception {
		withDemo(project -> {
			TLObject time = createAddress(project, "0/0/60", "Uhrzeit", "DPST-10-1");
			TLObject socket = address(project, "0/0/1");
			TLStructuredTypePart attribute = project.tType().getPart("timeMasterTime");

			TLObjectOverlay form = new TLObjectOverlay(project);
			form.tUpdate(attribute, socket);
			String problem = english(check(form, attribute));
			assertNotNull(problem);
			assertTrue(problem, problem.contains("10.001"));
			assertTrue(problem, problem.contains("1.001"));

			form.tUpdate(attribute, time);
			assertNull(check(form, attribute));
			form.tUpdate(attribute, null);
			assertNull(check(form, attribute));

			// The date and the date and time ask for their own types.
			assertNotNull(check(overlay(project, "timeMasterDate", time), project.tType().getPart("timeMasterDate")));
			assertNotNull(check(overlay(project, "timeMasterDateTime", time), project.tType().getPart("timeMasterDateTime")));

			// Stored outside a form: the stored project is not refused, so other changes still store;
			// the export names the type in its notes.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("timeMasterTime", socket);
				tx.commit();
			}
			assertNull(check(project, attribute));
			assertNull(check(new TLObjectOverlay(project), attribute));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("ntpServer", "pool.ntp.org");
				tx.commit();
			}
			List<String> notes = RuntimeImage.build(project, 1, NOW).getNotes();
			assertTrue(notes.toString(), notes.stream().anyMatch(n -> n.contains("0/0/1") && n.contains("10.001")));
		});
	}

	private static TLObjectOverlay overlay(TLObject project, String attribute, TLObject value) {
		TLObjectOverlay result = new TLObjectOverlay(project);
		result.tUpdate(project.tType().getPart(attribute), value);
		return result;
	}

	/**
	 * The first problem the constraints of the attribute of the model find, <code>null</code> for
	 * none.
	 */
	private static ResKey check(TLObject object, TLStructuredTypePart attribute) {
		TLConstraints constraints = attribute.getAnnotation(TLConstraints.class);
		assertNotNull(constraints);
		for (PolymorphicConfiguration<? extends ConstraintCheck> config : constraints.getConstraints()) {
			ResKey problem = TypedConfigUtil.createInstance(config).check(object, attribute);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	private static String number(int value) {
		return java.text.NumberFormat.getIntegerInstance(java.util.Locale.ENGLISH).format(value);
	}

	private static String english(Object text) {
		if (text instanceof ResKey key) {
			return Resources.getInstance(java.util.Locale.ENGLISH).getString(key);
		}
		return (String) text;
	}

	private static RuntimeImage valid(TLObject project) {
		RuntimeImage image = RuntimeImage.build(project, 1, NOW);
		ImageReport report = image.validate();
		assertTrue(report.toString(), report.isValid());
		return image;
	}

	private interface ProjectTest {
		void run(TLObject project) throws Exception;
	}

	private static void withDemo(ProjectTest test) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			test.run(projects.get(0));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestImageTexts.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
