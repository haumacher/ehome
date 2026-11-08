/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.knx.SerialNumberText;

/**
 * Test of {@link SerialNumberText} (haumacher/ehome #120): the serial number of a device as KNX
 * writes it, from the Base64 form ETS stores in a project.
 */
@SuppressWarnings("javadoc")
public class TestSerialNumberText extends TestCase {

	public void testText() {
		// The Tastsensor of the test system, as the bus scan read it on 2026-11-08.
		assertEquals("000A:2A4A2711", SerialNumberText.text("AAoqSicR"));
		assertEquals("0083:65AEF06D", SerialNumberText.text("AINlrvBt"));
	}

	public void testNotASerialNumber() {
		assertNull(SerialNumberText.text(null));
		assertNull(SerialNumberText.text(" "));
		assertEquals("not base64!", SerialNumberText.text("not base64!"));
		// Base64, but not six bytes.
		assertEquals("AAo=", SerialNumberText.text("AAo="));
	}

	public static Test suite() {
		return new TestSuite(TestSerialNumberText.class);
	}

}
