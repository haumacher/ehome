/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.monitor.DatapointValues;

/**
 * Probe of the strict codec (haumacher/ehome #33) beyond the corpus: for every datum of the one- and
 * two-octet types, a value the codec decodes is encoded again, and decoding that gives the same value
 * (for the integer types the same data). The codec decodes only what it encodes.
 */
@SuppressWarnings("javadoc")
public class TestDatapointValuesProbe extends TestCase {

	private static final List<String> EXACT =
		List.of("5", "5.001", "5.003", "5.004", "5.005", "5.006", "5.010", "6.001", "6.010", "7.001", "7.002",
			"7.003", "7.004", "7.007", "8.001", "8.003", "8.004", "8.010", "17.001", "18.001", "20.102",
			"2.001", "3.007", "1.001");

	private static final List<String> FLOAT = List.of("9", "9.001", "9.004", "9.007", "9.027", "9.020");

	public void testExact() {
		for (String code : EXACT) {
			Dpt dpt = Dpt.parse(code);
			int octets = dpt.main() == 7 || dpt.main() == 8 ? 2 : 1;
			int count = 0;
			for (int n = 0; n < (1 << (8 * octets)); n++) {
				byte[] data = data(n, octets);
				Object value;
				try {
					value = DatapointValues.decode(dpt, data);
				} catch (DatapointValues.Refusal ex) {
					continue;
				}
				count++;
				byte[] again = DatapointValues.encode(dpt, value);
				assertEquals(code + " " + hex(data) + " -> " + value, DatapointValues.decode(dpt, data).toString(),
					DatapointValues.decode(dpt, again).toString());
				if (dpt.main() != 2 && dpt.main() != 3 && dpt.main() != 18 && dpt.main() != 1 && dpt.main() != 17) {
					assertTrue(code + " " + hex(data) + " -> " + value + " -> " + hex(again), Arrays.equals(data, again));
				}
			}
			assertTrue(code + " decodes nothing", count > 0);
		}
	}

	public void testFloat() {
		for (String code : FLOAT) {
			Dpt dpt = Dpt.parse(code);
			for (int n = 0; n < 0x10000; n++) {
				byte[] data = data(n, 2);
				Object value;
				try {
					value = DatapointValues.decode(dpt, data);
				} catch (DatapointValues.Refusal ex) {
					continue;
				}
				byte[] again = DatapointValues.encode(dpt, value);
				assertFalse(code + " " + value + " encodes the invalid code", hex(again).equals("7fff"));
				assertEquals(code + " " + hex(data) + " -> " + value + " -> " + hex(again),
					((Number) value).doubleValue(), ((Number) DatapointValues.decode(dpt, again)).doubleValue(), 1e-9);
			}
		}
	}

	private static byte[] data(int n, int octets) {
		byte[] result = new byte[octets];
		for (int k = 0; k < octets; k++) {
			result[octets - 1 - k] = (byte) (n >> (8 * k));
		}
		return result;
	}

	private static String hex(byte[] data) {
		return HexFormat.of().formatHex(data);
	}

	public static Test suite() {
		return new TestSuite(TestDatapointValuesProbe.class);
	}

}
