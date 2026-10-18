/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.Random;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.runtime.DnsMessage;
import de.haumacher.ehome.runtime.DnsMessage.DnsException;

/**
 * Probe of the mDNS message parser (haumacher/ehome #42) with input nobody wrote by hand: random
 * datagrams and random corruptions of a valid header either parse or are refused with a
 * {@link DnsException}, never with another exception or a hang, since the search reads whatever the
 * network sends.
 */
@SuppressWarnings("javadoc")
public class TestDnsMessageProbe extends TestCase {

	public void testRandomInput() {
		Random random = new Random(42);
		for (int n = 0; n < 20000; n++) {
			byte[] data = new byte[random.nextInt(200)];
			random.nextBytes(data);
			if (data.length >= 12 && random.nextBoolean()) {
				// A plausible header: few questions and records, so the parser goes deeper.
				data[4] = 0;
				data[5] = (byte) random.nextInt(3);
				data[6] = 0;
				data[7] = (byte) random.nextInt(5);
				data[8] = 0;
				data[9] = 0;
				data[10] = 0;
				data[11] = (byte) random.nextInt(4);
			}
			try {
				DnsMessage.read(data, data.length);
			} catch (DnsException ex) {
				// Refused, as expected for most of them.
			} catch (RuntimeException ex) {
				fail("Input " + n + " (" + data.length + " bytes) fails with " + ex);
			}
		}
	}

	public static Test suite() {
		return new TestSuite(TestDnsMessageProbe.class);
	}

}
