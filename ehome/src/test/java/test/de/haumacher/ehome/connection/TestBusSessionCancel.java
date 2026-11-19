/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.job.JobHarness;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.CancelScope;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.job.JobState;
import com.top_logic.layout.view.job.JobStatus;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import tuwien.auto.calimero.IndividualAddress;

import de.haumacher.ehome.connection.BusSessionLink;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CompareJob;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.scan.CalimeroScanBus;

/**
 * A job on a bus session through a controller that is cancelled (haumacher/ehome #189) ends the bus
 * session (<code>close</code>) and nothing else: the controller's connection stays, and the next job
 * opens a session at once.
 *
 * <p>
 * The controller is the {@link FakeBusController}, whose transport fails a write on an interrupted
 * thread by closing the whole socket with 1001, as Tomcat's blocking send does: an interrupt of the
 * job's thread must never reach a write on the controller's socket.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBusSessionCancel extends BasicTestCase {

	private static final String CONTROLLER = "homecontrol-cancel";

	/**
	 * A device read of the scan (Calimero's management client on the job's thread) cancelled while
	 * it waits for the acknowledgement of the device: Calimero's transport layer waits on and sends
	 * the repetition of the data unit with the interrupt set, which must not close the connection.
	 */
	public void testDeviceReadCancelled() throws Exception {
		TLObject project = ControllerEndpoint.project("Bus cancel test");
		FakeBusController fake = FakeBusController.connect(project, CONTROLLER, true);
		try {
			IndividualAddress address = new IndividualAddress(1, 1, 7);
			MemoryDevice device = new MemoryDevice(address, new byte[] { 0x07, 0x05 });
			device.setSilent(read -> read > 0);
			fake.setBus(device);
			BusPath path = new BusPath.Controller(CONTROLLER, fake.connection());
			try (CalimeroScanBus bus = CalimeroScanBus.open(path)) {
				DeviceReader reader = bus.device(address.getRawAddress());
				CancelScope scope = new CancelScope();
				CompletableFuture<Throwable> outcome = new CompletableFuture<>();
				Thread worker = new Thread(() -> {
					try (var binding = CancelScope.bind(scope)) {
						reader.readMemory(0x4000, 12);
						outcome.complete(null);
					} catch (Throwable ex) {
						outcome.complete(ex);
					}
				}, "cancelled-read");
				worker.start();
				awaitBlockedIn(worker, "tuwien.auto.calimero.mgmt.TransportLayerImpl", "waitForAck");

				scope.cancel();
				Throwable failure = outcome.get(30, TimeUnit.SECONDS);
				assertTrue(String.valueOf(failure), failure instanceof AbortExecutionException);
			}
			assertSessionEndedConnectionKept(fake);
		} finally {
			fake.stop();
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(List.of(project));
		}
	}

	/**
	 * A Compare through the controller cancelled while a read waits for the device: the session ends
	 * with <code>close</code>, the connection stays ready, and a second Compare runs at once.
	 */
	public void testCompareCancelled() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		FakeBusController fake = null;
		try {
			fake = FakeBusController.connect(project, CONTROLLER, true);
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				((Project) project).setProgrammingController((Controller) fake.controller());
				((Project) project).setGateway(null);
				tx.commit();
			}
			TLObject device = SharedData.within("ehome.physical:Device", List.of(project)).get(0);
			MemoryDevice memory = new MemoryDevice(new IndividualAddress(DownloadImage.individualAddress(device)),
				new byte[] { 0x07, 0x05 });
			memory.setSilent(read -> read == 3);
			fake.setBus(memory);

			JobHarness job = new JobHarness(List.of());
			job.start(new CompareJob(null, null), List.of(device));
			JobState state = cancelWhenBlocked(job, memory);
			assertEquals(JobStatus.CANCELLED, state.status());
			assertSessionEndedConnectionKept(fake);

			memory.setSilent(read -> false);
			JobHarness second = new JobHarness(List.of());
			second.start(new CompareJob(null, null), List.of(device));
			JobState completed = second.await();
			assertEquals(String.valueOf(completed.error()), JobStatus.COMPLETED, completed.status());
			assertSessionEndedConnectionKept(fake);
		} finally {
			if (fake != null) {
				fake.stop();
			}
			ControllerConnections.awaitKnowledgeBase();
			SharedData.deleteProjects(projects);
		}
	}

	private static JobState cancelWhenBlocked(JobHarness job, MemoryDevice memory) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		while (memory.memoryReads() < 3 || job.worker() == null
			|| job.worker().getState() != Thread.State.TIMED_WAITING) {
			assertTrue("The Compare must wait for the third read.", System.currentTimeMillis() < deadline);
			Thread.sleep(10);
		}
		job.cancel();
		return job.await();
	}

	private static void assertSessionEndedConnectionKept(FakeBusController fake) throws Exception {
		fake.sync();
		assertNull("The connection is not closed: " + fake.closedWith(), fake.closedWith());
		assertTrue(fake.connection().isReady());
		assertSame(fake.connection(), ControllerConnections.get(fake.connection().controllerId()));
		List<Map<String, Object>> bus = fake.busMessages();
		assertEquals(bus.toString(), "close", bus.get(bus.size() - 1).get("op"));
		assertFalse("The session ended on the controller.", fake.sessionOpen());
		assertNull(BusSessionLink.veto(fake.connection(), CONTROLLER));
		BusSessionLink.open(fake.connection(), CONTROLLER).close();
		fake.sync();
	}

	private static void awaitBlockedIn(Thread worker, String type, String method) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (true) {
			Thread.State state = worker.getState();
			if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
				for (StackTraceElement element : worker.getStackTrace()) {
					if (element.getClassName().equals(type) && element.getMethodName().equals(method)) {
						return;
					}
				}
			}
			assertTrue("The read must wait in " + type + "#" + method + ".", System.currentTimeMillis() < deadline);
			Thread.sleep(10);
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestBusSessionCancel.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SchedulerService.Module.INSTANCE));
	}

}
