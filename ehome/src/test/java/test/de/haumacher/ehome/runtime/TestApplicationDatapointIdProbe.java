/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.runtime.ApplicationDatapoints;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageValidator;

/**
 * Probe of the id check of application datapoints (haumacher/ehome #59) against the controller's
 * loader as ImageValidator ports it: an id the field accepts gives a valid image with a virtual
 * datapoint of that id, so the form never lets through what the deployment then refuses.
 */
@SuppressWarnings("javadoc")
public class TestApplicationDatapointIdProbe extends TestCase {

	public void testFieldAgreesWithLoader() {
		List<String> ids = new ArrayList<>(List.of("morgens", "küche.licht", "a", "a b", "a\"b", "a\\b", "x/y",
			"1/2", "1/2/3/4", "knx.timer", "zigbeex", "Zigbee.x", ".", "-", "ä".repeat(31), "ä".repeat(32),
			"a".repeat(63), "a".repeat(64), "日本", "emoji😀", "tab\there", " lead", "trail "));
		List<String> problems = new ArrayList<>();
		for (String id : ids) {
			if (ApplicationDatapoints.idProblem(id, null) != null) {
				continue;
			}
			String stored = id.trim();
			String image = "{\"image\": {\"schemaVersion\": \"1.0\", \"projectId\": \"probe\", \"revision\": 0},"
				+ " \"datapoints\": [{\"id\": " + json(stored) + ", \"label\": \"x\", \"type\": \"boolean\","
				+ " \"virtual\": {}}]}";
			ImageReport report = ImageValidator.validate(image);
			if (!report.isValid()) {
				problems.add("'" + id + "': " + report);
			}
		}
		assertEquals(List.of(), problems);
	}

	private static String json(String text) {
		StringBuilder result = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			if (c == '"' || c == '\\') {
				result.append('\\').append(c);
			} else if (c < 0x20) {
				result.append(String.format("\\u%04x", (int) c));
			} else {
				result.append(c);
			}
		}
		return result.append('"').toString();
	}

	public static Test suite() {
		return new TestSuite(TestApplicationDatapointIdProbe.class);
	}

}
