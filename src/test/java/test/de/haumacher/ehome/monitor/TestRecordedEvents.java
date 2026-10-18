/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.runtime.FakeController;
import test.de.haumacher.ehome.runtime.FakeMonitorStream;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.ControllerBusLink;
import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.I18NConstants;
import de.haumacher.ehome.monitor.MonitorRow;
import de.haumacher.ehome.monitor.ProjectIndex;
import de.haumacher.ehome.monitor.RecordingCsv;
import de.haumacher.ehome.monitor.RecordingEntries;
import de.haumacher.ehome.runtime.ControllerClient;

/**
 * A recording of the group monitor attached to a HomeControl controller keeps the controller's events
 * that are no telegrams (haumacher/ehome #55): stored as <code>ehome.monitor:Event</code> beside the
 * telegrams, numbered in one sequence with them, read back in the order they were received
 * ({@link RecordingEntries}), and exported as lines of the CSV file.
 *
 * <p>
 * Runs on an empty project of its own against a {@link FakeController}: no import.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRecordedEvents extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	public void testEventsRecorded() throws Exception {
		TLObject project = TestControllerChoice.createProject("TestRecordedEvents");
		FakeController fake = new FakeController(TOKEN, 262144);
		GroupMonitor monitor = new GroupMonitor(ProjectIndex.of(project));
		try {
			FakeMonitorStream stream = fake.getMonitor();
			monitor.connect(ControllerBusLink.open("fake",
				new ControllerClient("127.0.0.1", fake.getPort(), TOKEN, Duration.ofSeconds(5)), 150, 150, 0));
			TLObject recording = monitor.startRecording("Events");

			stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", "01", true, null, null));
			stream.emit(Map.of("kind", "rule", "rule", "hall-presence-light", "dp", 3, "name", "hall.light", "value",
				Boolean.TRUE));
			stream.emit(Map.of("kind", "zigbee", "event", "report", "ieee", "00124b0001020304", "ep", 11, "cluster", 6,
				"attr", 0, "name", "zigbee.on", "value", Boolean.TRUE));
			stream.emit(Map.of("kind", "command", "origin", "ui", "name", "hall.light", "value", Boolean.FALSE));
			stream.emit(Map.of("kind", "image", "event", "load", "project", "example-house", "revision", 7, "source",
				"api"));
			stream.emit(FakeMonitorStream.knx("1.3.5", "0/0/34", "write", "00", true, null, null));
			awaitLog(monitor, 6);
			monitor.stopRecording();

			assertEquals(2, ((Collection<?>) recording.tValueByName("telegrams")).size());
			assertEquals(4, ((Collection<?>) recording.tValueByName("events")).size());
			assertEquals(6, recording.tValueByName("count"));

			// Read back in the order received, events between the telegrams.
			List<TLObject> entries = RecordingEntries.entries(recording);
			assertEquals(6, entries.size());
			String[] kinds = { null, "rule", "zigbee", "command", "image", null };
			for (int n = 0; n < entries.size(); n++) {
				TLObject entry = entries.get(n);
				assertEquals(n + 1, RecordingEntries.number(entry));
				assertEquals(kinds[n] != null, RecordingEntries.isEvent(entry));
				if (kinds[n] != null) {
					assertEquals(kinds[n], ((TLClassifier) entry.tValueByName("kind")).getName());
					assertNotNull(entry.tValueByName("time"));
				}
			}
			TLObject rule = entries.get(1);
			assertEquals(text(I18NConstants.EVENT_RULE__RULE.fill("hall-presence-light")), rule.tValueByName("text"));
			assertEquals("hall.light", rule.tValueByName("name"));
			assertEquals("on", rule.tValueByName("value"));
			assertEquals(text(I18NConstants.EVENT_IMAGE_LOAD__PROJECT_REVISION_SOURCE.fill("example-house", "7", "api")),
				entries.get(4).tValueByName("text"));
			assertNull(entries.get(4).tValueByName("value"));

			// The rows the recording shows when it is opened.
			List<MonitorRow> rows = RecordingEntries.rows(recording, TimeZone.getTimeZone("UTC"));
			assertEquals(6, rows.size());
			assertEquals(text(I18NConstants.EVENT_KIND_KNX), rows.get(0).get("kind"));
			assertEquals("0/0/34", rows.get(0).get("address"));
			// The empty project knows no datapoint type of the address: the data as they are.
			assertEquals("01", rows.get(0).get("value"));
			assertEquals(text(I18NConstants.EVENT_KIND_RULE), rows.get(1).get("kind"));
			assertEquals(rule.tValueByName("text"), rows.get(1).get("sender"));
			assertEquals(text(I18NConstants.EVENT_KIND_ZIGBEE), rows.get(2).get("kind"));
			assertEquals(text(I18NConstants.EVENT_ZIGBEE_REPORT__DEVICE_ENDPOINT_CLUSTER_ATTRIBUTE
				.fill("00124b0001020304", "11", "6", "0")), rows.get(2).get("sender"));
			assertEquals(text(I18NConstants.EVENT_KIND_COMMAND), rows.get(3).get("kind"));
			assertEquals("off", rows.get(3).get("value"));
			assertEquals(text(I18NConstants.EVENT_KIND_IMAGE), rows.get(4).get("kind"));
			assertEquals("00", rows.get(5).get("data"));

			// The CSV export has a line per event, with its kind and text.
			List<String> lines = RecordingCsv.lines(recording, TimeZone.getTimeZone("UTC"));
			assertEquals(7, lines.size());
			for (String line : lines) {
				assertEquals(line, 15, line.split(";", -1).length);
			}
			String[] ruleLine = lines.get(2).split(";", -1);
			assertEquals("2", ruleLine[0]);
			assertEquals("", ruleLine[4]);
			assertEquals("hall.light", ruleLine[5]);
			assertEquals("on", ruleLine[11]);
			assertEquals("rule", ruleLine[13]);
			assertEquals(rule.tValueByName("text"), ruleLine[14]);
			assertEquals("knx", lines.get(6).split(";", -1)[13]);

			// A recording without controller events is read as before: the events are optional.
			TLObject old;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				old = ModelService.getInstance().getFactory()
					.createObject((TLClass) TLModelUtil.findType("ehome.monitor:Recording"));
				old.tUpdateByName("name", "Old");
				project.tAdd((TLReference) TLModelUtil.findPart("ehome.project:Project#recordings"), old);
				tx.commit();
			}
			assertEquals(List.of(), RecordingEntries.entries(old));
			assertEquals(1, RecordingCsv.lines(old, TimeZone.getTimeZone("UTC")).size());
		} finally {
			monitor.close();
			fake.stop();
			TestControllerChoice.delete(project);
		}
	}

	private static void awaitLog(GroupMonitor monitor, int size) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (((List<?>) monitor.snapshot().get("log")).size() < size) {
			if (System.currentTimeMillis() > end) {
				fail("The log did not reach " + size + " rows within 5 s.");
			}
			Thread.sleep(10);
		}
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRecordedEvents.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
