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
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.security.AccountFixture;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Revision;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.physical.EhomePhysicalFactory;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DownloadLock;
import de.haumacher.ehome.programming.download.DownloadQueue;
import de.haumacher.ehome.programming.download.DownloadQueue.Entry;
import de.haumacher.ehome.programming.download.DownloadQueue.State;
import de.haumacher.ehome.programming.download.DownloadQueueFunctions;
import de.haumacher.ehome.programming.download.DownloadSelection;
import de.haumacher.ehome.programming.download.I18NConstants;
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
 * The project's queue of downloads (haumacher/ehome #200, #218), into virtual devices of
 * <code>samples/KNX-Testsystem.knxproj</code> (the actuator, a copy of it and the push button), each
 * behind a virtual interface of its own on the loopback address, worked through by a background
 * worker.
 *
 * <p>
 * Three devices are downloaded in sequence (the traces do not overlap), each recorded singly, the
 * second one failing without stopping the third. A device already waiting is not enqueued twice; a
 * full request raises a waiting entry of the parameters only, a request of the parameters only does
 * not lower a waiting full one. Devices appended while the queue runs are worked through after the
 * others, the device whose download runs among them; a waiting device cancelled stays untouched, the
 * running download aborted stops before its next operation, unrecorded, and the queue goes on. A
 * download enqueued while another download of the project holds the lock waits for it instead of
 * being refused. An entry enqueued by a person runs as that person after the context it was enqueued in
 * has ended. "All with changes" enqueues exactly the devices with <code>downloadPending</code>;
 * the kind preset per device of a selection follows its state.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVirtualDownloadQueue extends BasicTestCase {

	private static final long TIMEOUT = 120_000;

	private static final int ROUTER = 0x1301;

	private static final int ACTUATOR = 0x1305;

	private static final int SENSOR = 0x1332;

	/** Runs the worker on a thread of its own, as the application's scheduler does. */
	private static final Executor THREAD = task -> {
		Thread thread = new Thread(task, "download-queue-test");
		thread.setDaemon(true);
		thread.start();
	};

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

			// The kind preset per device of a selection follows its state: a device whose program is not loaded gets the full download.
			DownloadSelection selection = new DownloadSelection(project, List.of(copy, sensor, actuator));
			assertEquals(List.of(actuator, sensor, copy), selection.rows().stream().map(DownloadSelection.Row::device).toList());
			for (DownloadSelection.Row row : selection.rows()) {
				assertEquals(row.label(), ProgrammingFunctions.parametersOnlyByDefault(row.device()), row.parametersOnly());
			}
			assertFalse("The copy holds nothing.", selection.rows().get(2).parametersOnly());

			// "All with changes": exactly the devices with downloadPending, in the order of their addresses.
			assertPending(project);
			assertTrue(DownloadQueue.pendingDevices(project).contains(copy));
			List<Runnable> started = new ArrayList<>();
			DownloadQueue pending = new DownloadQueue(project, (d, p) -> {
				throw new AssertionError("Must not connect.");
			}, started::add);
			pending.enqueue(DownloadQueue.pendingDevices(project), d -> Boolean.FALSE);
			assertEquals(DownloadQueue.pendingDevices(project), devices(pending));
			assertEquals("One worker for the queue.", 1, started.size());
			assertEquals(pending.entries().size(), pending.cancelWaiting());

			// A device waiting already is not enqueued twice; full raises the parameters only, not the other way round.
			DownloadQueue kinds = new DownloadQueue(project, (d, p) -> {
				throw new AssertionError("Must not connect.");
			}, started::add);
			assertTrue(kinds.enqueue(copy, true));
			assertFalse("Waits already.", kinds.enqueue(copy, true));
			assertEquals(I18NConstants.QUEUE_DEVICE_WAITS, kinds.waitingVeto(copy, true));
			assertNull("The full download raises the entry.", kinds.waitingVeto(copy, false));
			assertTrue(kinds.enqueue(copy, false));
			assertEquals(1, kinds.entries().size());
			assertFalse("Raised to the full download.", kinds.entries().get(0).parametersOnly());
			assertFalse("The parameters only do not lower it.", kinds.enqueue(copy, true));
			assertFalse(kinds.entries().get(0).parametersOnly());
			assertEquals(I18NConstants.QUEUE_DEVICE_WAITS, kinds.waitingVeto(copy, false));
			assertEquals(1, kinds.cancelWaiting());
			assertNull("A cancelled entry no longer waits.", kinds.waitingVeto(copy, true));
			assertEquals(1, kinds.clearFinished());
			assertTrue(kinds.entries().isEmpty());

			setGateway(project);

			// Three devices one after another; the sensor (second) fails at its hardware type, the copy follows.
			Long sensorRevision = ((Device) sensor).getDownloadRevision();
			Date sensorDownload = ((Device) sensor).getLastDownload();
			// The virtual copy keeps what its download wrote, for the parameters only of the later runs.
			SimulatedDevice virtualCopy = ProjectDevices.create(copy, MappedLoadStateRule.ECHO);
			try (Bench bench = new Bench()) {
				bench.start(actuator, ProjectDevices.create(actuator, MappedLoadStateRule.ECHO), "actuator");
				SimulatedDevice wrongHardware = ProjectDevices.create(sensor, MappedLoadStateRule.ECHO);
				byte[] hardware = wrongHardware.property(0, SimulatedDevice.PID_HARDWARE_TYPE, 1, 1).clone();
				hardware[hardware.length - 1] ^= 1;
				wrongHardware.initProperty(0, SimulatedDevice.PID_HARDWARE_TYPE, hardware);
				bench.start(sensor, wrongHardware, "sensor-mismatch");
				bench.start(copy, virtualCopy, "copy");

				DownloadQueue first = new DownloadQueue(project, bench.opener(), THREAD);
				writing(() -> {
					first.enqueue(List.of(actuator, sensor, copy), d -> Boolean.FALSE);
					awaitIdle(first);
				});
				assertEquals(describe(first), List.of(State.DONE, State.FAILED, State.DONE), states(first));
				assertNull("The lock is released.", DownloadLock.veto(project));
				assertNotNull("The report of the download.", first.entries().get(0).report());
				assertNotNull("The report of the failure.", first.entries().get(1).report());

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
			Date actuatorDownload = ((Device) actuator).getLastDownload();
			Date copyDownload = ((Device) copy).getLastDownload();
			assertNotNull(actuatorDownload);
			assertNotNull(copyDownload);
			assertEquals(sensorRevision, ((Device) sensor).getDownloadRevision());
			assertEquals(sensorDownload, ((Device) sensor).getLastDownload());
			assertPending(project);
			assertTrue(DownloadQueue.pendingDevices(project).contains(sensor));
			assertFalse(DownloadQueue.pendingDevices(project).contains(actuator));
			// After its full download, the copy holds the program of its last download: parameters only.
			assertTrue(ProgrammingFunctions.parametersOnlyByDefault(copy));

			// While the actuator runs: the sensor cancelled while it waits, the actuator itself and the copy appended,
			// the running download aborted after 3 operations; the queue goes on with the appended ones.
			try (Bench bench = new Bench()) {
				bench.start(actuator, ProjectDevices.create(actuator, MappedLoadStateRule.ECHO), "actuator-aborted");
				bench.start(sensor, ProjectDevices.create(sensor, MappedLoadStateRule.ECHO), "sensor-cancelled");
				bench.start(copy, virtualCopy, "copy-parameters");
				DownloadQueue second = new DownloadQueue(project, bench.opener(), THREAD);
				ResKey[] lockWhileRunning = new ResKey[1];
				boolean[] done = new boolean[1];
				Consumer<Object> hook = key -> {
					Entry current = second.current();
					if (done[0] || current == null || current.device() != actuator || current.done() < 3) {
						return;
					}
					done[0] = true;
					lockWhileRunning[0] = DownloadLock.veto(project);
					second.cancel(List.of(second.waiting(sensor)));
					assertTrue("The device whose download runs is enqueued anew.", second.enqueue(actuator, false));
					assertTrue(second.enqueue(copy, true));
					second.abortRunning();
				};
				DownloadQueue.addListener(hook);
				try {
					writing(() -> {
						second.enqueue(List.of(actuator, sensor), d -> Boolean.FALSE);
						awaitIdle(second);
					});
				} finally {
					DownloadQueue.removeListener(hook);
				}
				assertTrue("The hook ran.", done[0]);
				assertEquals("The queue holds the lock while it runs.", I18NConstants.QUEUE_RUNNING, lockWhileRunning[0]);
				assertEquals(describe(second), List.of(actuator, sensor, actuator, copy), devices(second));
				assertEquals(describe(second), List.of(State.ABORTED, State.CANCELLED, State.DONE, State.DONE), states(second));
				Entry aborted = second.entries().get(0);
				assertEquals("Aborted before operation 5 of " + aborted.count() + ".",
					Resources.getInstance().getString(aborted.details()));
				assertNull("Nothing reported of an aborted download.", aborted.report());
				assertTrue(second.entries().get(3).parametersOnly());
				assertTrue("Nothing reached the cancelled sensor.", bench.trace(sensor).stream().noneMatch(TraceEntry::incoming));
				assertBefore(bench.trace(actuator), bench.trace(copy));
				assertEquals(4, second.clearFinished());
				assertTrue(second.entries().isEmpty());
			}
			assertTrue("The second download of the actuator is recorded.",
				((Device) actuator).getLastDownload().after(actuatorDownload));
			assertEquals(sensorDownload, ((Device) sensor).getLastDownload());
			Date copyBefore = ((Device) copy).getLastDownload();
			assertTrue(copyBefore.after(copyDownload));

			// Enqueued while another download of the project holds the lock: waits for it, then runs.
			try (Bench bench = new Bench()) {
				bench.start(copy, virtualCopy, "copy-after-lock");
				DownloadQueue third = new DownloadQueue(project, bench.opener(), THREAD);
				writing(() -> {
					try (DownloadLock lock = DownloadLock.acquire(sensor, ResKey.text("single"))) {
						assertTrue(third.enqueue(copy, true));
						assertFalse("Waits for the lock.", third.awaitIdle(500));
						assertEquals(List.of(State.WAITING), states(third));
						assertEquals(ResKey.text("single"), DownloadLock.veto(project));
					}
					awaitIdle(third);
				});
				assertEquals(describe(third), List.of(State.DONE), states(third));
				assertNull(DownloadLock.veto(project));
			}
			assertTrue(((Device) copy).getLastDownload().after(copyBefore));

			// Enqueued by a person whose context is gone when the worker runs (the person logged out): the
			// download runs to its end as that person. The worker starts only after the context of the
			// enqueuing person, a sub-session of its own, has been torn down.
			Person installer;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				installer = AccountFixture.person("queue-installer", null);
				tx.commit();
			}
			try (Bench bench = new Bench()) {
				bench.start(copy, virtualCopy, "copy-person-gone");
				List<Runnable> workers = new ArrayList<>();
				DownloadQueue fourth = new DownloadQueue(project, bench.opener(), workers::add);
				writing(() -> {
					TLContextManager.inPersonContext(installer, () -> {
						assertSame(installer, TLContextManager.getSubSession().getPerson());
						assertTrue(fourth.enqueue(copy, true));
					});
					assertEquals("The worker is started after the context of the person ended.", 1, workers.size());
					THREAD.execute(workers.get(0));
					awaitIdle(fourth);
				});
				assertEquals(describe(fourth), List.of(State.DONE), states(fourth));
				Revision recorded = HistoryUtils.getLastRevision();
				assertEquals("Recorded as the person who enqueued it.", installer, recorded.resolveAuthor());
			} finally {
				try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
					installer.tDelete();
					tx.commit();
				}
			}
		} finally {
			TestVirtualDownload.delete(project);
		}
	}

	/**
	 * Waits until the worker of the queue ended, and takes the view of the knowledge base the worker's
	 * commits are in.
	 */
	private static void awaitIdle(DownloadQueue queue) throws Exception {
		assertTrue("The worker ends.", queue.awaitIdle(TIMEOUT));
		HistoryUtils.updateSessionRevision();
	}

	/**
	 * "All with changes": exactly the devices of the project with <code>downloadPending</code>.
	 */
	private static void assertPending(TLObject project) {
		List<TLObject> expected = new ArrayList<>();
		for (TLObject device : DeviceChanges.devices(project)) {
			if (Boolean.TRUE.equals(device.tValue(EhomePhysicalFactory.getDownloadPendingDeviceAttr()))) {
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

	private static List<TLObject> devices(DownloadQueue queue) {
		return queue.entries().stream().map(Entry::device).toList();
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
