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
import java.util.HashMap;
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
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.DownloadExecutor;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.DownloadService;

/**
 * Probe of the changes since the last download (issue #15) composed with the download, the
 * structure and the export: a download through the executor is the reference; flipping a switch
 * of the structure and wiring an object count as changes; an export imported again carries the
 * edited state as its own reference with no changes; discarding everything restores the memory
 * image of the download byte for byte.
 */
@SuppressWarnings("javadoc")
public class TestDeviceChangesProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		TLObject project = newProject(before);
		File export = new File("target/roundtrip/probe-changes.knxproj");
		export.getParentFile().mkdirs();
		TLObject reimported = null;
		try {
			Map<Integer, TLObject> devices = devicesByAddress(project);
			TLObject actuator = devices.get(5);
			TLObject sensor = devices.get(50);
			DownloadImage sensorImage = DownloadImage.compute(sensor);

			// A download through the executor: the reference from now on, no changes.
			DownloadPlan plan = DownloadPlanner.plan(actuator, DownloadPlan.Kind.FULL);
			MockDevice mock = new MockDevice()
				.setProperty(0, 78, "00000000030d")
				.setProperty(3, 13, "0083000c21")
				.setState(1, 1).setState(2, 1).setState(3, 1);
			DownloadExecutor.Result result = DownloadService.download(actuator, plan, mock,
				new DownloadExecutor(ms -> { /* No wait. */ }), (number, count, operation) -> { /* Ignore. */ });
			assertTrue(result.failure(), result.success());
			DownloadImage atDownload = DownloadImage.compute(actuator);
			assertEquals(0, DeviceChanges.sinceDownload(actuator).getCount());

			// Flip a switch of the structure through its input, and wire an object.
			DeviceConfiguration configuration = new DeviceConfiguration(actuator);
			Switch choice = null;
			EnumerationValue other = null;
			for (Object obj : instances("ehome.operational:Switch")) {
				Switch candidate = (Switch) obj;
				ParameterView input = candidate.getInput();
				if (input == null || !configuration.getActiveViews().containsValue(input)
					|| !(input.getParameter().getType() instanceof EnumerationType type)
					|| !DeviceConfiguration.isEditable(input)) {
					continue;
				}
				String current = configuration.value(input);
				for (EnumerationValue entry : type.getValues()) {
					if (!String.valueOf(entry.getValue()).equals(current)) {
						choice = candidate;
						other = entry;
						break;
					}
				}
				if (choice != null) {
					break;
				}
			}
			assertNotNull("No switch of the actuator to flip", choice);
			TLObject address = DeviceSettings.groupAddresses(actuator).stream()
				.filter(a -> ((GA) a.tValueByName("address")).getValue() == 1)
				.findFirst().orElseThrow();
			Interface channelA = DeviceSettings.interfaces(actuator).stream()
				.filter(i -> i.getPort().getIndex() == 0)
				.findFirst().orElseThrow();
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.setValue(actuator, choice.getInput(), other);
				DeviceSettings.connect(actuator, channelA, address,
					(TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#receive"));
				tx.commit();
			}
			DeviceChanges changes = DeviceChanges.sinceDownload(actuator);
			assertTrue(changes.getEntries().toString(), changes.getCount() >= 2);
			assertTrue(changes.isChanged(actuator));
			assertTrue(changes.getEntries().stream().anyMatch(DeviceChanges.Entry::isConnections));
			assertFalse(changes.getChangedPages().isEmpty());
			DownloadImage edited = DownloadImage.compute(actuator);
			assertFalse(sameBytes(atDownload, edited));
			assertEquals(0, DeviceChanges.sinceDownload(sensor).getCount());

			// The export imported again: the edited state is what that device holds, no changes.
			try (OutputStream out = new FileOutputStream(export)) {
				KnxprojExporter.export(project, out);
			}
			List<TLObject> beforeReimport = instances("ehome.project:Project");
			importSample(export.getPath());
			reimported = newProject(beforeReimport);
			TLObject again = devicesByAddress(reimported).get(5);
			assertEquals(0, DeviceChanges.sinceDownload(again).getCount());
			assertTrue(sameBytes(edited, DownloadImage.compute(again)));

			// Discarding everything: the memory image of the download, byte for byte.
			try (Transaction tx = kb.beginTransaction()) {
				assertTrue(DeviceChanges.discard(actuator) >= 2);
				tx.commit();
			}
			assertEquals(0, DeviceChanges.sinceDownload(actuator).getCount());
			assertTrue(sameBytes(atDownload, DownloadImage.compute(actuator)));
			assertTrue(sameBytes(sensorImage, DownloadImage.compute(sensor)));
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

	private static boolean sameBytes(DownloadImage a, DownloadImage b) {
		List<Block> x = a.getBlocks();
		List<Block> y = b.getBlocks();
		if (x.size() != y.size()) {
			return false;
		}
		for (int n = 0; n < x.size(); n++) {
			if (x.get(n).address() != y.get(n).address() || !Arrays.equals(x.get(n).data(), y.get(n).data())) {
				return false;
			}
		}
		return true;
	}

	private static TLObject newProject(List<TLObject> before) {
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		return projects.get(0);
	}

	private static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new HashMap<>();
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDeviceChangesProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
