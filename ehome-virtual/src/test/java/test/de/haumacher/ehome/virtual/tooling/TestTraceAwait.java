/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.AssertionFailedError;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry.Direction;
import de.haumacher.ehome.virtual.tooling.trace.TraceRecorder;

/**
 * The wait for the end of the device's connection (haumacher/ehome #215): the tool's disconnect
 * after a restart reaches the device only after the tool has closed its tunnel, and a trace read
 * when the download returned ended without it. The wait ({@link TraceRecorder#await}) returns when
 * the disconnect is recorded, however late it comes. A device that ends the connection after a
 * restart and the tool cross their disconnects: either is recorded first, and the wait for such a
 * device waits for the device's own.
 */
@SuppressWarnings("javadoc")
public class TestTraceAwait extends TestCase {

	private static final int TOOL = 0x13FF;

	private static final int DEVICE = 0x1305;

	public void testDisconnectRecordedLate() throws Exception {
		TraceRecorder recorder = new TraceRecorder(null, Map.of());
		recorder.record(entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.T_CONNECT));
		recorder.record(entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.RESTART));
		recorder.record(entry(Direction.SENT, DEVICE, TOOL, TraceEntry.T_ACK));
		assertFalse(VirtualTesting.connectionEnded(recorder.entries()));

		// The disconnect arrives only while the test waits for it.
		Thread waiter = Thread.currentThread();
		Thread late = new Thread(() -> {
			while (waiter.getState() != Thread.State.TIMED_WAITING) {
				Thread.onSpinWait();
			}
			recorder.record(entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.T_DISCONNECT));
		}, "late disconnect");
		late.start();
		assertTrue(recorder.await(VirtualTesting::connectionEnded, Duration.ofSeconds(5)));
		late.join();
		List<TraceEntry> entries = recorder.entries();
		assertEquals(TraceEntry.T_DISCONNECT, entries.get(entries.size() - 1).service());
	}

	public void testConnectAgain() throws Exception {
		TraceRecorder recorder = new TraceRecorder(null, Map.of());
		recorder.record(entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.T_CONNECT));
		recorder.record(entry(Direction.SENT, DEVICE, TOOL, TraceEntry.T_DISCONNECT));
		assertTrue(VirtualTesting.connectionEnded(recorder.entries()));

		// A connection after it: open until its own disconnect.
		recorder.record(entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.T_CONNECT));
		assertFalse(recorder.await(VirtualTesting::connectionEnded, Duration.ofMillis(50)));
	}

	public void testCrossingDisconnects() {
		TransportTiming disconnects = TransportTiming.DEFAULT.withDisconnectOnRestart(true);
		List<TraceEntry> toolFirst = List.of(entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.T_CONNECT),
			entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.RESTART), entry(Direction.SENT, DEVICE, TOOL, TraceEntry.T_ACK),
			entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.T_DISCONNECT));
		// The tool's disconnect alone does not end the wait for the device's.
		assertTrue(VirtualTesting.connectionEnded(toolFirst));
		assertFalse(VirtualTesting.connectionEnded(toolFirst, true));

		List<TraceEntry> crossed = new ArrayList<>(toolFirst);
		crossed.add(entry(Direction.SENT, DEVICE, TOOL, TraceEntry.T_DISCONNECT));
		assertTrue(VirtualTesting.connectionEnded(crossed, true));
		TestVirtualDownload.assertRestartEndsAtOnce("crossed", crossed, disconnects);

		List<TraceEntry> deviceFirst = new ArrayList<>(toolFirst.subList(0, 3));
		deviceFirst.add(entry(Direction.SENT, DEVICE, TOOL, TraceEntry.T_DISCONNECT));
		deviceFirst.add(entry(Direction.RECEIVED, TOOL, DEVICE, TraceEntry.T_DISCONNECT));
		TestVirtualDownload.assertRestartEndsAtOnce("device first", deviceFirst, disconnects);

		// A device that does not end the connection after a restart must not send a disconnect.
		TestVirtualDownload.assertRestartEndsAtOnce("tool", toolFirst, TransportTiming.DEFAULT);
		try {
			TestVirtualDownload.assertRestartEndsAtOnce("device", deviceFirst, TransportTiming.DEFAULT);
			fail("The device's disconnect was accepted for a device that keeps the connection.");
		} catch (AssertionFailedError ex) {
			assertEquals("device: the device ended the connection after the restart", ex.getMessage());
		}
		try {
			TestVirtualDownload.assertRestartEndsAtOnce("tool", toolFirst, disconnects);
			fail("A device that ends the connection did not.");
		} catch (AssertionFailedError ex) {
			assertEquals("tool: the device did not end the connection after the restart", ex.getMessage());
		}
	}

	private static TraceEntry entry(Direction direction, int source, int destination, String service) {
		return new TraceEntry(Instant.now(), direction, source, destination, service, null, Map.of(), null);
	}

	public static Test suite() {
		return new TestSuite(TestTraceAwait.class);
	}

}
