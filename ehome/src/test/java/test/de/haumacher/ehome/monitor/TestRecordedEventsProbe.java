/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.runtime.FakeController;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.ControllerBusLink;
import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.ProjectIndex;
import de.haumacher.ehome.monitor.RecordingCsv;
import de.haumacher.ehome.runtime.ControllerClient;

/**
 * Probe of the recorded controller events (haumacher/ehome #55) with texts the controller chooses: a
 * rule and a datapoint name with the CSV separator, quotes and a line break still give one record of
 * 15 fields per entry, and the texts come back unchanged.
 */
@SuppressWarnings("javadoc")
public class TestRecordedEventsProbe extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	public void testAwkwardTexts() throws Exception {
		TLObject project = TestControllerChoice.createProject("TestRecordedEventsProbe");
		FakeController fake = new FakeController(TOKEN, 262144);
		GroupMonitor monitor = new GroupMonitor(ProjectIndex.of(project));
		try {
			monitor.connect(ControllerBusLink.open("fake",
				new ControllerClient("127.0.0.1", fake.getPort(), TOKEN, Duration.ofSeconds(5)), 150, 150, 0));
			TLObject recording = monitor.startRecording("Probe");
			String name = "hall;light \"main\"\nsecond line";
			fake.getMonitor().emit(Map.of("kind", "rule", "rule", "r;1", "dp", 3, "name", name, "value", Boolean.TRUE));
			long end = System.currentTimeMillis() + 5000;
			while (((List<?>) monitor.snapshot().get("log")).isEmpty()) {
				assertTrue("No log row within 5 s.", System.currentTimeMillis() < end);
				Thread.sleep(10);
			}
			monitor.stopRecording();

			String csv = String.join("\n", RecordingCsv.lines(recording, TimeZone.getTimeZone("UTC")));
			List<List<String>> records = parse(csv);
			assertEquals(csv, 2, records.size());
			for (List<String> record : records) {
				assertEquals(record.toString(), 15, record.size());
			}
			assertEquals(name, records.get(1).get(5));
			assertTrue(records.get(1).get(14), records.get(1).get(14).contains("r;1"));
		} finally {
			monitor.close();
			fake.stop();
			TestControllerChoice.delete(project);
		}
	}

	/** RFC 4180 with ';' as separator. */
	private static List<List<String>> parse(String csv) {
		List<List<String>> result = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int n = 0; n < csv.length(); n++) {
			char c = csv.charAt(n);
			if (quoted) {
				if (c == '"') {
					if (n + 1 < csv.length() && csv.charAt(n + 1) == '"') {
						field.append('"');
						n++;
					} else {
						quoted = false;
					}
				} else {
					field.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ';') {
				record.add(field.toString());
				field.setLength(0);
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && n + 1 < csv.length() && csv.charAt(n + 1) == '\n') {
					n++;
				}
				record.add(field.toString());
				field.setLength(0);
				result.add(record);
				record = new ArrayList<>();
			} else {
				field.append(c);
			}
		}
		record.add(field.toString());
		result.add(record);
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestRecordedEventsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
