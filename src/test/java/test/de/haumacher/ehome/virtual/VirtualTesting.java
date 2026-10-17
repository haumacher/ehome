/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.virtual.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.server.VirtualInterface;
import de.haumacher.ehome.virtual.server.VirtualInterface.Endpoint;
import de.haumacher.ehome.virtual.trace.TraceFormat;
import de.haumacher.ehome.virtual.trace.TraceStore;

/**
 * What the tests of the virtual device share: a virtual interface on the loopback address only, at
 * a free port, without discovery, its traces below <code>target/virtual/</code>; writing switched on
 * for the duration of a test body only.
 */
@SuppressWarnings("javadoc")
public class VirtualTesting {

	/** The base directory of the tests' traces and server files. */
	public static final File OUTPUT = new File("target/virtual");

	/**
	 * Something run with writing to the bus switched on.
	 */
	public interface Body {
		void run() throws Exception;
	}

	/**
	 * A manager whose traces go to <code>target/virtual/&lt;name&gt;</code>.
	 */
	public static VirtualInterfaceManager manager(String name) {
		File base = new File(OUTPUT, name);
		return new VirtualInterfaceManager(new TraceStore(new File(base, "traces")), new File(base, "server"));
	}

	/**
	 * An endpoint on the loopback address at a free port, without discovery.
	 */
	public static Endpoint loopback() throws IOException {
		InetAddress loopback = InetAddress.getByName("127.0.0.1");
		try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
			return new Endpoint(loopback, socket.getLocalPort(), false);
		}
	}

	/**
	 * The gateway address of an endpoint, for {@link CalimeroWriter#open(InetSocketAddress, int)}.
	 */
	public static InetSocketAddress gateway(Endpoint endpoint) {
		assertLoopback(endpoint.address());
		return new InetSocketAddress(endpoint.address(), endpoint.port());
	}

	/**
	 * Runs the body with {@link CalimeroWriter#WRITE_ENABLED} set, and resets it afterwards.
	 */
	public static void writing(Body body) throws Exception {
		String before = System.getProperty(CalimeroWriter.WRITE_ENABLED);
		System.setProperty(CalimeroWriter.WRITE_ENABLED, "true");
		try {
			body.run();
		} finally {
			if (before == null) {
				System.clearProperty(CalimeroWriter.WRITE_ENABLED);
			} else {
				System.setProperty(CalimeroWriter.WRITE_ENABLED, before);
			}
		}
	}

	/**
	 * Reads a trace file.
	 */
	public static TraceFormat.Trace read(File file) throws IOException {
		try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			return TraceFormat.read(in);
		}
	}

	/**
	 * Waits (at most 10 s) until the device has received the given service.
	 */
	public static void awaitService(VirtualInterface running, String service) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (running.recorder().entries().stream().noneMatch(e -> e.incoming() && service.equals(e.service()))) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("The device did not receive " + service + ".");
			}
			Thread.sleep(20);
		}
	}

	private static void assertLoopback(InetAddress address) {
		if (!address.isLoopbackAddress()) {
			throw new AssertionError("The tests of the virtual device must use the loopback address only: " + address);
		}
	}

}
