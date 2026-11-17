/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.job.JobHarness;
import test.de.haumacher.ehome.knx.transport.TestLink;
import test.de.haumacher.ehome.view.ViewCommandScan;

import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.job.JobState;
import com.top_logic.layout.view.job.JobStatus;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.scan.BusScanner;
import de.haumacher.ehome.scan.CalimeroScanBus;
import de.haumacher.ehome.scan.ReadBackJob;
import de.haumacher.ehome.scan.ScanBus;
import de.haumacher.ehome.scan.ScanJob;
import de.haumacher.ehome.scan.ScanTarget;

/**
 * A cancel of the bus scan ({@link ScanJob}) and of the read-back of a device ({@link ReadBackJob}),
 * run by the engine's job runner ({@link JobHarness}), reaches them through the job's
 * <code>CancelScope</code> (TL #30196).
 *
 * <ul>
 * <li>While the job waits for the bus, in the access of {@link CalimeroScanBus} and
 * {@link CalimeroReader} on a link in memory where nothing answers, the cancel ends the wait at once:
 * the accesses are interruptible sections of the job's scope. Without them, the job would wait for
 * the timeout of the access.</li>
 * <li>While the job is in the database, the cancel interrupts nothing, the job ends at its point of
 * no return before it stores anything, and the database stays usable; a cancel after the point of
 * no return is refused (TL #30194).</li>
 * </ul>
 *
 * <p>
 * The project is the test system with its gateway; the bus of a scan that completes is the recorded
 * test line of {@link TestBusScan}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestScanJobsCancelled extends BasicTestCase {

	private static final int ACTUATOR = 0x1305;

	/** The address of the client on the link in memory, 1.1.255. */
	private static final int CLIENT = 0x11FF;

	/**
	 * The longest time a cancelled wait for the bus may take to end; the timeouts of the accesses
	 * are seconds.
	 */
	private static final long PROMPT_MILLIS = 1500;

	/** The view of the scan, relative to the views directory. */
	private static final String VIEW = "projects/scan.view.xml";

	/** A value of the channel <code>busState</code>, which only makes the commands decide again. */
	private static final Object BUS_STATE = Long.valueOf(1);

	/** The projects before the test. */
	static List<TLObject> _before;

	/** The test system, imported once for the tests of this class. */
	static TLObject _project;

	/**
	 * A scan cancelled while it waits for the answers of the devices in programming mode ends at once
	 * as cancelled.
	 */
	public void testScanCancelledOnTheBus() throws Exception {
		CountDownLatch asking = new CountDownLatch(1);
		JobHarness job = new JobHarness(List.of()).onEvent(event -> {
			if (event.equals("phase:" + BusScanner.PHASE_PROGRAMMING)) {
				asking.countDown();
			}
		});
		ScanJob scan = new ScanJob(null, null) {
			@Override
			protected ScanBus openBus(BusPath path) {
				return new CalimeroScanBus(path, new TestLink(CLIENT)) {
					// On the link in memory.
				};
			}
		};
		job.start(scan, Arrays.asList(_project, ScanTarget.PROGRAMMING_MODE, null));
		assertTrue(asking.await(10, TimeUnit.SECONDS));

		JobState state = cancelWhenBlocked(job, "tuwien.auto.calimero.");

		assertEquals(JobStatus.CANCELLED, state.status());
		assertNull(state.result());
		assertFalse(job.events().toString(), job.events().contains("uncancelable"));
		assertDatabaseUsable();
	}

	/**
	 * A read-back cancelled while it waits for the device ends at once as cancelled.
	 */
	public void testReadBackCancelledOnTheBus() throws Exception {
		Object actuatorRow = actuatorRow();

		JobHarness job = new JobHarness(List.of());
		ReadBackJob readBack = new ReadBackJob(null, null) {
			@Override
			protected DeviceReader openReader(BusPath gateway, int address) {
				assertEquals(ACTUATOR, address);
				return new CalimeroReader(gateway.text(), new TestLink(CLIENT), address) {
					// On the link in memory.
				};
			}
		};
		// Started with the values the command of the view starts it with.
		Map<String, Object> channels = new HashMap<>();
		channels.put("selected", actuatorRow);
		channels.put("busState", BUS_STATE);
		job.start(readBack, ViewCommandScan.command(VIEW, "ReadBackJob").jobValues(channels));

		JobState state = cancelWhenBlocked(job, "de.haumacher.ehome.knx.transport.TransportClient");

		assertEquals(JobStatus.CANCELLED, state.status());
		assertNull(state.result());
		assertFalse(job.events().toString(), job.events().contains("uncancelable"));
		assertDatabaseUsable();
	}

	/**
	 * A scan cancelled when its bus is closed, right before it stores the devices found, ends at its
	 * point of no return as cancelled, without passing it: nothing is stored, the worker is not
	 * interrupted, and the database stays usable.
	 */
	public void testScanCancelledBeforeStoring() throws Exception {
		JobHarness job = new JobHarness(List.of());
		Boolean[] interrupted = new Boolean[1];
		ScanJob scan = new ScanJob(null, null) {
			@Override
			protected ScanBus openBus(BusPath gateway) {
				return new TestBusScan.FakeBus() {
					@Override
					public void close() {
						job.cancel();
						interrupted[0] = Boolean.valueOf(Thread.currentThread().isInterrupted());
					}
				};
			}
		};
		job.start(scan, List.of(_project, ScanTarget.LINE, "1.3"));
		JobState state = job.await();

		assertEquals(JobStatus.CANCELLED, state.status());
		assertNull(state.result());
		assertEquals("The worker is not interrupted.", Boolean.FALSE, interrupted[0]);
		assertFalse("Nothing is stored: " + job.events(), job.events().contains("uncancelable"));
		assertDatabaseUsable();
	}

	/**
	 * A scan cancelled after its point of no return stores the devices found and ends as completed
	 * with them.
	 */
	public void testScanCancelledAfterPointOfNoReturn() throws Exception {
		JobHarness job = new JobHarness(List.of());
		job.onEvent(event -> {
			if (event.equals("uncancelable")) {
				// Refused: the job is no longer cancelable.
				job.cancel();
			}
		});
		// Started with the values the command of the view starts it with.
		job.start(scanOfFakeBus(), ViewCommandScan.command(VIEW, "ScanJob").jobValues(scanChannels()));
		JobState state = job.await();

		assertEquals(JobStatus.COMPLETED, state.status());
		// The actuator and the Tastsensor; the gateway 1.3.1 does not answer through its own tunnel.
		assertEquals(2, ((List<?>) ((Map<?, ?>) state.result()).get("rows")).size());
	}

	/**
	 * The row of the actuator of a completed scan of the recorded test line.
	 */
	private Object actuatorRow() throws Exception {
		JobHarness job = new JobHarness(List.of());
		job.start(scanOfFakeBus(), List.of(_project, ScanTarget.LINE, "1.3"));
		JobState state = job.await();
		assertEquals(JobStatus.COMPLETED, state.status());
		List<?> rows = (List<?>) ((Map<?, ?>) state.result()).get("rows");
		return rows.stream().filter(row -> "1.3.5".equals(((Map<?, ?>) row).get("address"))).findFirst().orElseThrow();
	}

	/**
	 * The channels of the scan view choosing the line 1.3, with a value of the bus
	 * state (an input of the command before the inputs of the job).
	 */
	private static Map<String, Object> scanChannels() {
		Map<String, Object> result = new HashMap<>();
		result.put("project", _project);
		result.put("target", ScanTarget.LINE);
		result.put("line", "1.3");
		result.put("busState", BUS_STATE);
		return result;
	}

	private static ScanJob scanOfFakeBus() {
		return new ScanJob(null, null) {
			@Override
			protected ScanBus openBus(BusPath gateway) {
				return new TestBusScan.FakeBus();
			}
		};
	}

	/**
	 * Cancels the given job once its worker waits in the given code, and waits for the job to end,
	 * which must be prompt.
	 */
	private static JobState cancelWhenBlocked(JobHarness job, String code) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!blockedIn(job.worker(), code)) {
			assertTrue("The job must wait in " + code + ".", System.currentTimeMillis() < deadline);
			Thread.sleep(10);
		}
		long start = System.nanoTime();
		job.cancel();
		JobState state = job.await();
		long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertTrue("The cancel must end the wait for the bus at once, took " + millis + " ms.", millis < PROMPT_MILLIS);
		return state;
	}

	private static boolean blockedIn(Thread worker, String code) {
		if (worker == null) {
			return false;
		}
		Thread.State state = worker.getState();
		if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
			return false;
		}
		for (StackTraceElement element : worker.getStackTrace()) {
			if (element.getClassName().startsWith(code)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A read and a commit of the database succeed.
	 */
	private static void assertDatabaseUsable() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		assertEquals(1, SharedData.newProjects(_before).size());
		try (Transaction tx = kb.beginTransaction()) {
			_project.tUpdateByName("gateway", "localhost:3671");
			tx.commit();
		}
		try (Transaction tx = kb.beginTransaction()) {
			_project.tUpdateByName("gateway", "localhost");
			tx.commit();
		}
	}

	/**
	 * Imports the test system with its gateway for the given tests, and deletes it again.
	 */
	private static Test withProject(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestScanJobsCancelled.class, () -> {
					_before = instances("ehome.project:Project");
					importSample("samples/KNX-Testsystem.knxproj");
					List<TLObject> projects = SharedData.newProjects(_before);
					assertEquals(1, projects.size());
					_project = projects.get(0);
					try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
						_project.tUpdateByName("gateway", "localhost");
						tx.commit();
					}
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestScanJobsCancelled.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					return null;
				});
				_project = null;
				_before = null;
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(withProject(new TestSuite(TestScanJobsCancelled.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SchedulerService.Module.INSTANCE));
	}

}
