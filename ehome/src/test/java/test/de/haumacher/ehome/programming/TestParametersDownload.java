/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadRecord;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.programming.download.ProgrammingFunctions;

/**
 * Test of the plan of the download "Parameters only" (haumacher/ehome #195) on the devices of
 * <code>TestPartialSteps.knxproj</code> (see <code>make-fixtures.py</code>).
 *
 * <ul>
 * <li>The actuator 1.3.5, whose load procedure says which steps belong to which download: the plan
 * runs exactly the steps of the parameters (the allocation of its code segment, a step of the full
 * download only, left out; the delay of the parameters taken), the code segment not loaded; the full
 * download runs the others.</li>
 * <li>The Gira Tastsensor 1.3.50, whose steps apply to every download and whose program has no segment
 * of code alone: the plan of the full download after the checks of the program.</li>
 * <li>"Parameters only" is offered first for a device that holds the program of its last download.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestParametersDownload extends BasicTestCase {

	/** The actuator 1.3.5. */
	private static final String ACTUATOR = "P-0563-0_DI-3";

	/** The Tastsensor 1.3.50. */
	private static final String SENSOR = "P-0563-0_DI-2";

	public void testPlans() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestPartialSteps.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			checkSteps(device(project, ACTUATOR));
			checkWithoutCode(device(project, SENSOR));
			checkDefault(device(project, ACTUATOR));
			checkNoPartialDownload(device(project, ACTUATOR));
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * The steps of the parameters, exactly, against those of the full download.
	 */
	private static void checkSteps(TLObject device) {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		assertEquals(plan.dump(), List.of("#1 Connect", "#2 CompareProp", "#3 Unload LSM 1", "#4 Unload LSM 2",
			"#5 Unload LSM 3", "#6 Load LSM 1", "#7 AbsSegment LSM 1", "#8 TaskSegment LSM 1", "#9 LoadCompleted LSM 1",
			"#10 Load LSM 2", "#11 AbsSegment LSM 2", "#12 TaskSegment LSM 2", "#13 LoadCompleted LSM 2", "#14 Load LSM 3",
			"#15 AbsSegment LSM 3", "#16 AbsSegment LSM 3", "#17 AbsSegment LSM 3", "#18 AbsSegment LSM 3",
			"#19 TaskSegment LSM 3", "#20 LoadCompleted LSM 3", "#21 Delay", "#22 Restart", "#23 Disconnect"),
			steps(plan));
		assertEquals(List.of(0x0700, 0x0784, 0x4400, 0x44EC), segments(plan, 3));
		assertEquals(List.of(0x4700), plan.getUnloadedBlocks().stream().map(Block::address).toList());
		assertTrue(plan.dump(), plan.getOperations().stream().noneMatch(
			o -> o instanceof Operation.WriteMemory write && write.address() >= 0x4700 && write.address() < 0x4710));
		assertTrue(plan.dump(), plan.getOperations().stream().anyMatch(o -> o instanceof Operation.Delay));

		DownloadPlan full = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertEquals(full.dump(), List.of(), full.getProblems());
		assertEquals(List.of(0x0700, 0x0784, 0x4400, 0x44EC, 0x4700), segments(full, 3));
		assertEquals(List.of(), full.getUnloadedBlocks());
		assertTrue(full.dump(), full.getOperations().stream().noneMatch(o -> o instanceof Operation.Delay));
		assertTrue(full.dump(), full.getOperations().stream().noneMatch(
			o -> o instanceof Operation.CheckProgramVersion || o instanceof Operation.CheckLoadState));
	}

	/**
	 * Steps of every download and no segment of code alone: the full plan after the checks.
	 */
	private static void checkWithoutCode(TLObject device) {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
		assertEquals(plan.dump(), List.of(), plan.getProblems());
		DownloadPlan full = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		List<String> checks = new ArrayList<>();
		List<String> rest = new ArrayList<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.CheckProgramVersion || operation instanceof Operation.CheckLoadState) {
				checks.add(operation.describe());
			} else {
				rest.add(operation.describe());
			}
		}
		assertEquals(List.of("Check that object 3 property 13 holds the program version 00 08 10 D7 11",
			"Check that load state machine 1 is loaded", "Check that load state machine 2 is loaded",
			"Check that load state machine 3 is loaded"), checks);
		assertEquals(full.getOperations().stream().map(Operation::describe).toList(), rest);
		// The segment ETS does not write (SegFlags 0) stays the only one not loaded.
		assertEquals(List.of(0x454E), plan.getUnloadedBlocks().stream().map(Block::address).toList());
		assertEquals(List.of(0x454E), full.getUnloadedBlocks().stream().map(Block::address).toList());
	}

	/**
	 * "Parameters only" is offered first when the device holds the program of its last download.
	 */
	private static void checkDefault(TLObject device) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			device.tUpdateByName("loaded", Set.of());
			tx.commit();
		}
		assertFalse(ProgrammingFunctions.parametersOnlyByDefault(device));
		DownloadRecord.record(device, DownloadPlan.Kind.ADDRESS, 0);
		assertFalse("Only the address is loaded.", ProgrammingFunctions.parametersOnlyByDefault(device));
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
		DownloadRecord.record(device, plan.getKind(), plan.getRevision());
		assertTrue(ProgrammingFunctions.parametersOnlyByDefault(device));
		assertEquals(5, ((Collection<?>) device.tValueByName("loaded")).size());
	}

	/**
	 * A program marked for no partial download (ETS: <code>LegacyNoPartialDownload</code>) refuses
	 * "Parameters only" with its reason and is never offered it first; the full download stays.
	 */
	private static void checkNoPartialDownload(TLObject device) {
		de.haumacher.ehome.model.operational.Program program = DeviceConfiguration.program(device);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			program.setNoPartialDownload(true);
			tx.commit();
		}
		try {
			assertFalse(ProgrammingFunctions.parametersOnlyByDefault(device));
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.PARAMETERS);
			assertTrue(plan.dump(), plan.getProblems().contains(
				de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_PARTIAL_DOWNLOAD));
			DownloadPlan full = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertEquals(full.dump(), List.of(), full.getProblems());
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				program.setNoPartialDownload(false);
				tx.commit();
			}
		}
	}

	/**
	 * The steps of the procedure the plan performs, in their order.
	 */
	private static List<String> steps(DownloadPlan plan) {
		Set<String> result = new LinkedHashSet<>();
		for (Operation operation : plan.getOperations()) {
			if (!operation.step().isEmpty()) {
				result.add(operation.step());
			}
		}
		return new ArrayList<>(result);
	}

	/**
	 * The addresses of the absolute segments the plan allocates for the given load state machine.
	 */
	private static List<Integer> segments(DownloadPlan plan, int lsm) {
		List<Integer> result = new ArrayList<>();
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.LoadControl control && control.stateMachine() == lsm
				&& control.control()[0] == 3 && (control.control()[1] == 0 || control.control()[1] == 1)) {
				result.add((control.control()[2] & 0xFF) << 8 | control.control()[3] & 0xFF);
			}
		}
		return result;
	}

	private static TLObject device(TLObject project, String extId) {
		for (TLObject device : instances("ehome.physical:Device")) {
			if (extId.equals(device.tValueByName("extId"))) {
				TLObject root = device;
				while (root.tContainer() != null) {
					root = root.tContainer();
				}
				if (root == project) {
					return device;
				}
			}
		}
		throw new AssertionError("No device " + extId);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParametersDownload.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
