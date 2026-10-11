/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Segment;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.ParameterEncoding;
import de.haumacher.ehome.programming.download.DeviceComparison;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;

/**
 * Probe of the BCU 1 download (issue #13) composed with the rest: a download onto a mock becomes
 * the device's reference, a parameter edited afterwards changes exactly its bits, which the
 * compare against the mock reports and the next download writes, after which the compare is
 * identical and the change list empty; and the BCU 1 project survives the export round trip with
 * identical images.
 */
@SuppressWarnings("javadoc")
public class TestBcuImageProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestBcu1.knxproj");
		TLObject project = newProject(before);
		File export = new File("target/roundtrip/probe-bcu1.knxproj");
		export.getParentFile().mkdirs();
		TLObject reimported = null;
		try {
			TLObject device = devicesByAddress(project).get(1);
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertTrue(plan.getProblems().toString(), plan.isExecutable());

			// A delivered device holds the program's static EEPROM content (the procedure never writes
			// the last byte of it, so a blank mock would differ there). The first download onto it:
			// the mock then holds the image, the device its reference.
			MockDevice mock = new MockDevice().setDescriptor("0012");
			for (Segment segment : DeviceConfiguration.program(device).getSegments()) {
				if (segment instanceof CodeSegment code && code.getData() != null) {
					mock.setMemory(code.getAddress(), ParameterEncoding.toBytes(code.getData()));
				}
			}
			DownloadExecutor.Result result = DownloadService.download(device, plan, mock,
				new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
			assertTrue(result.failure(), result.success());
			assertEquals(0, DeviceChanges.sinceDownload(device).getCount());
			DeviceComparison.Result same = DeviceComparison.compare(DeviceConfiguration.program(device), plan.getImage(),
				plan.getUnloadedBlocks(), mock, (number, count, block) -> { /* Ignore. */ });
			for (DeviceComparison.BlockResult block : same.blocks()) {
				assertNull(block.failure());
				assertTrue(block.name() + ": " + block.ranges(), block.identical());
			}

			// A parameter in effect edited: exactly its bits differ from the mock.
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			ParameterView view = null;
			Object value = null;
			for (Map.Entry<Parameter, ParameterView> active : configuration.getActiveViews().entrySet()) {
				if (!DeviceConfiguration.isEditable(active.getValue())) {
					continue;
				}
				String current = configuration.value(active.getValue());
				if (active.getKey().getType() instanceof EnumerationType type) {
					for (EnumerationValue entry : type.getValues()) {
						if (!String.valueOf(entry.getValue()).equals(current)) {
							view = active.getValue();
							value = entry;
							break;
						}
					}
				} else if (active.getKey().getType() instanceof NumericType type) {
					long other = String.valueOf(type.getMin()).equals(current) ? type.getMax() : type.getMin();
					view = active.getValue();
					value = other;
				}
				if (view != null) {
					break;
				}
			}
			assertNotNull("No editable parameter in the BCU 1 fixture", view);
			try (Transaction tx = kb.beginTransaction()) {
				assertNotNull(DeviceSettings.setValue(device, view, value));
				tx.commit();
			}
			assertEquals(1, DeviceChanges.sinceDownload(device).getCount());
			DownloadPlan edited = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertTrue(edited.getProblems().toString(), edited.isExecutable());
			int differing = countDifferences(plan.getImage(), edited.getImage());
			assertTrue("The edit changed " + differing + " bytes", differing >= 1 && differing <= 4);
			DeviceComparison.Result changed = DeviceComparison.compare(DeviceConfiguration.program(device),
				edited.getImage(), edited.getUnloadedBlocks(), mock, (number, count, block) -> { /* Ignore. */ });
			assertEquals(same.blocks().size() - 1, changed.identicalBlocks());

			// The second download: identical again, no changes.
			result = DownloadService.download(device, edited, mock,
				new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
			assertTrue(result.failure(), result.success());
			assertEquals(0, DeviceChanges.sinceDownload(device).getCount());
			DeviceComparison.Result after = DeviceComparison.compare(DeviceConfiguration.program(device),
				edited.getImage(), edited.getUnloadedBlocks(), mock, (number, count, block) -> { /* Ignore. */ });
			assertEquals(after.blocks().size(), after.identicalBlocks());

			// The export round trip: the same image for the edited device.
			try (OutputStream out = new FileOutputStream(export)) {
				KnxprojExporter.export(project, out);
			}
			List<TLObject> beforeReimport = instances("ehome.project:Project");
			importSample(export.getPath());
			reimported = newProject(beforeReimport);
			DownloadImage again = DownloadImage.compute(devicesByAddress(reimported).get(1));
			assertEquals(0, countDifferences(edited.getImage(), again));
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				if (reimported != null) {
					reimported.tDelete();
				}
				tx.commit();
			}
		}
	}

	private static int countDifferences(DownloadImage a, DownloadImage b) {
		List<Block> x = a.getBlocks();
		List<Block> y = b.getBlocks();
		assertEquals(x.size(), y.size());
		int result = 0;
		for (int n = 0; n < x.size(); n++) {
			assertEquals(x.get(n).address(), y.get(n).address());
			assertTrue(Arrays.equals(x.get(n).mask(), y.get(n).mask()));
			for (int i = 0; i < x.get(n).data().length; i++) {
				if (x.get(n).data()[i] != y.get(n).data()[i]) {
					result++;
				}
			}
		}
		return result;
	}

	private static TLObject newProject(List<TLObject> before) {
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		return projects.get(0);
	}

	private static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new java.util.HashMap<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					result.put((Integer) ((TLObject) device).tValueByName("address"), (TLObject) device);
				}
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBcuImageProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
