/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.zigbee;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.ImageValidator;
import de.haumacher.ehome.runtime.Json;

/**
 * The checks of the Zigbee binding of a datapoint (homecontrol <code>spec/zigbee.md</code>, "Bindings",
 * the loader's <code>parse_zigbee</code>) made by {@link ImageValidator}: each refusal of the table of
 * the specification with its path, on images written as JSON (no knowledge base).
 */
@SuppressWarnings("javadoc")
public class TestZigbeeBindingChecks extends TestCase {

	private static final String IEEE = "00158d0001020304";

	public void testValid() {
		ImageReport report = validate(List.of(
			datapoint("a", "boolean", false, zigbee(IEEE, 11, 6, 0, 6)),
			datapoint("b", "real", false, zigbee("00:15:8d:00:01:02:03:04", 11, 1026, 0, null)),
			datapoint("c", "unsigned", false, client(IEEE, 1, 8)),
			datapoint("d", "real", true, zigbee(IEEE, 11, 8, 0, 8)),
			datapoint("e", "boolean", true, zigbee(IEEE, 12, 6, 0, 8))));
		assertTrue(report.toString(), report.isValid());
		assertTrue(report.getNotes().toString(), report.getNotes().isEmpty());
	}

	public void testRefusals() {
		Map<String, Object> noAttribute = zigbee(IEEE, 1, 1026, 0, null);
		noAttribute.remove("attribute");
		Map<String, Object> clientCommand = client(IEEE, 1, 6);
		clientCommand.put("command", Long.valueOf(6));
		Map<String, Object> knx = datapoint("knx", "boolean", false, zigbee(IEEE, 1, 6, 0, null));
		knx.put("knx", Map.of("dpt", "1.001", "listen", List.of("1/0/1")));
		ImageReport report = validate(List.of(
			datapoint("ieee", "boolean", false, zigbee("0000000000000000", 1, 6, 0, null)),
			datapoint("endpoint", "boolean", false, zigbee(IEEE, 241, 6, 0, null)),
			datapoint("attribute", "real", false, noAttribute),
			datapoint("unknown", "real", false, zigbee(IEEE, 1, 1026, 5, null)),
			datapoint("command", "real", false, zigbee(IEEE, 1, 1026, 0, 1026)),
			datapoint("clientCluster", "enumeration", false, client(IEEE, 1, 1030)),
			datapoint("clientCommand", "enumeration", false, clientCommand),
			datapoint("type", "string", false, zigbee(IEEE, 1, 1030, 0, null)),
			datapoint("transmit", "real", true, zigbee(IEEE, 1, 1026, 0, null)),
			knx,
			datapoint("twice", "boolean", false, zigbee(IEEE, 2, 6, 0, null)),
			datapoint("again", "boolean", false, zigbee(IEEE, 2, 6, 0, null))));
		assertRefusal(report, "datapoints[0].zigbee.ieee", "\"0000000000000000\" is not an IEEE address");
		assertRefusal(report, "datapoints[1].zigbee.endpoint", "endpoint must be 1..240");
		assertRefusal(report, "datapoints[2].zigbee", "attribute is missing (only a client binding");
		assertRefusal(report, "datapoints[3].zigbee", "cluster 1026 attribute 5: no known function");
		assertRefusal(report, "datapoints[4].zigbee", "cluster 1026 attribute 0: the command cluster cannot set this attribute");
		assertRefusal(report, "datapoints[5].zigbee", "client cluster 1030: a client binding (button commands) needs cluster 6");
		assertRefusal(report, "datapoints[6].zigbee", "client cluster 6: a client binding (button commands) cannot be commanded");
		assertRefusal(report, "datapoints[7].zigbee", "the function occupancy carries boolean values, the datapoint is string");
		assertRefusal(report, "datapoints[8].zigbee", "a transmitting Zigbee datapoint needs the cluster its commands go to");
		assertRefusal(report, "datapoints[9].zigbee", "a datapoint is either knx or zigbee");
		assertRefusal(report, "datapoints[11].zigbee",
			"device " + IEEE + " endpoint 2: the function on_off is bound twice (also datapoints[10] 'twice')");
		assertEquals(report.toString(), 11, report.getRefusals().size());
	}

	public void testLimit() {
		List<Object> datapoints = new ArrayList<>();
		for (int n = 0; n <= ImageValidator.MAX_ZIGBEE_BINDINGS; n++) {
			datapoints.add(datapoint("dp" + n, "boolean", false, zigbee(IEEE, 1 + n / 4, 6, 0, null)));
		}
		ImageReport report = validate(datapoints);
		assertRefusal(report, "datapoints", "513 Zigbee bindings exceed the limit of 512");
	}

	private static ImageReport validate(List<Object> datapoints) {
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("image", Map.of("schemaVersion", "1.0", "projectId", "P-TEST", "revision", Long.valueOf(1)));
		image.put("datapoints", datapoints);
		return ImageValidator.validate(Json.write(image));
	}

	private static void assertRefusal(ImageReport report, String path, String reason) {
		for (Refusal refusal : report.getRefusals()) {
			if (refusal.path().equals(path) && refusal.reason().contains(reason)) {
				return;
			}
		}
		fail("Expected refusal at " + path + " (" + reason + "): " + report);
	}

	private static Map<String, Object> datapoint(String id, String type, boolean writable, Map<String, Object> zigbee) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("type", type);
		if (writable) {
			result.put("writable", Boolean.TRUE);
			result.put("transmit", Boolean.TRUE);
		}
		result.put("zigbee", zigbee);
		return result;
	}

	private static Map<String, Object> zigbee(String ieee, int endpoint, int cluster, int attribute, Integer command) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ieee", ieee);
		result.put("endpoint", Long.valueOf(endpoint));
		result.put("cluster", Long.valueOf(cluster));
		result.put("attribute", Long.valueOf(attribute));
		if (command != null) {
			result.put("command", Long.valueOf(command.longValue()));
		}
		return result;
	}

	private static Map<String, Object> client(String ieee, int endpoint, int cluster) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ieee", ieee);
		result.put("endpoint", Long.valueOf(endpoint));
		result.put("cluster", Long.valueOf(cluster));
		result.put("client", Boolean.TRUE);
		return result;
	}

	public static Test suite() {
		return new TestSuite(TestZigbeeBindingChecks.class);
	}

}
