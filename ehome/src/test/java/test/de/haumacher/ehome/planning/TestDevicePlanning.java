/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.planning;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.planning.I18NConstants;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.scan.CatalogMatch;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.scan.InstallationBuilder;

/**
 * Planning devices in the app (haumacher/ehome #104), on the test system
 * (<code>samples/KNX-Testsystem.knxproj</code>, line 1.3 with the router 1.3.1, the actuator 1.3.5
 * and the Tastsensor 1.3.50): a device planned from the catalog, the refusal of an address in use,
 * the duplicate of a device with its settings, the bus scan meeting a planned device, and the export
 * of planned devices to .knxproj.
 */
@SuppressWarnings("javadoc")
public class TestDevicePlanning extends BasicTestCase {

	private static final int TASTSENSOR = 50;

	private static final int ACTUATOR = 5;

	public void testPlanning() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject project = importTestsystem();
		try {
			TLObject line = line(project);
			TLObject tastsensor = device(line, TASTSENSOR);
			TLObject variant = (TLObject) tastsensor.tValueByName("type");

			// The catalog offers the product, with its programs, the newest first.
			assertTrue(DevicePlanning.variants().contains(variant));
			assertTrue(DevicePlanning.variantLabel(variant), DevicePlanning.variantLabel(variant).contains("5133 00"));
			List<TLObject> programs = DevicePlanning.programs(variant);
			assertTrue(programs.contains(tastsensor.tValueByName("program")));
			assertSame(programs.get(0), DevicePlanning.defaultProgram(variant));

			// A second program of the product with the same name and version (as ETS exports of the same
			// device do, differing only in the hash in the ID): both labels carry the ID. Rolled back.
			try (Transaction tx = kb.beginTransaction()) {
				TLObject known = (TLObject) programs.get(0).tValueByName("program");
				TLObject product = programs.get(0).tContainer();
				TLObject twin = de.haumacher.ehome.model.operational.EhomeOperationalFactory.getInstance()
					.createProgram(known.tContainer());
				twin.tUpdateByName("name", known.tValueByName("name"));
				twin.tUpdateByName("applicationVersion", known.tValueByName("applicationVersion"));
				twin.tUpdateByName("extId", "M-0008_A-TEST-TWIN");
				List<Object> manufacturerPrograms = new ArrayList<>((Collection<?>) known.tContainer().tValueByName("programs"));
				manufacturerPrograms.add(twin);
				known.tContainer().tUpdateByName("programs", manufacturerPrograms);
				TLObject assignment = com.top_logic.util.model.ModelService.getInstance().getFactory().createObject(
					(com.top_logic.model.TLClass) com.top_logic.model.util.TLModelUtil.findType("ehome.catalog:ProgramAssignment"));
				assignment.tUpdateByName("program", twin);
				List<Object> productPrograms = new ArrayList<>((Collection<?>) product.tValueByName("programs"));
				productPrograms.add(assignment);
				product.tUpdateByName("programs", productPrograms);
				String plain = DevicePlanning.programLabel(programs.get(0));
				String other = DevicePlanning.programLabel(assignment);
				assertTrue(plain + " / " + other, plain.endsWith(" (" + ExtIds.extId(known) + ")"));
				assertTrue(other, other.endsWith(" (M-0008_A-TEST-TWIN)"));
				assertFalse(plain.equals(other));
				tx.rollback();
			}
			assertFalse(DevicePlanning.programLabel(programs.get(0)).endsWith(")"));

			// The next free address above the highest of the line; an address in use is refused.
			assertEquals(Integer.valueOf(51), DevicePlanning.nextFree(line));
			assertEquals(I18NConstants.ERROR_ADDRESS_TAKEN__ADDRESS_DEVICE.getKey(),
				DevicePlanning.addressVeto(line, Integer.valueOf(ACTUATOR)).plain().getKey());
			assertEquals(I18NConstants.ERROR_NUMBER_RANGE__NUMBER_MIN_MAX.getKey(),
				DevicePlanning.addressVeto(line, Integer.valueOf(256)).plain().getKey());
			assertNull(DevicePlanning.addressVeto(line, Long.valueOf(51)));
			try (Transaction tx = kb.beginTransaction()) {
				DevicePlanning.create(line, variant, null, Integer.valueOf(ACTUATOR), null, null);
				fail("An address in use must be refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_ADDRESS_TAKEN__ADDRESS_DEVICE.getKey(), ex.getErrorKey().plain().getKey());
			}

			// A planned device: variant, program, address, nothing else.
			TLObject planned;
			try (Transaction tx = kb.beginTransaction()) {
				planned = DevicePlanning.create(line, variant, null, Long.valueOf(51), "Taster Flur", null);
				tx.commit();
			}
			assertSame(line, planned.tContainer());
			assertSame(variant, planned.tValueByName("type"));
			assertSame(programs.get(0), planned.tValueByName("program"));
			assertEquals(Integer.valueOf(51), planned.tValueByName("address"));
			assertEquals("Taster Flur", planned.tValueByName("title"));
			assertEquals(0, list(planned, "settings").size());
			assertEquals(0, list(planned, "connectors").size());
			assertEquals(0, ((Collection<?>) planned.tValueByName("loaded")).size());
			assertNotNull(planned.tValueByName("lastModified"));
			String extId = ExtIds.extId(planned);
			assertNotNull(extId);
			assertTrue(extId, extId.startsWith(ExtIds.extId(project) + "-0_DI-"));
			assertEquals(Integer.valueOf(52), DevicePlanning.nextFree(line));

			// A duplicate of the Tastsensor with a changed value: the settings, the next free address,
			// no connections, nothing loaded.
			try (Transaction tx = kb.beginTransaction()) {
				changeValue(tastsensor);
				tx.commit();
			}
			List<TLObject> settings = list(tastsensor, "settings");
			assertFalse(settings.isEmpty());
			assertFalse(list(tastsensor, "connectors").isEmpty());
			assertNull(DevicePlanning.duplicateVeto(tastsensor));
			TLObject copy;
			try (Transaction tx = kb.beginTransaction()) {
				copy = DevicePlanning.duplicate(tastsensor);
				tx.commit();
			}
			assertSame(line, copy.tContainer());
			assertEquals(Integer.valueOf(52), copy.tValueByName("address"));
			assertSame(variant, copy.tValueByName("type"));
			assertSame(tastsensor.tValueByName("program"), copy.tValueByName("program"));
			assertEquals(0, list(copy, "connectors").size());
			assertEquals(0, ((Collection<?>) copy.tValueByName("loaded")).size());
			assertNotNull(ExtIds.extId(copy));
			assertEquals(values(tastsensor), values(copy));
			for (TLObject setting : list(copy, "settings")) {
				assertFalse(settings.contains(setting));
			}
			// The original keeps its settings.
			assertEquals(settings, list(tastsensor, "settings"));

			// The bus scan meeting the planned device: the same program updates it, another one is a
			// conflict.
			FoundDevice sameProgram = found(0x1333, "000810D711");
			CatalogMatch.Candidate same = candidate(CatalogMatch.match(sameProgram),
				DeviceConfiguration.program(planned).getExtId());
			FoundDevice otherProgram = found(0x1333, "0083000C21");
			CatalogMatch.Candidate other = candidate(CatalogMatch.match(otherProgram), "M-0083_A-000C-21-83B5");
			try (Transaction tx = kb.beginTransaction()) {
				InstallationBuilder.Outcome updated = InstallationBuilder.apply(project, sameProgram, same);
				assertEquals(InstallationBuilder.Action.UPDATED, updated.action());
				assertSame(planned, updated.device());
				InstallationBuilder.Outcome conflict = InstallationBuilder.apply(project, otherProgram, other);
				assertEquals(InstallationBuilder.Action.CONFLICT, conflict.action());
				assertSame(planned, conflict.device());
				tx.commit();
			}
			assertSame(programs.get(0), planned.tValueByName("program"));
		} finally {
			delete(project);
		}
	}

	/**
	 * Planned devices are exported to .knxproj like any device: the import of the export has them
	 * with their product, program, address, external ID and settings.
	 */
	public void testExport() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject project = importTestsystem();
		File export = new File("target/roundtrip/planned-devices.knxproj");
		export.getParentFile().mkdirs();
		Map<String, String> plannedValues;
		Map<String, String> copyValues;
		String plannedId;
		String copyId;
		String variantId;
		String programId;
		try {
			TLObject line = line(project);
			TLObject tastsensor = device(line, TASTSENSOR);
			TLObject planned;
			TLObject copy;
			try (Transaction tx = kb.beginTransaction()) {
				planned = DevicePlanning.create(line, (TLObject) tastsensor.tValueByName("type"),
					(TLObject) tastsensor.tValueByName("program"), Integer.valueOf(51), null, null);
				changeValue(planned);
				copy = DevicePlanning.duplicate(planned);
				tx.commit();
			}
			plannedValues = values(planned);
			copyValues = values(copy);
			assertFalse(plannedValues.isEmpty());
			assertEquals(plannedValues, copyValues);
			plannedId = ExtIds.extId(planned);
			copyId = ExtIds.extId(copy);
			variantId = ExtIds.extId((TLObject) planned.tValueByName("type"));
			programId = ExtIds.extId((TLObject) planned.tValueByName("program"));
			try (OutputStream out = new FileOutputStream(export)) {
				KnxprojExporter.export(project, out);
			}
		} finally {
			delete(project);
		}

		TLObject reimported = importProject(export.getPath());
		try {
			TLObject line = line(reimported);
			TLObject planned = device(line, 51);
			TLObject copy = device(line, 52);
			assertEquals(plannedId, ExtIds.extId(planned));
			assertEquals(copyId, ExtIds.extId(copy));
			for (TLObject device : List.of(planned, copy)) {
				assertEquals(variantId, ExtIds.extId((TLObject) device.tValueByName("type")));
				assertEquals(programId, ExtIds.extId((TLObject) device.tValueByName("program")));
				assertEquals(0, list(device, "connectors").size());
			}
			assertEquals(plannedValues, values(planned));
			assertEquals(copyValues, values(copy));
		} finally {
			delete(reimported);
		}
	}

	/**
	 * Sets a parameter in effect of the given device to another entry of its enumeration.
	 */
	private static void changeValue(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		for (Map.Entry<Parameter, ParameterView> active : configuration.getActiveViews().entrySet()) {
			if (active.getKey().getType() instanceof EnumerationType type
				&& DeviceConfiguration.isEditable(active.getValue())) {
				String current = configuration.value(active.getValue());
				for (EnumerationValue entry : type.getValues()) {
					if (!String.valueOf(entry.getValue()).equals(current)) {
						assertNotNull(DeviceSettings.setValue(device, active.getValue(), entry));
						return;
					}
				}
			}
		}
		fail("No parameter to change.");
	}

	/**
	 * The values of the settings of the given device in the notation of ETS, by the ID of their
	 * parameter placement.
	 */
	private static Map<String, String> values(TLObject device) {
		Map<String, String> result = new java.util.TreeMap<>();
		for (TLObject setting : list(device, "settings")) {
			result.put(ExtIds.extId((TLObject) setting.tValueByName("parameter")), ParameterValues.encode(setting));
		}
		return result;
	}

	private static FoundDevice found(int address, String programVersion) {
		byte[] version = HexFormat.of().parseHex(programVersion);
		return new FoundDevice(address, FoundDevice.Source.BUS, 0x0705,
			Integer.valueOf((version[0] & 0xFF) << 8 | (version[1] & 0xFF)), null, version, null, null, List.of(),
			List.of());
	}

	private static CatalogMatch.Candidate candidate(CatalogMatch.Match match, String program) {
		for (CatalogMatch.Candidate candidate : match.candidates()) {
			if (program.equals(candidate.program().getExtId())) {
				return candidate;
			}
		}
		fail("No candidate " + program + " among " + match.candidates().stream().map(CatalogMatch.Candidate::label).toList());
		return null;
	}

	private static TLObject importTestsystem() throws Exception {
		return importProject("samples/KNX-Testsystem.knxproj");
	}

	private static TLObject importProject(String file) throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(file);
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		return projects.get(0);
	}

	private static void delete(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			if (project.tValid()) {
				project.tDelete();
			}
			tx.commit();
		}
	}

	/**
	 * The line 1.3 of the test system.
	 */
	private static TLObject line(TLObject project) {
		for (TLObject area : list(project, "areas")) {
			if (Integer.valueOf(1).equals(area.tValueByName("address"))) {
				for (TLObject line : list(area, "lines")) {
					if (Integer.valueOf(3).equals(line.tValueByName("address"))) {
						return line;
					}
				}
			}
		}
		fail("No line 1.3.");
		return null;
	}

	private static TLObject device(TLObject line, int number) {
		for (TLObject device : list(line, "devices")) {
			if (Integer.valueOf(number).equals(device.tValueByName("address"))) {
				return device;
			}
		}
		fail("No device " + number + " in the line.");
		return null;
	}

	private static List<TLObject> list(TLObject obj, String reference) {
		return ExtIds.list(obj, reference);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDevicePlanning.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
