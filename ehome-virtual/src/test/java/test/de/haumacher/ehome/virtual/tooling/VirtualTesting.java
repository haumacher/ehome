/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import test.de.haumacher.ehome.simulation.LoopbackEndpoints;

import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.settings.OperatorSettings;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;
import de.haumacher.ehome.virtual.tooling.trace.TraceFormat;
import de.haumacher.ehome.virtual.tooling.trace.TraceStore;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;

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
	 *
	 * @see LoopbackEndpoints#loopback()
	 */
	public static Endpoint loopback() throws IOException {
		return LoopbackEndpoints.loopback();
	}

	/**
	 * The gateway address of an endpoint, for {@link CalimeroWriter#open(InetSocketAddress, int)}.
	 *
	 * @see LoopbackEndpoints#gateway(Endpoint)
	 */
	public static InetSocketAddress gateway(Endpoint endpoint) {
		return LoopbackEndpoints.gateway(endpoint);
	}

	/**
	 * Runs the body with writing to the bus switched on in the configuration
	 * ({@link OperatorSettings.Config#getProgrammingWriteEnabled()}, see
	 * {@link CalimeroWriter#writeEnabled()}), and resets it afterwards.
	 */
	public static void writing(Body body) throws Exception {
		OperatorSettings.Config settings = OperatorSettings.config();
		boolean before = settings.getProgrammingWriteEnabled();
		settings.setProgrammingWriteEnabled(true);
		try {
			body.run();
		} finally {
			settings.setProgrammingWriteEnabled(before);
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
		if (!running.recorder().await(entries -> entries.stream().anyMatch(e -> e.incoming() && service.equals(e.service())),
			AWAIT_LIMIT)) {
			throw new AssertionError("The device did not receive " + service + ".");
		}
	}

	/**
	 * Waits (at most 10 s) until the last connection of the device has ended: the trace holds a
	 * disconnect after its last connect, the device's or the tool's.
	 *
	 * <p>
	 * The tool sends its disconnect without waiting for its confirmation (after a restart, or when it
	 * closes the connection) and closes its tunnel; the frame travels through the server to the
	 * device asynchronously. An interface stopped before it arrived loses it, and the trace would end
	 * without it.
	 * </p>
	 */
	public static void awaitConnectionEnded(VirtualInterface running) throws InterruptedException {
		if (!running.recorder().await(VirtualTesting::connectionEnded, AWAIT_LIMIT)) {
			throw new AssertionError("The connection to the device did not end.");
		}
	}

	/**
	 * Waits (at most 10 s) until the last connection of the device has ended
	 * ({@link #awaitConnectionEnded(VirtualInterface)}) and, for a device that ends the connection
	 * after a restart ({@link TransportTiming#disconnectOnRestart()}), until its own disconnect after
	 * the last restart is recorded: the tool's disconnect may come first.
	 */
	public static void awaitConnectionEnded(VirtualInterface running, TransportTiming timing)
			throws InterruptedException {
		if (!running.recorder().await(entries -> connectionEnded(entries, timing.disconnectOnRestart()), AWAIT_LIMIT)) {
			throw new AssertionError("The connection to the device did not end.");
		}
	}

	/**
	 * Whether the trace holds a disconnect after its last connect.
	 */
	static boolean connectionEnded(List<TraceEntry> entries) {
		return connectionEnded(entries, false);
	}

	/**
	 * Whether the trace holds a disconnect after its last connect and, if the device ends the
	 * connection after a restart, the device's disconnect after a restart of that connection.
	 */
	static boolean connectionEnded(List<TraceEntry> entries, boolean deviceDisconnectsOnRestart) {
		boolean ended = false;
		boolean deviceEnded = false;
		for (int n = entries.size() - 1; n >= 0; n--) {
			TraceEntry entry = entries.get(n);
			String service = entry.service();
			if (TraceEntry.T_DISCONNECT.equals(service)) {
				ended = true;
				deviceEnded |= entry.outgoing();
			} else if (entry.incoming() && TraceEntry.RESTART.equals(service)) {
				return ended && (!deviceDisconnectsOnRestart || deviceEnded);
			} else if (TraceEntry.T_CONNECT.equals(service)) {
				return ended;
			}
		}
		return ended;
	}

	private static final Duration AWAIT_LIMIT = Duration.ofSeconds(10);

}
