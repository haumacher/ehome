/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.delete;
import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.device;
import static test.de.haumacher.ehome.virtual.tooling.TestVirtualDownload.importProject;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.read;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.Operation;
import de.haumacher.ehome.virtual.tooling.trace.TraceOperations;

/**
 * ETS 6's own full download of the MDT actuator 1.3.5 of the ETS 6 export of the test system
 * (<code>samples/KNX-Testsystem-ETS6.knxproj</code> of the app), recorded by the virtual device
 * (<code>src/test/fixtures/ets-traces/ets6-download-1.3.5.txt</code>), against E-Home's plan of the
 * same download: the memory E-Home writes is the memory ETS 6 writes, byte for byte.
 *
 * <p>
 * The app's <code>TestEts6TestsystemProbe</code> compares the ETS 6 export with the ETS 4 export of
 * the same installation; this comparison needs the trace format of the virtual device and therefore
 * lives in the development tooling.
 * </p>
 *
 * <p>
 * About 5 s: the module has a database of its own, and the ETS 6 export brings new editions of the
 * three programs of the test system, which this test imports first.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestEts6Download extends BasicTestCase {

	private static final String ETS6 = "../ehome/samples/KNX-Testsystem-ETS6.knxproj";

	private static final String TRACE = "src/test/fixtures/ets-traces/ets6-download-1.3.5.txt";

	public void testAsEts6() throws Exception {
		// The mask versions of the export's own master data, as in an empty database.
		SharedData.resetMaskVersions(Files.readAllBytes(Path.of(ETS6)));
		TLObject project = importProject(ETS6);
		try {
			DownloadPlan plan = DownloadPlanner.plan(device(project, 0x1305), DownloadPlan.Kind.FULL);
			assertEquals(List.of(), plan.getProblems());

			List<Operation> ets = TraceOperations.operations(read(new File(TRACE)).entries());
			Map<Integer, Integer> theirs = written(ets);
			Map<Integer, Integer> mine = written(plan.getOperations());
			assertEquals(626, theirs.size());
			assertEquals(theirs, mine);
		} finally {
			delete(project);
		}
	}

	/**
	 * The bytes the operations write into the memory, by their address.
	 */
	private static Map<Integer, Integer> written(List<Operation> operations) {
		Map<Integer, Integer> result = new TreeMap<>();
		for (Operation operation : operations) {
			if (operation instanceof Operation.WriteMemory write) {
				for (int n = 0; n < write.data().length; n++) {
					result.put(write.address() + n, write.data()[n] & 0xFF);
				}
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestEts6Download.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
