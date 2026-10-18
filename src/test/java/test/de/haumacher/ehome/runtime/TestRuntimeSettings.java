/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.createAddress;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.update;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.DatapointSettings;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.I18NConstants;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeFunctions;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The runtime settings of datapoints and of a project in the runtime image (haumacher/ehome #45), on
 * the KNX test system: overrides of the derived flags, readable, unit, the checks of the history, time
 * zone and host name, the project's settings, a time master on an actuator's address, and refusals
 * shown against the project element they stem from.
 */
@SuppressWarnings("javadoc")
public class TestRuntimeSettings extends BasicTestCase {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	public void testSettings() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject channelA = address(project, "0/0/34");
			TLObject key1 = address(project, "0/0/1");
			TLObject scene2 = address(project, "0/0/20");

			// Derived: the actuator input is writable, the key is neither writable nor read on start.
			assertEquals(Boolean.TRUE, derived(channelA, "effectiveWritable"));
			assertEquals(Boolean.FALSE, derived(channelA, "effectiveReadOnStart"));
			assertEquals(I18NConstants.FLAGS_DERIVED, DatapointSettings.flagsSource(channelA));
			assertNotNull(derived(channelA, "runtimeFlagsSource"));
			Map<?, ?> a = datapoint(project, "0/0/34");
			assertEquals(Boolean.TRUE, a.get("writable"));
			assertEquals(Map.of("dpt", "1.001", "send", "0/0/34"), a.get("knx"));

			// Overridden: channel A is no longer commanded but read on start.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				channelA.tUpdateByName("runtimeWritable", Boolean.FALSE);
				channelA.tUpdateByName("runtimeReadOnStart", Boolean.TRUE);
				key1.tUpdateByName("runtimeReadable", Boolean.TRUE);
				scene2.tUpdateByName("runtimeUnit", "Stufe");
				tx.commit();
			}
			assertEquals(Boolean.FALSE, derived(channelA, "effectiveWritable"));
			assertEquals(Boolean.TRUE, derived(channelA, "effectiveReadOnStart"));
			assertEquals(I18NConstants.FLAGS_SET, DatapointSettings.flagsSource(channelA));
			assertEquals("Stufe", derived(scene2, "effectiveUnit"));
			a = datapoint(project, "0/0/34");
			assertNull(a.get("writable"));
			assertNull(a.get("transmit"));
			assertEquals(Boolean.TRUE, a.get("readOnStart"));
			assertEquals(Map.of("dpt", "1.001", "listen", List.of("0/0/34")), a.get("knx"));
			// Readable: the controller answers reads, so it sends on the address.
			Map<?, ?> k = datapoint(project, "0/0/1");
			assertEquals(Boolean.TRUE, k.get("readable"));
			assertNull(k.get("writable"));
			assertEquals(Map.of("dpt", "1.000", "send", "0/0/1"), k.get("knx"));
			assertEquals("Stufe", datapoint(project, "0/0/20").get("unit"));

			// Back to the derivation.
			update(channelA, "runtimeWritable", null);
			assertEquals(I18NConstants.FLAGS_READ_ON_START_SET, DatapointSettings.flagsSource(channelA));
			assertEquals(Boolean.TRUE, datapoint(project, "0/0/34").get("writable"));

			// A history without spacing is refused at saving; with both, it is in the image.
			try {
				update(scene2, "historyRetention", Integer.valueOf(3600));
				fail("A history without spacing must not be stored.");
			} catch (TopLogicException ex) {
				// Expected: the constraint of the history.
			}
			assertNull(scene2.tValueByName("historyRetention"));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				scene2.tUpdateByName("historyRetention", Integer.valueOf(3600));
				scene2.tUpdateByName("historySpacing", Integer.valueOf(1));
				assertNotNull(DatapointSettings.historyProblem(scene2));
				scene2.tUpdateByName("historySpacing", Integer.valueOf(60));
				assertNull(DatapointSettings.historyProblem(scene2));
				tx.commit();
			}
			assertEquals(Map.of("retention", Long.valueOf(3600), "spacing", Long.valueOf(60)),
				datapoint(project, "0/0/20").get("history"));

			// A budget the rings do not fit in: refused by the controller, shown against the project.
			update(project, "historyBudget", Integer.valueOf(100));
			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			ImageReport report = image.validate();
			assertFalse(report.isValid());
			assertEquals(1, report.getRefusals().size());
			assertEquals("history", report.getRefusals().get(0).path());
			assertSame(project, image.elementAt(report.getRefusals().get(0).path()));
			update(project, "historyBudget", Integer.valueOf(4096));

			// The project's settings, as in the specification's example.
			assertNull(RuntimeFunctions.timeZoneProblem("CET-1CEST,M3.5.0,M10.5.0/3"));
			assertNotNull(RuntimeFunctions.timeZoneProblem("Europe/Berlin"));
			assertNull(RuntimeFunctions.hostNameProblem("pool.ntp.org"));
			assertNotNull(RuntimeFunctions.hostNameProblem("pool..ntp.org"));
			assertNull(RuntimeFunctions.identifierProblem("channel-a"));
			assertNotNull(RuntimeFunctions.identifierProblem("Kanal A"));
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("timeZone", "CET-1CEST,M3.5.0,M10.5.0/3");
				project.tUpdateByName("latitude", Double.valueOf(48.137154));
				project.tUpdateByName("longitude", Double.valueOf(11.576124));
				project.tUpdateByName("elevation", Integer.valueOf(520));
				project.tUpdateByName("ntpServer", "pool.ntp.org");
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			Map<?, ?> example = (Map<?, ?>) Json.parse(Files.readString(new File(TestImageConformance.IMAGES, "example.json").toPath(),
				StandardCharsets.UTF_8), 100);
			assertEquals(example.get("settings"), image.getJson().get("settings"));
			// The only note: the room temperature has no datapoint type.
			assertEquals(1, image.getNotes().size());
			update(project, "longitude", null);
			image = RuntimeImage.build(project, 1, NOW);
			assertNull(((Map<?, ?>) image.getJson().get("settings")).get("location"));
			assertEquals(2, image.getNotes().size());

			// A time master on the address of an actuator input: the address is the time master's, not
			// a datapoint the controller would send on, and the type that does not fit is named.
			TLObject date = createAddress(project, "0/0/61", "Datum", "DPST-11-1");
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("timeMasterTime", address(project, "0/0/40"));
				project.tUpdateByName("timeMasterDate", date);
				project.tUpdateByName("timeMasterAtMidnight", Boolean.FALSE);
				project.tUpdateByName("timeMasterInterval", Integer.valueOf(3600));
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			report = image.validate();
			assertTrue(report.toString(), report.isValid());
			List<?> datapoints = (List<?>) image.getJson().get("datapoints");
			assertNull(TestRuntimeImage.datapoint(datapoints, "0/0/40"));
			assertNull(TestRuntimeImage.datapoint(datapoints, "0/0/61"));
			assertEquals(Map.of("timeMaster", Map.of("time", "0/0/40", "date", "0/0/61", "interval", Long.valueOf(3600),
				"atMidnight", Boolean.FALSE, "onSynchronized", Boolean.TRUE)), image.getJson().get("knx"));
			assertEquals(3, image.getNotes().size());
			assertTrue(image.getNotes().get(2), image.getNotes().get(2).contains("0/0/40"));
			assertTrue(image.getNotes().get(2), image.getNotes().get(2).contains("10.001"));

			// A function binding the time address names the controller's datapoint of it; one whose
			// role is bound to an untyped address is refused against the function.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				TLObject clock = TestFunctionProposal.create("ehome.runtime:Function");
				clock.tUpdateByName("name", "Uhr");
				clock.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
				clock.tUpdateByName("value", date);
				TLObject temperature = TestFunctionProposal.create("ehome.runtime:Function");
				temperature.tUpdateByName("name", "Temperatur");
				temperature.tUpdateByName("kind", FunctionProposal.classifier("sensor"));
				temperature.tUpdateByName("value", address(project, "0/0/28"));
				project.tUpdateByName("functions", List.of(clock, temperature));
				tx.commit();
			}
			image = RuntimeImage.build(project, 1, NOW);
			List<?> functions = (List<?>) image.getJson().get("functions");
			assertEquals(Map.of("value", "knx.date"), ((Map<?, ?>) functions.get(0)).get("datapoints"));
			assertEquals("uhr", ((Map<?, ?>) functions.get(0)).get("id"));
			report = image.validate();
			assertFalse(report.isValid());
			ImageReport.Refusal refusal = report.getRefusals().get(report.getRefusals().size() - 1);
			assertEquals("functions[1].datapoints.value", refusal.path());
			assertTrue(refusal.reason(), refusal.reason().contains("0/0/28"));
			assertSame(FunctionProposal.functions(project).get(1), image.elementAt(refusal.path()));
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static Map<?, ?> datapoint(TLObject project, String id) {
		return TestRuntimeImage.datapoint((List<?>) RuntimeImage.build(project, 1, NOW).getJson().get("datapoints"), id);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRuntimeSettings.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
