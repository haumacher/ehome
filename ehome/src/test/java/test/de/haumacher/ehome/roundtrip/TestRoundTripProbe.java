/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.roundtrip;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.physical.Area;
import de.haumacher.ehome.model.physical.BusDevice;
import de.haumacher.ehome.model.physical.Connection;
import de.haumacher.ehome.model.physical.Line;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Probe of the export (issue #12) composed with the editing of a device: what the user changed in
 * the app, a parameter value and a connection, survives the export and the import of the export,
 * and the download image of the re-imported device is the edited one, byte for byte.
 */
@SuppressWarnings("javadoc")
public class TestRoundTripProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		TLObject project = newProject(before);
		File export = new File("target/roundtrip/probe-edited.knxproj");
		export.getParentFile().mkdirs();
		String parameterName;
		String entryValue;
		int objectIndex;
		int addressNumber;
		int connectionsBefore;
		DownloadImage edited;
		try {
			TLObject actuator = devicesByAddress(project).get(5);

			// An edit of a parameter in effect, as the device view does it.
			DeviceConfiguration configuration = new DeviceConfiguration(actuator);
			ParameterView view = null;
			EnumerationValue other = null;
			for (Map.Entry<Parameter, ParameterView> active : configuration.getActiveViews().entrySet()) {
				if (active.getKey().getType() instanceof EnumerationType type && DeviceConfiguration.isEditable(active.getValue())) {
					String current = configuration.value(active.getValue());
					for (EnumerationValue entry : type.getValues()) {
						if (!String.valueOf(entry.getValue()).equals(current)) {
							view = active.getValue();
							other = entry;
							break;
						}
					}
				}
				if (view != null) {
					break;
				}
			}
			assertNotNull(view);
			parameterName = view.getParameter().getName();
			entryValue = String.valueOf(other.getValue());
			try (Transaction tx = kb.beginTransaction()) {
				assertNotNull(DeviceSettings.setValue(actuator, view, other));
				tx.commit();
			}

			// A further receiving connection of an object in effect, to an address of the Tastsensor
			// (an object may receive from several addresses).
			TLObject address = DeviceSettings.groupAddresses(actuator).stream()
				.filter(a -> ((GroupAddress) a).getAddress().getValue() == 1)
				.findFirst().orElseThrow();
			addressNumber = 1;
			Interface free = DeviceSettings.interfaces(actuator).stream()
				.filter(i -> DeviceSettings.connections(actuator, i).stream().noneMatch(c -> ((Connection) c).getTarget() == address))
				.findFirst().orElseThrow();
			objectIndex = free.getPort().getIndex();
			connectionsBefore = DeviceSettings.connections(actuator, free).size();
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.connect(actuator, free, address, (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#receive"));
				tx.commit();
			}
			edited = DownloadImage.compute(actuator);
			assertEquals(edited.getProblems().toString(), 0, edited.getProblems().size());

			try (OutputStream out = new FileOutputStream(export)) {
				KnxprojExporter.export(project, out);
			}
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}

		// The export imported: the edits are there, and the image is the edited one.
		List<TLObject> beforeReimport = instances("ehome.project:Project");
		importSample(export.getPath());
		TLObject reimported = newProject(beforeReimport);
		try {
			TLObject actuator = devicesByAddress(reimported).get(5);
			DeviceConfiguration configuration = new DeviceConfiguration(actuator);
			ParameterView view = configuration.getActiveViews().entrySet().stream()
				.filter(e -> parameterName.equals(e.getKey().getName()))
				.map(Map.Entry::getValue)
				.findFirst().orElseThrow();
			assertEquals(entryValue, configuration.value(view));
			assertNotNull("The edited value is a setting of the device", DeviceSettings.setting(actuator, view));
			Interface placed = DeviceSettings.interfaces(actuator).stream()
				.filter(i -> i.getPort().getIndex() == objectIndex)
				.findFirst().orElseThrow();
			List<TLObject> connections = DeviceSettings.connections(actuator, placed);
			assertEquals(connectionsBefore + 1, connections.size());
			TLObject connection = connections.stream()
				.filter(c -> ((Connection) c).getTarget().getAddress().getValue() == addressNumber)
				.findFirst().orElseThrow();
			assertEquals("receive", ((Connection) connection).getKind().getName());

			DownloadImage again = DownloadImage.compute(actuator);
			assertEquals(again.getProblems().toString(), 0, again.getProblems().size());
			List<Block> x = edited.getBlocks();
			List<Block> y = again.getBlocks();
			assertEquals(x.size(), y.size());
			for (int n = 0; n < x.size(); n++) {
				assertEquals(x.get(n).name(), y.get(n).name());
				assertEquals(x.get(n).address(), y.get(n).address());
				assertTrue(x.get(n).name(), Arrays.equals(x.get(n).data(), y.get(n).data()));
				assertTrue(x.get(n).name(), Arrays.equals(x.get(n).mask(), y.get(n).mask()));
			}
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				reimported.tDelete();
				tx.commit();
			}
		}
	}

	private static TLObject newProject(List<TLObject> before) {
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		return projects.get(0);
	}

	private static Map<Integer, TLObject> devicesByAddress(TLObject project) {
		Map<Integer, TLObject> result = new java.util.HashMap<>();
		for (Area area : ((Project) project).getAreas()) {
			for (Line line : area.getLines()) {
				for (BusDevice device : line.getDevices()) {
					result.put(device.getAddress(), device);
				}
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRoundTripProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
