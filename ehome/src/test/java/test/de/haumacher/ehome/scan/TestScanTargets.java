/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import test.de.haumacher.ehome.programming.MockDevice;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.scan.BusScanner;
import de.haumacher.ehome.scan.FoundDevice;
import de.haumacher.ehome.scan.I18NConstants;
import de.haumacher.ehome.scan.LineInputControlProvider;
import de.haumacher.ehome.scan.ScanTarget;

/**
 * The one choice of a scan of the installation (haumacher/ehome #182): each {@link ScanTarget}
 * runs its search alone and reads only the devices it found, and the line is entered as
 * <code>area.line</code>.
 */
@SuppressWarnings("javadoc")
public class TestScanTargets extends TestCase {

	private static final int ACTUATOR = 0x1305;

	public void testProgrammingModeOnly() throws Exception {
		RecordingBus bus = new RecordingBus();
		List<String> phases = new ArrayList<>();
		BusScanner.Request request = BusScanner.Request.of(ScanTarget.PROGRAMMING_MODE);
		BusScanner.Result result = BusScanner.scan(bus, request, listener(phases));

		assertEquals("Neither the line nor the routers are scanned.",
			List.of("programmingMode", "device 1.3.5"), bus.calls());
		assertEquals(BusScanner.phases(request), phases);
		assertEquals(List.of(BusScanner.PHASE_PROGRAMMING, BusScanner.PHASE_DEVICES), phases);
		assertEquals(List.of(Integer.valueOf(ACTUATOR)), result.inProgrammingMode());
		assertEquals(List.of("1.3.5"), addresses(result));
	}

	public void testLineOnly() throws Exception {
		RecordingBus bus = new RecordingBus();
		List<String> phases = new ArrayList<>();
		BusScanner.Request request = BusScanner.Request.of(new BusScanner.Line(1, 3));
		BusScanner.Result result = BusScanner.scan(bus, request, listener(phases));

		assertEquals(List.of("scanLine 1.3", "device 1.3.1", "device 1.3.5", "device 1.3.50"), bus.calls());
		assertEquals(BusScanner.phases(request), phases);
		assertEquals(List.of(BusScanner.PHASE_LINE, BusScanner.PHASE_DEVICES), phases);
		assertEquals(List.of(), result.inProgrammingMode());
		assertEquals(List.of("1.3.5", "1.3.50"), addresses(result));
		// The gateway answers a connection through its own tunnel, but not its reads.
		assertEquals(result.notes().toString(), 1, result.notes().size());
	}

	public void testRoutersOnly() throws Exception {
		RecordingBus bus = new RecordingBus();
		List<String> phases = new ArrayList<>();
		BusScanner.Request request = BusScanner.Request.of(ScanTarget.ROUTERS);
		BusScanner.scan(bus, request, listener(phases));

		assertEquals(List.of("scanRouters", "device 1.3.1"), bus.calls());
		assertEquals(BusScanner.phases(request), phases);
		assertEquals(List.of(BusScanner.PHASE_ROUTERS, BusScanner.PHASE_DEVICES), phases);
	}

	public void testGatewayOnly() throws Exception {
		RecordingBus bus = new RecordingBus();
		List<String> phases = new ArrayList<>();
		BusScanner.Request request = BusScanner.Request.of(ScanTarget.GATEWAY);
		BusScanner.Result result = BusScanner.scan(bus, request, listener(phases));

		assertEquals("Read through local device management only.", List.of("gatewayProperties"), bus.calls());
		assertEquals(BusScanner.phases(request), phases);
		assertEquals(List.of(BusScanner.PHASE_GATEWAY), phases);
		assertEquals(result.notes().toString(), 0, result.notes().size());
		assertEquals(List.of("1.3.1"), addresses(result));
		assertEquals(FoundDevice.Source.LOCAL, result.devices().get(0).source());
	}

	public void testRequestShape() {
		assertNull(BusScanner.Request.of(ScanTarget.ROUTERS).line());
		assertEquals(new BusScanner.Line(1, 1), BusScanner.Request.of(new BusScanner.Line(1, 1)).line());
		assertIllegal(() -> new BusScanner.Request(ScanTarget.LINE, null));
		assertIllegal(() -> new BusScanner.Request(ScanTarget.PROGRAMMING_MODE, new BusScanner.Line(1, 1)));
		assertIllegal(() -> new BusScanner.Request(null, null));
		assertIllegal(() -> new BusScanner.Line(16, 0));
		assertIllegal(() -> new BusScanner.Line(0, -1));
	}

	public void testLineParser() {
		assertEquals(new BusScanner.Line(1, 1), BusScanner.Line.parse("1.1"));
		assertEquals(new BusScanner.Line(0, 0), BusScanner.Line.parse("0.0"));
		assertEquals(new BusScanner.Line(15, 15), BusScanner.Line.parse("15.15"));
		assertEquals(new BusScanner.Line(1, 3), BusScanner.Line.parse(" 1.3 "));
		assertEquals("15.15", BusScanner.Line.parse("15.15").toString());

		for (String refused : new String[] { "1", "16.1", "1.16", "a.b", "1.1.1", "", " ", "1.", ".1", "-1.1",
			"+1.1", "1,1", "100.1" }) {
			assertNull(refused, BusScanner.Line.parse(refused));
		}
		assertNull(BusScanner.Line.parse(null));
	}

	/**
	 * The field of the line refuses with the reason of the scan's veto; an empty field is a missing
	 * value, which the mandatory input reports.
	 */
	public void testLineInputProblem() {
		assertNull(LineInputControlProvider.problem("1.1"));
		assertNull(LineInputControlProvider.problem(" 15.15 "));
		assertNull(LineInputControlProvider.problem(""));
		assertNull(LineInputControlProvider.problem(null));
		assertEquals(I18NConstants.ERROR_NO_LINE, LineInputControlProvider.problem("1.16"));
		assertEquals(I18NConstants.ERROR_NO_LINE, LineInputControlProvider.problem("1"));
		assertEquals(I18NConstants.ERROR_NO_LINE, LineInputControlProvider.problem("a.b"));
	}

	private static void assertIllegal(Runnable creation) {
		try {
			creation.run();
			fail("Expected a refusal.");
		} catch (IllegalArgumentException ex) {
			// Expected.
		}
	}

	private static List<String> addresses(BusScanner.Result result) {
		return result.devices().stream().map(FoundDevice::addressText).toList();
	}

	private static BusScanner.Listener listener(List<String> phases) {
		return new BusScanner.Listener() {
			@Override
			public void phase(String name) {
				phases.add(name);
			}

			@Override
			public void message(ResKey text) {
				// Ignore.
			}

			@Override
			public void progress(int done, int total) {
				// Ignore.
			}
		};
	}

	/**
	 * The test line 1.3 of {@link TestBusScan.FakeBus}, telling which of its services a scan used;
	 * the actuator is in programming mode.
	 */
	static class RecordingBus extends TestBusScan.FakeBus {

		private final List<String> _calls = new ArrayList<>();

		List<String> calls() {
			return _calls;
		}

		@Override
		public List<Integer> scanLine(int area, int line, Progress progress) {
			_calls.add("scanLine " + area + "." + line);
			return super.scanLine(area, line, progress);
		}

		@Override
		public List<Integer> scanRouters(Progress progress) {
			_calls.add("scanRouters");
			return super.scanRouters(progress);
		}

		@Override
		public List<Integer> programmingMode() {
			_calls.add("programmingMode");
			return List.of(Integer.valueOf(ACTUATOR));
		}

		@Override
		public MockDevice device(int address) throws BusException {
			_calls.add("device " + FoundDevice.formatAddress(address));
			return super.device(address);
		}

		@Override
		public LocalProperties gatewayProperties() {
			_calls.add("gatewayProperties");
			return super.gatewayProperties();
		}
	}

	public static Test suite() {
		return new TestSuite(TestScanTargets.class);
	}

}
