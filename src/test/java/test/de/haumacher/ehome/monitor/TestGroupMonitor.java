/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.MonitorRow;
import de.haumacher.ehome.monitor.ProjectIndex;
import de.haumacher.ehome.monitor.ProjectIndex.AddressInfo;
import de.haumacher.ehome.monitor.RecordingCsv;

/**
 * The group monitor (haumacher/ehome #10) on the user's test system
 * (<code>samples/KNX-Testsystem.knxproj</code>) with a mocked bus: the actuator 1.3.5 switches its
 * channel A on 0/0/34 (DPT 1.001), the Tastsensor 1.3.50 sends the room temperature on 0/0/28
 * (DPT 9.001); an address is given the type 5.001 (percent) for the test.
 *
 * <p>
 * Received frames are decoded into the state board (one row per address with the latest value)
 * and the log; a recording stores the telegrams in batches and exports them as CSV; sending
 * produces the right frame for each kind of datapoint type and is refused while it is not allowed.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestGroupMonitor extends BasicTestCase {

	private static final int ACTUATOR = 0x1305;

	private static final int TASTSENSOR = 0x1332;

	private static final int UNKNOWN_DEVICE = 0x11C8;

	private static final int SWITCH = 34;

	private static final int UNKNOWN_ADDRESS = 0x7FFF;

	public void testMonitor() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			check(project);
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private void check(TLObject project) throws Exception {
		ProjectIndex index = ProjectIndex.of(project);
		assertEquals(new Dpt(1, 1), index.address(SWITCH).dpt());
		assertEquals("KanalA", index.address(SWITCH).name());
		int percent = addressOfType(index, 5, 1);
		assertEquals("Szene3", index.address(percent).name());
		// The keys of the Tastsensor have objects of 1 bit without a datapoint type.
		assertEquals(new Dpt(1, 0), index.address(1).dpt());

		// The room temperature of the Tastsensor has no datapoint type in the program; the project gives
		// the address its own.
		int temperature = 28;
		assertEquals("Raumtemperatur", index.address(temperature).name());
		assertNull(index.address(temperature).dpt());
		try (Transaction tx = kb().beginTransaction()) {
			index.address(temperature).groupAddress().tUpdateByName("datapointTypes", List.of(subtype(9, 1)));
			tx.commit();
		}
		index = ProjectIndex.of(project);
		assertEquals(new Dpt(9, 1), index.address(temperature).dpt());
		assertTrue(index.address(temperature).dptLabel(), index.address(temperature).dptLabel().startsWith("9.001 "));
		assertNull(index.address(UNKNOWN_ADDRESS));

		GroupMonitor monitor = new GroupMonitor(index);
		monitor.setBatchMillis(3_600_000);
		MockBusLink link = new MockBusLink();
		monitor.connect(link);
		assertTrue(monitor.isConnected());
		assertFalse(monitor.isSending());

		// Decoding of received frames.
		link.write(ACTUATOR, SWITCH, 0x01);
		link.write(TASTSENSOR, percent, 0xFF);
		link.write(TASTSENSOR, temperature, 0x0C, 0x33);
		link.write(UNKNOWN_DEVICE, UNKNOWN_ADDRESS, 0x12, 0x34);

		Map<String, Object> state = monitor.snapshot();
		assertEquals(Boolean.TRUE, state.get("connected"));
		assertEquals(Boolean.FALSE, state.get("sending"));
		List<MonitorRow> board = rows(state.get("board"));
		assertEquals(index.getAddresses().size() + 1, board.size());

		MonitorRow switchRow = row(board, SWITCH);
		assertEquals("0/0/34", switchRow.get("address"));
		assertEquals("KanalA", switchRow.get("name"));
		assertEquals("1.001", switchRow.get("dpt"));
		assertEquals("switch", switchRow.get("kind"));
		assertEquals("on", switchRow.get("value"));
		assertTrue((String) switchRow.get("sender"), ((String) switchRow.get("sender")).endsWith("(1.3.5)"));
		assertEquals(1, switchRow.get("count"));
		assertEquals(Boolean.TRUE, switchRow.get("fresh"));

		assertEquals("100 %", row(board, percent).get("value"));
		assertEquals("percent", row(board, percent).get("kind"));
		assertEquals("21.5 °C", row(board, temperature).get("value"));
		assertTrue(((String) row(board, temperature).get("sender")).endsWith("(1.3.50)"));

		MonitorRow unknownRow = row(board, UNKNOWN_ADDRESS);
		assertEquals("15/7/255", unknownRow.get("address"));
		assertEquals(Boolean.FALSE, unknownRow.get("known"));
		assertEquals("raw", unknownRow.get("kind"));
		assertEquals("12 34", unknownRow.get("value"));
		assertEquals("1.1.200", unknownRow.get("sender"));

		// An address without traffic has its row with no value.
		MonitorRow quiet = row(board, 2);
		assertEquals(0, quiet.get("count"));
		assertEquals("", quiet.get("value"));

		// The state board keeps one row per address with the latest value.
		link.write(ACTUATOR, SWITCH, 0x00);
		state = monitor.snapshot();
		board = rows(state.get("board"));
		assertEquals(index.getAddresses().size() + 1, board.size());
		assertEquals(1, board.stream().filter(r -> r.get(MonitorRow.KEY).equals(SWITCH)).count());
		assertEquals("off", row(board, SWITCH).get("value"));
		assertEquals(2, row(board, SWITCH).get("count"));
		// A row stands for its address: the new row equals the one of the previous update.
		assertEquals(switchRow, row(board, SWITCH));

		// The log, newest first.
		List<MonitorRow> log = rows(state.get("log"));
		assertEquals(5, log.size());
		assertEquals("off", log.get(0).get("value"));
		assertEquals("0/0/34", log.get(0).get("address"));
		assertEquals("KanalA", log.get(0).get("name"));
		assertEquals("00", log.get(0).get("data"));
		assertEquals("write", ((TLClassifier) log.get(0).get("service")).getName());
		assertEquals("low", ((TLClassifier) log.get(0).get("priority")).getName());
		assertEquals(Boolean.FALSE, log.get(0).get("own"));
		assertEquals("on", log.get(4).get("value"));
		assertEquals("12 34", log.get(1).get("value"));
		assertEquals("1.1.200", log.get(1).get("sender"));

		// Sending is refused until it is allowed.
		try {
			monitor.send(SWITCH, "on", null);
			fail("Sending must be refused while it is not allowed.");
		} catch (TopLogicException ex) {
			// Expected.
		}
		try {
			monitor.read(SWITCH);
			fail("Reading must be refused while sending is not allowed.");
		} catch (TopLogicException ex) {
			// Expected.
		}
		assertTrue(link.getSent().toString(), link.getSent().isEmpty());

		// The frame of each kind of datapoint type.
		monitor.setSending(true);
		monitor.send("0/0/34", "on", null);
		monitor.send(SWITCH, Boolean.FALSE, null);
		monitor.send(SWITCH, "1", null);
		monitor.send(temperature, Double.valueOf(21.5), null);
		monitor.send(temperature, "-5,5", null);
		monitor.send(percent, Double.valueOf(73), null);
		monitor.send(UNKNOWN_ADDRESS, "12 34", null);
		monitor.send("15/7/255", "on", "1.001");
		monitor.read(SWITCH);
		assertEquals(List.of(
			"34: 00 81",
			"34: 00 80",
			"34: 00 81",
			temperature + ": 00 80 0C 33",
			temperature + ": 00 80 85 DA",
			percent + ": 00 80 BA",
			UNKNOWN_ADDRESS + ": 00 80 12 34",
			UNKNOWN_ADDRESS + ": 00 81",
			"34: 00 00"), link.getSent());

		// A value that is no value of the type is refused, nothing is sent.
		for (Object invalid : List.of("warm", "")) {
			try {
				monitor.send(temperature, invalid, null);
				fail("Value must be refused: " + invalid);
			} catch (TopLogicException ex) {
				// Expected.
			}
		}
		try {
			monitor.send(SWITCH, "vielleicht", null);
			fail("Value must be refused.");
		} catch (TopLogicException ex) {
			// Expected.
		}
		assertEquals(9, link.getSent().size());

		// The own telegrams are in the log and on the state board.
		state = monitor.snapshot();
		log = rows(state.get("log"));
		assertEquals(Boolean.TRUE, log.get(0).get("own"));
		assertEquals("read", ((TLClassifier) log.get(0).get("service")).getName());
		assertEquals("1.1.250", log.get(0).get("source"));
		assertEquals("on", log.get(1).get("value"));
		assertEquals("15/7/255", log.get(1).get("address"));
		assertEquals("on", row(rows(state.get("board")), SWITCH).get("value"));
		assertEquals("-5.5 °C", row(rows(state.get("board")), temperature).get("value"));

		// The send form of an address.
		Map<String, Object> target = monitor.target("0/0/34", null);
		assertEquals("switch", target.get("kind"));
		assertEquals("off", target.get("off"));
		assertEquals("on", target.get("on"));
		assertNull(target.get("hint"));
		assertNotNull(monitor.target("15/7/255", null).get("hint"));
		assertEquals("switch", monitor.target("15/7/255", "1.001").get("kind"));
		assertNotNull(monitor.target("99/99/99", null).get("problem"));

		// A recording stores the telegrams in batches.
		monitor.setSending(false);
		TLObject recording = monitor.startRecording("Test");
		assertTrue(((Collection<?>) project.tValueByName("recordings")).contains(recording));
		link.write(ACTUATOR, SWITCH, 0x01);
		// The thread of a real link has no context of the application.
		link.writeFromLinkThread(TASTSENSOR, temperature, 0x0C, 0x33);
		link.write(UNKNOWN_DEVICE, UNKNOWN_ADDRESS, 0xAB);
		assertEquals(0, ((Collection<?>) recording.tValueByName("telegrams")).size());
		monitor.flushRecording();
		assertEquals(3, ((Collection<?>) recording.tValueByName("telegrams")).size());
		assertEquals(3, recording.tValueByName("count"));
		link.write(ACTUATOR, SWITCH, 0x00);
		link.write(TASTSENSOR, percent, 0xBA);
		assertEquals(3, ((Collection<?>) recording.tValueByName("telegrams")).size());
		monitor.stopRecording();
		assertNull(monitor.getRecording());
		assertEquals(5, ((Collection<?>) recording.tValueByName("telegrams")).size());
		assertEquals(5, recording.tValueByName("count"));
		assertNotNull(recording.tValueByName("end"));
		assertEquals("mock:3671", recording.tValueByName("gateway"));

		// Telegrams after the end are not recorded.
		link.write(ACTUATOR, SWITCH, 0x01);
		assertEquals(5, ((Collection<?>) recording.tValueByName("telegrams")).size());

		// The CSV export.
		List<String> lines = RecordingCsv.lines(recording, TimeZone.getTimeZone("UTC"));
		assertEquals(6, lines.size());
		assertEquals(RecordingCsv.HEADER, lines.get(0));
		assertLine(lines.get(1), "1", "1.3.5", "(1.3.5)", "0/0/34", "KanalA", "write", "low", "false", "6", "01", "on",
			"false");
		assertLine(lines.get(2), "2", "1.3.50", "(1.3.50)", "0/0/28", "Raumtemperatur", "write", "low", "false", "6",
			"0C 33", "21.5 °C", "false");
		assertLine(lines.get(3), "3", "1.1.200", "", "15/7/255", "", "write", "low", "false", "6", "AB", "AB",
			"false");
		assertLine(lines.get(4), "4", "1.3.5", "(1.3.5)", "0/0/34", "KanalA", "write", "low", "false", "6", "00", "off",
			"false");
		assertLine(lines.get(5), "5", "1.3.50", "(1.3.50)", "0/0/21", "Szene3", "write", "low", "false", "6", "BA",
			"72.9 %", "false");
		assertEquals("Test.csv", RecordingCsv.fileName(recording));

		// A key of the Tastsensor (an object of 1 bit without datapoint type) is decoded as a switch.
		link.write(TASTSENSOR, 1, 0x01);
		MonitorRow key = row(rows(monitor.snapshot().get("board")), 1);
		assertEquals("Taste1", key.get("name"));
		assertEquals("1.*", key.get("dpt"));
		assertEquals("switch", key.get("kind"));
		assertEquals("on", key.get("value"));

		// Disconnecting closes the link and locks sending.
		monitor.setSending(true);
		monitor.disconnect();
		assertTrue(link.isClosed());
		assertFalse(monitor.isConnected());
		assertFalse(monitor.isSending());
		try {
			monitor.send(SWITCH, "on", null);
			fail("Sending must be refused without connection.");
		} catch (TopLogicException ex) {
			// Expected.
		}
		monitor.close();
	}

	private static void assertLine(String line, String number, String source, String device, String address,
			String name, String service, String priority, String repeated, String hops, String data, String value,
			String own) {
		String[] fields = line.split(";", -1);
		assertEquals(line, 13, fields.length);
		assertEquals(line, number, fields[0]);
		assertTrue(line, fields[1].matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}"));
		assertEquals(line, source, fields[2]);
		assertTrue(line, device.isEmpty() ? fields[3].isEmpty() : fields[3].endsWith(device));
		assertEquals(line, address, fields[4]);
		assertEquals(line, name, fields[5]);
		assertEquals(line, service, fields[6]);
		assertEquals(line, priority, fields[7]);
		assertEquals(line, repeated, fields[8]);
		assertEquals(line, hops, fields[9]);
		assertEquals(line, data, fields[10]);
		assertEquals(line, value, fields[11]);
		assertEquals(line, own, fields[12]);
	}

	private static int addressOfType(ProjectIndex index, int main, int sub) {
		for (AddressInfo address : index.getAddresses().values()) {
			if (new Dpt(main, sub).equals(address.dpt())) {
				return address.address();
			}
		}
		throw new AssertionError("No address of the type " + main + "." + sub + ": " + index.getAddresses().values().stream().map(a -> a.text() + "=" + a.dpt()).toList());
	}

	private static TLObject subtype(int main, int sub) {
		for (TLObject type : instances("ehome.datapoint:DatapointSubtype")) {
			if (Integer.valueOf(sub).equals(type.tValueByName("number"))
				&& Integer.valueOf(main).equals(type.tContainer().tValueByName("number"))) {
				return type;
			}
		}
		throw new AssertionError("No datapoint type " + main + "." + sub);
	}

	@SuppressWarnings("unchecked")
	private static List<MonitorRow> rows(Object value) {
		return (List<MonitorRow>) value;
	}

	private static MonitorRow row(List<MonitorRow> rows, int address) {
		for (MonitorRow row : rows) {
			if (row.get(MonitorRow.KEY).equals(Integer.valueOf(address))) {
				return row;
			}
		}
		throw new AssertionError("No row for " + address);
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupMonitor.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
