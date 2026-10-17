/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.programming.download.CalimeroWriter;

/**
 * The one write path to the bus (haumacher/ehome #7): of the sources using Calimero, only
 * {@link CalimeroWriter} calls a service that changes a device, so that its switch
 * ({@link CalimeroWriter#WRITE_ENABLED}) decides whether the application writes to the bus at all.
 */
@SuppressWarnings("javadoc")
public class TestBusWritePath extends TestCase {

	/** Calimero services changing a device or the bus configuration. */
	private static final Pattern WRITES = Pattern.compile(
		"\\.(writeMemory|writeProperty|restart|writeAddress|writeDomainAddress|writeKey|writeNetworkParameter"
			+ "|writeSystemNetworkParameter|setProgrammingMode|callFunctionProperty|authorize|sendWait|write)\\s*\\(");

	public void testOneWritePath() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (String root : List.of("src/main/java", "src/test/java")) {
			try (Stream<Path> files = Files.walk(Path.of(root))) {
				for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
					String source = Files.readString(file, StandardCharsets.UTF_8);
					if (!source.contains("import tuwien.auto.calimero")) {
						continue;
					}
					if (file.getFileName().toString().equals(CalimeroWriter.class.getSimpleName() + ".java")) {
						continue;
					}
					if (virtualDeviceTest(file)) {
						// A programming tool's session against the virtual device (haumacher/ehome #35):
						// the tests of that package reach only the loopback address (VirtualTesting).
						continue;
					}
					if (WRITES.matcher(source).find()) {
						offenders.add(file.toString());
					}
				}
			}
		}
		assertEquals("Calimero writes outside CalimeroWriter", List.of(), offenders);
	}

	/**
	 * Whether the file is a test of the virtual device, which acts as a programming tool against a
	 * virtual interface on the loopback address only ({@link test.de.haumacher.ehome.virtual.VirtualTesting}).
	 */
	private static boolean virtualDeviceTest(Path file) {
		return file.toString().replace('\\', '/').startsWith("src/test/java/test/de/haumacher/ehome/virtual/");
	}

	/**
	 * E-Home's own transport connection ({@link de.haumacher.ehome.knx.transport.TransportClient}) sends
	 * what it is given: the application services changing a device are built only by
	 * {@link CalimeroWriter} (and recognized by the virtual device, which answers them).
	 */
	public void testWriteServicesOnlyInWriter() throws IOException {
		Pattern writes = Pattern.compile("Tpdu\\.(MEMORY_WRITE|PROPERTY_WRITE|RESTART)\\b|\\.requestAndEnd\\s*\\(");
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
				String name = file.toString().replace('\\', '/');
				if (name.endsWith("/" + CalimeroWriter.class.getSimpleName() + ".java")
					|| name.contains("/de/haumacher/ehome/virtual/")) {
					continue;
				}
				if (writes.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
					offenders.add(file.toString());
				}
			}
		}
		assertEquals("Write services built outside CalimeroWriter", List.of(), offenders);
	}

	public void testWritesSwitchedOffByDefault() {
		assertFalse(CalimeroWriter.writeEnabled());
		try {
			CalimeroWriter.open(java.net.InetSocketAddress.createUnresolved("192.0.2.1", 3671), 0x1305);
			fail("Writing must be refused.");
		} catch (IllegalStateException ex) {
			// Expected.
		} catch (Exception ex) {
			fail("Refused without connecting expected: " + ex);
		}
	}

	public static Test suite() {
		return new TestSuite(TestBusWritePath.class);
	}

}
