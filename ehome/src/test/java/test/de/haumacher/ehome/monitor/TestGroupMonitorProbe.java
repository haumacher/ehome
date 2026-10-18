/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
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
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.MonitorRow;
import de.haumacher.ehome.monitor.ProjectIndex;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * Probe of the group monitor (issue #10) composed with the wiring of the test system: a telegram
 * on an address is attributed to the device that the address view names as its sender, a key of
 * the Tastsensor without a datapoint type decodes as on/off through its 1-bit object, a recording
 * belongs to the project and goes with it, and the project exports unchanged with a recording.
 */
@SuppressWarnings("javadoc")
public class TestGroupMonitorProbe extends BasicTestCase {

	private static final int TASTSENSOR = 0x1332;

	private static final int ACTUATOR = 0x1305;

	public void testProbe() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		TLObject project = newProject(before);
		File export = new File("target/roundtrip/probe-monitor.knxproj");
		export.getParentFile().mkdirs();
		int telegramsBefore = instances("ehome.monitor:Telegram").size();
		try {
			TLObject sensor = devicesByAddress(project).get(50);
			TLObject key1 = DeviceSettings.groupAddresses(sensor).stream()
				.filter(a -> ((GA) a.tValueByName("address")).getValue() == 1)
				.findFirst().orElseThrow();
			TLObject channelA = DeviceSettings.groupAddresses(sensor).stream()
				.filter(a -> ((GA) a.tValueByName("address")).getValue() == 34)
				.findFirst().orElseThrow();
			TLObject senderOfChannelA = GroupAddressConnections.device(GroupAddressConnections.connections(channelA).get(0));

			GroupMonitor monitor = new GroupMonitor(ProjectIndex.of(project));
			monitor.setBatchMillis(3_600_000);
			MockBusLink link = new MockBusLink();
			monitor.connect(link);
			TLObject recording = monitor.startRecording("Probe");

			// The Tastsensor's key 1 (no datapoint type, a 1-bit object) and the actuator's channel A.
			link.write(TASTSENSOR, 1, 0x01);
			link.write(ACTUATOR, 34, 0x01);
			link.write(TASTSENSOR, 1, 0x00);
			Map<String, Object> state = monitor.snapshot();
			List<MonitorRow> board = rows(state.get("board"));
			MonitorRow keyRow = row(board, 1);
			MonitorRow channelRow = row(board, 34);
			assertEquals("off", keyRow.get("value"));
			assertEquals(2, ((Number) keyRow.get("count")).intValue());
			assertEquals("on", channelRow.get("value"));
			// The sender of a telegram on channel A is the device the address view names.
			String senderName = String.valueOf(senderOfChannelA.tValueByName("name")).replaceAll(" \\(\\d+\\)$", "");
			assertTrue(String.valueOf(channelRow.get("sender")), String.valueOf(channelRow.get("sender")).startsWith(senderName));
			assertTrue(String.valueOf(keyRow.get("sender")), String.valueOf(keyRow.get("sender")).contains("1.3.50"));
			List<MonitorRow> log = rows(state.get("log"));
			assertEquals(3, log.size());

			// Sending is off: refused; on: a 1-bit write to channel A as the actuator expects it.
			try {
				monitor.send(34, "1", null);
				fail("Sent while sending is disabled");
			} catch (RuntimeException ex) {
				// Expected.
			}
			assertTrue(link.getSent().isEmpty());
			monitor.setSending(true);
			monitor.send(34, "1", null);
			assertEquals(1, link.getSent().size());
			assertTrue(link.getSent().get(0), link.getSent().get(0).endsWith("00 81"));

			// The recording belongs to the project and holds the telegrams after the flush.
			monitor.flushRecording();
			monitor.stopRecording();
			assertTrue(((Collection<?>) project.tValueByName("recordings")).contains(recording));
			assertEquals(4, ((Collection<?>) recording.tValueByName("telegrams")).size());
			monitor.disconnect();
			assertFalse(monitor.isConnected());

			// The export of a project with a recording is a project export like any other.
			try (OutputStream out = new FileOutputStream(export)) {
				KnxprojExporter.export(project, out);
			}
			assertTrue(export.length() > 100_000);
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
		// The recording and its telegrams went with the project.
		assertEquals(telegramsBefore, instances("ehome.monitor:Telegram").size());
	}

	@SuppressWarnings("unchecked")
	private static List<MonitorRow> rows(Object value) {
		return (List<MonitorRow>) value;
	}

	private static MonitorRow row(List<MonitorRow> rows, int address) {
		for (MonitorRow row : rows) {
			if (Integer.valueOf(address).equals(row.get("address"))
				|| String.valueOf(row.get("address")).equals(new GA(address, de.haumacher.ehome.knx.GroupAddressStyle.ThreeLevel).toString())
				|| Integer.valueOf(address).equals(row.get(MonitorRow.KEY))) {
				return row;
			}
		}
		throw new AssertionError("No row for " + address + " in " + rows);
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupMonitorProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
