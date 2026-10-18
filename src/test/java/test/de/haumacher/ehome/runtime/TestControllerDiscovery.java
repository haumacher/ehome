/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import junit.framework.TestSuite;
import junit.framework.TestCase;

import de.haumacher.ehome.runtime.DiscoverControllersAction;
import de.haumacher.ehome.runtime.DnsMessage;
import de.haumacher.ehome.runtime.DnsMessage.DnsException;
import de.haumacher.ehome.runtime.DnsMessage.Question;
import de.haumacher.ehome.runtime.DnsMessage.Record;
import de.haumacher.ehome.runtime.MdnsBrowser;
import de.haumacher.ehome.runtime.MdnsBrowser.Target;

/**
 * The discovery of HomeControl controllers over multicast DNS (haumacher/ehome #42): the DNS
 * messages (a response as a responder writes it, with compressed names; malformed ones), and the
 * search against responders announcing <code>_homecontrol._tcp</code> in-process, on the multicast
 * DNS group over the loopback interface (on a free port instead of 5353, which the system's own
 * responder holds).
 *
 * <p>
 * Where the loopback interface cannot join the group, the responders answer on a unicast port of
 * <code>127.0.0.1</code> instead: the messages and the follow-up query are then tested, the
 * multicast transport is not.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestControllerDiscovery extends TestCase {

	private static final String TYPE = DiscoverControllersAction.SERVICE_TYPE;

	/**
	 * A response as the ESP-IDF responder writes it: PTR, SRV, TXT and A, the names compressed.
	 */
	public void testParseCompressedResponse() throws Exception {
		Raw out = new Raw();
		out.u16(0).u16(0x8400).u16(0).u16(4).u16(0).u16(0);
		int type = out.size();
		out.labels("_homecontrol", "_tcp", "local").end();
		out.u16(DnsMessage.PTR).u16(1).u32(4500);
		int ptrData = out.size();
		out.u16(0);
		int instance = out.size();
		out.labels("HomeControl").pointer(type);
		out.patchLength(ptrData);

		out.pointer(instance).u16(DnsMessage.SRV).u16(0x8001).u32(120);
		int srvData = out.size();
		out.u16(0).u16(0).u16(0).u16(80);
		int host = out.size();
		out.labels("homecontrol-a1b2c3").pointer(type + 1 + "_homecontrol".length() + 1 + "_tcp".length());
		out.patchLength(srvData);

		out.pointer(instance).u16(DnsMessage.TXT).u16(0x8001).u32(4500);
		int txtData = out.size();
		out.u16(0).text("version=0.3.1").text("build=v0.3.1-12-gabcdef").text("Flag");
		out.patchLength(txtData);

		out.pointer(host).u16(DnsMessage.A).u16(0x8001).u32(120);
		out.u16(4).bytes(192, 0, 2, 10);

		DnsMessage message = DnsMessage.read(out.toByteArray(), out.size());
		assertTrue(message.isResponse());
		List<Record> records = message.records();
		assertEquals(4, records.size());
		assertEquals("HomeControl._homecontrol._tcp.local", records.get(0).target());
		assertEquals("homecontrol-a1b2c3.local", records.get(1).target());
		assertEquals(80, records.get(1).port());
		assertEquals(Map.of("version", "0.3.1", "build", "v0.3.1-12-gabcdef", "flag", ""), records.get(2).attributes());
		assertEquals("192.0.2.10", records.get(3).address().getHostAddress());
		assertEquals("homecontrol-a1b2c3.local", records.get(3).name());

		// Truncated anywhere: refused, never read past the end.
		byte[] bytes = out.toByteArray();
		for (int length = 0; length < bytes.length; length++) {
			try {
				DnsMessage.read(bytes, length);
				fail("Truncated message read at length " + length);
			} catch (DnsException ex) {
				// Expected.
			}
		}
	}

	public void testPointerLoop() {
		Raw out = new Raw();
		out.u16(0).u16(0).u16(1).u16(0).u16(0).u16(0);
		out.pointer(12);
		out.u16(DnsMessage.PTR).u16(1);
		try {
			DnsMessage.read(out.toByteArray(), out.size());
			fail("A name pointing to itself was read.");
		} catch (DnsException ex) {
			// Expected.
		}
	}

	public void testWriteRead() throws Exception {
		DnsMessage message = new DnsMessage(7, DnsMessage.FLAGS_RESPONSE,
			List.of(new Question(TYPE, DnsMessage.PTR, true)),
			List.of(Record.ptr(TYPE, 4500, "HomeControl." + TYPE),
				Record.srv("HomeControl." + TYPE, 120, "homecontrol-a1b2c3.local", 8080),
				Record.txt("HomeControl." + TYPE, 4500, List.of("version=1.0")),
				Record.address("homecontrol-a1b2c3.local", 120, InetAddress.getByName("192.0.2.11"))));
		byte[] bytes = message.write();
		DnsMessage read = DnsMessage.read(bytes, bytes.length);
		assertEquals(7, read.id());
		assertEquals(message.questions(), read.questions());
		assertEquals(4, read.records().size());
		assertEquals("HomeControl." + TYPE, read.records().get(0).target());
		assertEquals(8080, read.records().get(1).port());
		assertEquals("1.0", read.records().get(2).attributes().get("version"));
		assertEquals("192.0.2.11", read.records().get(3).address().getHostAddress());
	}

	/**
	 * Two controllers announce themselves; one answers with all records, the other only with its
	 * name, so that the search asks for its host, attributes and address in a second query. A
	 * responder of another service type is not listed.
	 */
	public void testDiscover() throws Exception {
		NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
		try (FakeMdnsResponder responder = FakeMdnsResponder.start(loopback, 0)) {
			responder.announce(new FakeMdnsResponder.Announcement("HomeControl", TYPE, "homecontrol-a1b2c3.local", "127.0.0.1", 80,
				List.of("version=0.3.1", "build=v0.3.1-12-gabcdef"), true));
			responder.announce(new FakeMdnsResponder.Announcement("HomeControl (2)", TYPE, "homecontrol-d4e5f6.local", "127.0.0.2", 8080,
				List.of("version=0.4.0"), false));
			responder.announce(new FakeMdnsResponder.Announcement("Printer", "_ipp._tcp.local", "printer.local", "127.0.0.3", 631,
				List.of("version=9"), true));

			List<Map<String, Object>> rows = DiscoverControllersAction
				.rows(MdnsBrowser.browse(TYPE, 600, List.of(new Target(responder.target(), loopback))));
			assertEquals(2, rows.size());
			Map<String, Object> first = rows.get(0);
			assertEquals("homecontrol-a1b2c3", first.get("name"));
			assertEquals("HomeControl", first.get("instance"));
			assertEquals("homecontrol-a1b2c3.local", first.get("hostName"));
			assertEquals("127.0.0.1", first.get("host"));
			assertEquals(Integer.valueOf(80), first.get("port"));
			assertEquals("0.3.1", first.get("version"));
			assertEquals("v0.3.1-12-gabcdef", first.get("build"));

			Map<String, Object> second = rows.get(1);
			assertEquals("homecontrol-d4e5f6", second.get("name"));
			assertEquals("HomeControl (2)", second.get("instance"));
			assertEquals("127.0.0.2", second.get("host"));
			assertEquals(Integer.valueOf(8080), second.get("port"));
			assertEquals("8080", second.get("portText"));
			assertEquals("0.4.0", second.get("version"));
			assertEquals("", second.get("build"));
			assertTrue("The second controller needed a follow-up query.", responder.queries() >= 2);
		}
	}

	/**
	 * No responder: an empty list after the window, no failure.
	 */
	public void testNothingFound() throws Exception {
		NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
		try (FakeMdnsResponder responder = FakeMdnsResponder.start(loopback, 0)) {
			assertEquals(List.of(),
				MdnsBrowser.browse(TYPE, 200, List.of(new Target(responder.target(), loopback))));
		}
	}

	/**
	 * Writes DNS messages byte by byte, for messages {@link DnsMessage} would not write that way.
	 */
	static final class Raw extends ByteArrayOutputStream {

		Raw u16(int value) {
			write(value >>> 8);
			write(value);
			return this;
		}

		Raw u32(long value) {
			return u16((int) (value >>> 16)).u16((int) value);
		}

		Raw labels(String... labels) {
			for (String label : labels) {
				byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
				write(bytes.length);
				write(bytes, 0, bytes.length);
			}
			return this;
		}

		Raw end() {
			write(0);
			return this;
		}

		Raw pointer(int offset) {
			return u16(0xC000 | offset);
		}

		Raw text(String text) {
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			write(bytes.length);
			write(bytes, 0, bytes.length);
			return this;
		}

		Raw bytes(int... values) {
			for (int value : values) {
				write(value);
			}
			return this;
		}

		/**
		 * Sets the data length written at the given position to the bytes written after it.
		 */
		void patchLength(int at) {
			int length = size() - at - 2;
			buf[at] = (byte) (length >>> 8);
			buf[at + 1] = (byte) length;
		}
	}

	public static Test suite() {
		return new TestSuite(TestControllerDiscovery.class);
	}

}
