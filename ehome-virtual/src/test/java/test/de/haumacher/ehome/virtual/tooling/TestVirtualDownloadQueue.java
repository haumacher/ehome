/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.virtual.tooling;

import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.gateway;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.loopback;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.manager;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.read;
import static test.de.haumacher.ehome.virtual.tooling.VirtualTesting.writing;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.layout.view.job.JobPhase;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadLock;
import de.haumacher.ehome.programming.download.DownloadQueue;
import de.haumacher.ehome.programming.download.DownloadQueue.Entry;
import de.haumacher.ehome.programming.download.DownloadQueue.State;
import de.haumacher.ehome.programming.download.DownloadQueueFunctions;
import de.haumacher.ehome.programming.download.ProgrammingFunctions;
import de.haumacher.ehome.virtual.server.Endpoint;
import de.haumacher.ehome.virtual.tooling.VirtualInterfaceManager;
import de.haumacher.ehome.virtual.tooling.device.MappedLoadStateRule;
import de.haumacher.ehome.virtual.tooling.device.ProjectDevices;
import de.haumacher.ehome.virtual.tooling.device.SimulatedDevice;
import de.haumacher.ehome.virtual.tooling.server.TransportTiming;
import de.haumacher.ehome.virtual.tooling.server.VirtualInterface;
import de.haumacher.ehome.virtual.tooling.trace.TraceEntry;

/**
 * The programming of several devices one after another (haumacher/ehome #200), into virtual devices
 * of <code>samples/KNX-Testsystem.knxproj</code> (the actuator, a copy of it and the push button), each behind a virtual interface of its own on the
 * loopback address.
 *
 * <p>
 * Three devices are downloaded in sequence (the traces do not overlap), each recorded singly, the
 * second one failing without stopping the third. A waiting device cancelled stays untouched; the
 * running download aborted stops before its next operation, unrecorded, and the queue goes on; a cancel
 * of the job aborts the running download and cancels the waiting ones. "All with changes" selects
 * exactly the devices with <code>downloadPending</code>, and the kind preset per device follows its
 * state. The queue holds the project's download lock while it runs.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVirtualDownloadQueue extends BasicTestCase {

	private static final int ROUTER = 0x1301;

	private static final int ACTUATOR = 0x1305;

	private static final int SENSOR = 0x1332;

	/**
	 * The virtual devices of a run, each behind an interface of its own.
	 */
	private static final class Bench implements AutoCloseable {

		final Map<TLObject, Endpoint> _endpoints = new HashMap<>();

		final Map<TLObject, VirtualInterface> _interfaces = new LinkedHashMap<>();

		final List<VirtualInterfaceManager> _managers = new ArrayList<>();

		void start(TLObject device, SimulatedDevice simulated, String name) throws Exception {
			VirtualInterfaceManager manager = manager("download-queue-" + name);
			Endpoint endpoint = loopback();
			_managers.add(manager);
			_endpoints.put(device, endpoint);
			_interfaces.put(device, manager.start(simulated, endpoint, TransportTiming.DEFAULT, Map.of("queue", name)));
		}

		DownloadQueue.Opener opener() {
			return (device, plan) -> CalimeroWriter.open(gateway(_endpoints.get(device)), plan.getIndividualAddress());
		}

		List<TraceEntry> trace(TLObject device) throws Exception {
			File file = _interfaces.get(device).recorder().file();
			return read(file).entries();
		}

		@Override
		public void close() {
			for (VirtualInterfaceManager manager : _managers) {
				manager.stop();
			}
		}
	}

	/**
	 * A job monitor calling a hook on every progress report, cancelled when the hook says so.
	 */
	private static final class Monitor implements JobMonitor {

		private final Consumer<Monitor> _hook;

		boolean _cancelled;

		Monitor(Consumer<Monitor> hook) {
			_hook = hook;
		}

		@Override
		public void setPhases(List<JobPhase> phases) {
			checkCancelled();
		}

		@Override
		public void beginPhase(String name) {
			checkCancelled();
		}

		@Override
		public void progress(double done, double total) {
			_hook.accept(this);
			checkCancelled();
		}

		@Override
		public void fraction(double fraction) {
			checkCancelled();
		}

		@Override
		public void indeterminate() {
			checkCancelled();
		}

		@Override
		public void message(ResKey message) {
			checkCancelled();
		}

		@Override
		public void checkCancelled() {
			if (_cancelled) {
				throw new AbortExecutionException();
			}
		}

		@Override
		public void uncancelable() {
			checkCancelled();
		}
	}

	public void testQueue() throws Exception {
		TLObject project = TestVirtualDownload.importProject("../ehome/samples/KNX-Testsystem.knxproj");
		try {
			TLObject router = TestVirtualDownload.device(project, ROUTER);
			TLObject actuator = TestVirtualDownload.device(project, ACTUATOR);
			TLObject sensor = TestVirtualDownload.device(project, SENSOR);
			// A third device to download (the router's program is not downloadable): a copy of the actuator, nothing loaded.
			TLObject copy;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				copy = DevicePlanning.duplicate(actuator);
				tx.commit();
			}
			TLObject line = actuator.tContainer();
			TLObject area = line.tContainer();

			// The selection in the tree: a line or an area stands for its devices, each once, by address.
			assertEquals(List.of(router, actuator, sensor, copy), DownloadQueueFunctions.devices(project, line));
			assertEquals(List.of(router, actuator, sensor, copy),
				DownloadQueueFunctions.devices(project, List.of(copy, area, actuator)));
			assertEquals(List.of(actuator), DownloadQueueFunctions.devices(project, List.of(actuator)));

			// "All with changes".
			assertPending(project);
			assertTrue(DownloadQueue.pendingDevices(project).contains(copy));

			// The kind preset per device follows its state: a device whose program is not loaded gets the full download.
			DownloadQueue first = new DownloadQueue(project, List.of(copy, sensor, actuator));
			assertEquals(List.of(actuator, sensor, copy), first.entries().stream().map(Entry::device).toList());
			for (Entry entry : first.entries()) {
				assertEquals(entry.label(), ProgrammingFunctions.parametersOnlyByDefault(entry.device()), entry.parametersOnly());
			}
			assertFalse("The copy holds nothing.", first.entries().get(2).parametersOnly());
			first.setParametersOnly(false);

			setGateway(project);

			// Three devices one after another; the sensor (second) fails at its hardware type, the copy follows.
			Object sensorRevision = sensor.tValueByName("downloadRevision");
			Object sensorDownload = sensor.tValueByName("lastDownload");
			// The virtual copy keeps what its download wrote, for the parameters only of the second run.
			SimulatedDevice virtualCopy = ProjectDevices.create(copy, MappedLoadStateRule.ECHO);
			try (Bench bench = new Bench()) {
				bench.start(actuator, ProjectDevices.create(actuator, MappedLoadStateRule.ECHO), "actuator");
				SimulatedDevice wrongHardware = ProjectDevices.create(sensor, MappedLoadStateRule.ECHO);
				byte[] hardware = wrongHardware.property(0, SimulatedDevice.PID_HARDWARE_TYPE, 1, 1).clone();
				hardware[hardware.length - 1] ^= 1;
				wrongHardware.initProperty(0, SimulatedDevice.PID_HARDWARE_TYPE, hardware);
				bench.start(sensor, wrongHardware, "sensor-mismatch");
				bench.start(copy, virtualCopy, "copy");

				ResKey[] summary = new ResKey[1];
				writing(() -> summary[0] = first.run(new Monitor(m -> {
					// No cancel.
				}), bench.opener()));
				assertEquals(describe(first), List.of(State.DONE, State.FAILED, State.DONE), states(first));
				assertEquals("Programmed: 2, failed: 1, cancelled: 0, aborted: 0.", Resources.getInstance().getString(summary[0]));
				assertNull("The lock is released.", DownloadLock.veto(project));

				// One after another: no trace overlaps the next.
				List<TraceEntry> actuatorTrace = bench.trace(actuator);
				List<TraceEntry> sensorTrace = bench.trace(sensor);
				List<TraceEntry> copyTrace = bench.trace(copy);
				assertTrue(actuatorTrace.stream().anyMatch(TraceEntry::incoming));
				assertTrue(sensorTrace.stream().anyMatch(TraceEntry::incoming));
				assertTrue(copyTrace.stream().anyMatch(TraceEntry::incoming));
				assertBefore(actuatorTrace, sensorTrace);
				assertBefore(sensorTrace, copyTrace);
			}
			// Each download recorded singly; the failed one left the sensor's record as it was.
			Object actuatorDownload = actuator.tValueByName("lastDownload");
			Object copyDownload = copy.tValueByName("lastDownload");
			assertNotNull(actuatorDownload);
			assertNotNull(copyDownload);
			assertEquals(sensorRevision, sensor.tValueByName("downloadRevision"));
			assertEquals(sensorDownload, sensor.tValueByName("lastDownload"));
			assertPending(project);
			assertTrue(DownloadQueue.pendingDevices(project).contains(sensor));
			assertFalse(DownloadQueue.pendingDevices(project).contains(actuator));

			// After its full download, the copy holds the program of its last download: parameters only.
			DownloadQueue second = new DownloadQueue(project, List.of(actuator, sensor, copy));
			Entry actuatorEntry = second.entries().get(0);
			Entry sensorEntry = second.entries().get(1);
			Entry copyEntry = second.entries().get(2);
			assertTrue(copyEntry.parametersOnly());
			actuatorEntry.setParametersOnly(false);

			// The actuator aborted after 3 operations, the sensor cancelled while it waits; the copy follows.
			try (Bench bench = new Bench()) {
				bench.start(actuator, ProjectDevices.create(actuator, MappedLoadStateRule.ECHO), "actuator-aborted");
				bench.start(sensor, ProjectDevices.create(sensor, MappedLoadStateRule.ECHO), "sensor-cancelled");
				bench.start(copy, virtualCopy, "copy-parameters");
				writing(() -> second.run(new Monitor(m -> {
					if (second.current() == actuatorEntry && actuatorEntry.done() >= 3) {
						second.cancel(List.of(sensorEntry));
						second.abortRunning();
					}
				}), bench.opener()));
				assertEquals(describe(second), List.of(State.ABORTED, State.CANCELLED, State.DONE), states(second));
				assertEquals("Aborted before operation 5 of " + actuatorEntry.count() + ".",
					Resources.getInstance().getString(actuatorEntry.details()));
				assertTrue("Nothing reached the cancelled sensor.", bench.trace(sensor).stream().noneMatch(TraceEntry::incoming));
				assertBefore(bench.trace(actuator), bench.trace(copy));
			}
			assertEquals("The aborted download is not recorded.", actuatorDownload, actuator.tValueByName("lastDownload"));
			assertEquals(sensorDownload, sensor.tValueByName("lastDownload"));
			assertTrue(((Date) copy.tValueByName("lastDownload")).after((Date) copyDownload));

			// A cancel of the job aborts the running download and cancels the waiting ones.
			DownloadQueue third = new DownloadQueue(project, List.of(sensor, copy));
			third.setParametersOnly(false);
			try (Bench bench = new Bench()) {
				bench.start(sensor, ProjectDevices.create(sensor, MappedLoadStateRule.ECHO), "sensor-job-cancelled");
				bench.start(copy, ProjectDevices.create(copy, MappedLoadStateRule.ECHO), "copy-job-cancelled");
				Entry running = third.entries().get(0);
				ResKey[] summary = new ResKey[1];
				writing(() -> summary[0] = third.run(new Monitor(m -> {
					if (running.done() >= 2) {
						m._cancelled = true;
					}
				}), bench.opener()));
				assertEquals(describe(third), List.of(State.ABORTED, State.CANCELLED), states(third));
				assertEquals("Programmed: 0, failed: 0, cancelled: 1, aborted: 1.", Resources.getInstance().getString(summary[0]));
				assertTrue(bench.trace(copy).stream().noneMatch(TraceEntry::incoming));
			}
			assertEquals(sensorDownload, sensor.tValueByName("lastDownload"));
			assertNull("The lock is released.", DownloadLock.veto(project));

			// The queue does not start while another download of the project runs; one download of a project at a time.
			DownloadQueue fourth = new DownloadQueue(project, List.of(sensor));
			try (DownloadLock lock = DownloadLock.acquire(sensor, ResKey.text("single"))) {
				assertEquals(ResKey.text("single"), DownloadLock.veto(project));
				writing(() -> {
					assertEquals(ResKey.text("single"), fourth.startVeto());
					try {
						fourth.run(new Monitor(m -> fail("Must not start.")), (d, p) -> {
							throw new AssertionError("Must not connect.");
						});
						fail("The queue must not start.");
					} catch (TopLogicException ex) {
						assertEquals(ResKey.text("single"), ex.getErrorKey());
					}
				});
				try {
					DownloadLock.acquire(copy, ResKey.text("other"));
					fail("One download of a project at a time.");
				} catch (TopLogicException ex) {
					assertEquals(ResKey.text("single"), ex.getErrorKey());
				}
			}
			assertNull(DownloadLock.veto(project));
			assertEquals(List.of(State.WAITING), states(fourth));
		} finally {
			TestVirtualDownload.delete(project);
		}
	}

	/**
	 * "All with changes": exactly the devices of the project with <code>downloadPending</code>.
	 */
	private static void assertPending(TLObject project) {
		List<TLObject> expected = new ArrayList<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			if (Boolean.TRUE.equals(device.tValueByName("downloadPending"))) {
				expected.add(device);
			}
		}
		assertEquals(DownloadQueue.sorted(expected), DownloadQueue.pendingDevices(project));
	}

	private static void setGateway(TLObject project) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			// A valid address: the opener of the test connects each device to its own virtual interface.
			((Project) project).setGateway("127.0.0.1:3671");
			tx.commit();
		}
	}

	private static String describe(DownloadQueue queue) {
		StringBuilder result = new StringBuilder();
		for (Entry entry : queue.entries()) {
			result.append(entry).append(": ")
				.append(entry.details() == null ? "" : Resources.getInstance().getString(entry.details())).append('\n');
		}
		return result.toString();
	}

	private static List<State> states(DownloadQueue queue) {
		return queue.entries().stream().map(Entry::state).toList();
	}

	/**
	 * The trace <code>first</code> ends before <code>next</code> begins.
	 */
	private static void assertBefore(Collection<TraceEntry> first, Collection<TraceEntry> next) {
		if (first.isEmpty() || next.isEmpty()) {
			return;
		}
		Instant end = first.stream().filter(TraceEntry::incoming).map(TraceEntry::time).max(Instant::compareTo).orElse(null);
		Instant start = next.stream().filter(TraceEntry::incoming).map(TraceEntry::time).min(Instant::compareTo).orElse(null);
		if (end != null && start != null) {
			assertTrue(end + " < " + start, end.isBefore(start));
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestVirtualDownloadQueue.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
