/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.tooling.trace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The text format of a trace of the virtual device: one line per service, readable by a person and
 * read back by the tests.
 *
 * <pre>
 * # E-Home virtual device trace
 * # device: 1.3.5 (P-0353-0_DI-2)
 * # mask: 0705
 * 2026-10-17T13:30:01.120Z &gt; 15.15.250 1.3.5 T_Connect
 * 2026-10-17T13:30:01.135Z &gt; 15.15.250 1.3.5 A_DeviceDescriptor_Read seq=0 type=0
 * 2026-10-17T13:30:01.151Z &lt; 1.3.5 15.15.250 A_DeviceDescriptor_Response seq=0 type=0 data=0705
 * 2026-10-17T13:30:01.170Z &gt; 15.15.250 1.3.5 A_Memory_Write seq=5 address=4000 count=2 data=0102
 * </pre>
 *
 * <ul>
 * <li>A line starting with <code>#</code> is a comment; the comments before the first service are
 * the header, <code>key: value</code>.</li>
 * <li>A service: the time (ISO-8601, UTC), the direction (<code>&gt;</code> received by the device,
 * <code>&lt;</code> sent by it, <code>*</code> an event of the device itself such as a timeout of
 * its transport layer, with the device as sender and its partner as receiver), the sender and the receiver (individual addresses
 * area.line.device, <code>0/0/0</code> for a broadcast), the service, then its parameters as
 * <code>name=value</code>: <code>seq</code> the sequence number in a connection, the fields of
 * {@link TraceEntry} (the memory address and an APCI hexadecimal, everything else decimal), and
 * <code>data</code> last, hexadecimal.</li>
 * <li>A negative response carries <code>count=0</code> and no data.</li>
 * </ul>
 */
public class TraceFormat {

	/** The first line of a trace. */
	public static final String TITLE = "# E-Home virtual device trace";

	/** The parameter of the sequence number. */
	public static final String SEQUENCE = "seq";

	/** The parameter of the data. */
	public static final String DATA = "data";

	/** The receiver of a broadcast. */
	public static final String BROADCAST = "0/0/0";

	private static final HexFormat HEX = HexFormat.of().withUpperCase();

	/**
	 * A trace read from text.
	 *
	 * @param header
	 *        The header entries, by key.
	 * @param entries
	 *        The services, in their order.
	 */
	public record Trace(Map<String, String> header, List<TraceEntry> entries) {
		// Value.
	}

	/**
	 * The line of a service.
	 */
	public static String format(TraceEntry entry) {
		StringBuilder out = new StringBuilder();
		out.append(entry.time()).append(' ').append(entry.direction().symbol()).append(' ');
		out.append(individual(entry.source())).append(' ');
		out.append(entry.destination() == 0 ? BROADCAST : individual(entry.destination())).append(' ');
		out.append(entry.service());
		if (entry.sequence() != null) {
			out.append(' ').append(SEQUENCE).append('=').append(entry.sequence());
		}
		for (Map.Entry<String, String> field : entry.fields().entrySet()) {
			out.append(' ').append(field.getKey()).append('=').append(field.getValue());
		}
		byte[] data = entry.data();
		if (data.length > 0) {
			out.append(' ').append(DATA).append('=').append(HEX.formatHex(data));
		}
		return out.toString();
	}

	/**
	 * A header line.
	 */
	public static String header(String key, String value) {
		return "# " + key + ": " + value;
	}

	/**
	 * Reads a trace.
	 *
	 * @throws IOException
	 *         If reading fails or a line is not a service of this format.
	 */
	public static Trace read(Reader in) throws IOException {
		Map<String, String> header = new LinkedHashMap<>();
		List<TraceEntry> entries = new ArrayList<>();
		BufferedReader lines = new BufferedReader(in);
		int number = 0;
		String line;
		while ((line = lines.readLine()) != null) {
			number++;
			String text = line.strip();
			if (text.isEmpty()) {
				continue;
			}
			if (text.startsWith("#")) {
				int colon = text.indexOf(':');
				if (entries.isEmpty() && colon > 0) {
					header.put(text.substring(1, colon).strip(), text.substring(colon + 1).strip());
				}
				continue;
			}
			try {
				entries.add(parse(text));
			} catch (IllegalArgumentException | DateTimeParseException ex) {
				throw new IOException("Line " + number + " is no service of a trace: " + ex.getMessage() + ": " + line, ex);
			}
		}
		return new Trace(header, entries);
	}

	/**
	 * Parses the line of a service.
	 *
	 * @throws IllegalArgumentException
	 *         If the line is not one.
	 */
	public static TraceEntry parse(String line) {
		String[] tokens = line.strip().split(" +");
		if (tokens.length < 5) {
			throw new IllegalArgumentException("too few parts");
		}
		Instant time = Instant.parse(tokens[0]);
		TraceEntry.Direction direction = TraceEntry.Direction.of(tokens[1]);
		if (direction == null) {
			throw new IllegalArgumentException("no direction: " + tokens[1]);
		}
		int source = parseIndividual(tokens[2]);
		int destination = BROADCAST.equals(tokens[3]) ? 0 : parseIndividual(tokens[3]);
		String service = tokens[4];
		Integer sequence = null;
		Map<String, String> fields = new LinkedHashMap<>();
		byte[] data = new byte[0];
		for (int n = 5; n < tokens.length; n++) {
			int equals = tokens[n].indexOf('=');
			if (equals <= 0) {
				throw new IllegalArgumentException("no parameter: " + tokens[n]);
			}
			String name = tokens[n].substring(0, equals);
			String value = tokens[n].substring(equals + 1);
			if (SEQUENCE.equals(name)) {
				sequence = Integer.valueOf(value);
			} else if (DATA.equals(name)) {
				data = HEX.parseHex(value);
			} else {
				fields.put(name, value);
			}
		}
		return new TraceEntry(time, direction, source, destination, service, sequence, fields, data);
	}

	/**
	 * An individual address as area.line.device.
	 */
	public static String individual(int address) {
		return (address >>> 12 & 0x0F) + "." + (address >>> 8 & 0x0F) + "." + (address & 0xFF);
	}

	/**
	 * Parses an individual address area.line.device.
	 */
	public static int parseIndividual(String text) {
		String[] parts = text.split("\\.");
		if (parts.length != 3) {
			throw new IllegalArgumentException("no individual address: " + text);
		}
		int area = Integer.parseInt(parts[0]);
		int line = Integer.parseInt(parts[1]);
		int device = Integer.parseInt(parts[2]);
		if (area > 15 || line > 15 || device > 255 || area < 0 || line < 0 || device < 0) {
			throw new IllegalArgumentException("no individual address: " + text);
		}
		return area << 12 | line << 8 | device;
	}

	/**
	 * Bytes as hexadecimal text without separators.
	 */
	public static String hex(byte[] data) {
		return HEX.formatHex(data);
	}

}
