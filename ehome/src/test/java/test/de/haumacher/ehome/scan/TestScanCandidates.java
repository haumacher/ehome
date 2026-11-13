/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.job.JobPhase;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.scan.BusScanner;
import de.haumacher.ehome.scan.CatalogMatch;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.scan.I18NConstants;
import de.haumacher.ehome.scan.InstallationBuilder;

/**
 * The catalog candidates of a device found on the bus when the catalog holds several editions of its
 * program (haumacher/ehome #161): the ETS 4 and the ETS 6 export of the test system
 * (<code>samples/KNX-Testsystem.knxproj</code>, <code>samples/KNX-Testsystem-ETS6.knxproj</code>, both
 * with the project ID P-0563) bring two editions of each program, differing in the hash part of their
 * IDs. A scan of a project matches a device the project already has with the edition that device
 * runs; without a device at the address the choice stays with the installer, and the summary of the
 * scan counts such a device as one to choose for, not as identified.
 *
 * <p>
 * And the steps of a scan, which the job status shows, so that its progress tells the work left.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestScanCandidates extends BasicTestCase {

	private static final int TASTSENSOR = 0x1332;

	public void testProjectEditionWins() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			importSample("samples/KNX-Testsystem.knxproj");
			List<TLObject> ets4 = SharedData.newProjects(before);
			assertEquals(1, ets4.size());
			List<TLObject> both = new ArrayList<>(instances("ehome.project:Project"));
			importSample("samples/KNX-Testsystem-ETS6.knxproj");
			List<TLObject> ets6 = SharedData.newProjects(both);
			assertEquals(1, ets6.size());

			// The Tastsensor 1.3.50 as a scan reads it: manufacturer 0008, application 10D7 version 11.
			FoundDevice found = new FoundDevice(TASTSENSOR, FoundDevice.Source.BUS, 0x0705, 0x08, null,
				HexFormat.of().parseHex("000810d711"), null, null, List.of(), List.of());

			// The catalog alone offers both editions: a choice.
			CatalogMatch.Match catalog = CatalogMatch.match(found);
			assertEquals(CatalogMatch.Kind.CHOICE, catalog.kind());
			assertTrue(catalog.candidates().stream().map(c -> c.program().getExtId()).distinct().count() >= 2);

			// Each project decides for the edition its device 1.3.50 runs.
			Program program4 = program(ets4.get(0));
			Program program6 = program(ets6.get(0));
			assertNotSame(program4, program6);
			assertUnique(CatalogMatch.match(ets4.get(0), found), program4);
			assertUnique(CatalogMatch.match(ets6.get(0), found), program6);

			// A device at another address of the project does not decide.
			FoundDevice elsewhere = new FoundDevice(0x1333, FoundDevice.Source.BUS, 0x0705, 0x08, null,
				HexFormat.of().parseHex("000810d711"), null, null, List.of(), List.of());
			assertEquals(CatalogMatch.Kind.CHOICE, CatalogMatch.match(ets4.get(0), elsewhere).kind());
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	public void testSummaryCountsChoices() {
		ResKey summary = I18NConstants.SCAN_DONE__GATEWAY_FOUND_IDENTIFIED_CHOICES_UNIDENTIFIED.fill("Controller c", 3, 1,
			2, 0);
		assertEquals("Suche über Controller c: 3 Geräte gefunden; der Katalog identifiziert 1 eindeutig, bei 2 ist unter "
			+ "ihren Kandidaten zu wählen, 0 sind nicht identifiziert.",
			Resources.getInstance(Locale.GERMAN).getString(summary));
	}

	public void testPhases() {
		assertEquals(List.of(BusScanner.PHASE_LINE, BusScanner.PHASE_DEVICES),
			BusScanner.phases(new BusScanner.Request(1, 3, false, false, false)));
		assertEquals(
			List.of(BusScanner.PHASE_GATEWAY, BusScanner.PHASE_LINE, BusScanner.PHASE_ROUTERS,
				BusScanner.PHASE_PROGRAMMING, BusScanner.PHASE_DEVICES),
			BusScanner.phases(new BusScanner.Request(1, 3, true, true, true)));
		assertEquals(List.of(BusScanner.PHASE_PROGRAMMING, BusScanner.PHASE_DEVICES),
			BusScanner.phases(new BusScanner.Request(null, 0, false, false, true)));
	}

	/**
	 * The scan reports its phases in the order it announces them, and the progress of each phase
	 * from its start, so that the bar never stands at the end while work is left.
	 */
	public void testScanReportsPhases() throws Exception {
		TestBusScan.FakeBus bus = new TestBusScan.FakeBus();
		List<String> events = new ArrayList<>();
		BusScanner.Request request = new BusScanner.Request(1, 3, true, true, false);
		BusScanner.scan(bus, request, new BusScanner.Listener() {
			@Override
			public void phase(String name) {
				events.add("phase " + name);
			}

			@Override
			public void message(ResKey text) {
				// Ignore.
			}

			@Override
			public void progress(int done, int total) {
				events.add(done + "/" + total);
			}
		});
		List<String> phases = events.stream().filter(e -> e.startsWith("phase ")).map(e -> e.substring(6)).toList();
		assertEquals(BusScanner.phases(request), phases);
		// Reading the devices found (actuator and Tastsensor; the router is read as the gateway) starts
		// at nothing done and ends with all done.
		int devices = events.indexOf("phase " + BusScanner.PHASE_DEVICES);
		assertEquals(List.of("0/2", "1/2", "2/2"), events.subList(devices + 1, events.size()));
	}

	public void testPhaseLabels() {
		for (String name : BusScanner.phases(new BusScanner.Request(1, 3, true, true, true))) {
			JobPhase phase = new JobPhase(name, label(name));
			String german = Resources.getInstance(Locale.GERMAN).getString(phase.label());
			String english = Resources.getInstance(Locale.ENGLISH).getString(phase.label());
			assertFalse(name, german.equals(english));
		}
	}

	private static ResKey label(String phase) {
		return switch (phase) {
			case BusScanner.PHASE_GATEWAY -> I18NConstants.PHASE_GATEWAY;
			case BusScanner.PHASE_LINE -> I18NConstants.PHASE_LINE;
			case BusScanner.PHASE_ROUTERS -> I18NConstants.PHASE_ROUTERS;
			case BusScanner.PHASE_PROGRAMMING -> I18NConstants.PHASE_PROGRAMMING;
			default -> I18NConstants.PHASE_DEVICES;
		};
	}

	private static Program program(TLObject project) {
		TLObject device = InstallationBuilder.device(project, TASTSENSOR);
		assertNotNull(device);
		Program program = DeviceConfiguration.program(device);
		assertNotNull(program);
		return program;
	}

	private static void assertUnique(CatalogMatch.Match match, Program program) {
		assertEquals(match.candidates().toString(), CatalogMatch.Kind.UNIQUE, match.kind());
		assertSame(program, match.candidates().get(0).program());
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestScanCandidates.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
