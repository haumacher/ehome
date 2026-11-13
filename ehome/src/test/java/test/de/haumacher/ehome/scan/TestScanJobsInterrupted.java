/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.programming.MockDevice;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.scan.ReadBackJob;
import de.haumacher.ehome.scan.ScanBus;
import de.haumacher.ehome.scan.ScanJob;

/**
 * The bus scan ({@link ScanJob}) and the read-back of a device ({@link ReadBackJob}) keep the
 * interrupt of a cancel away from their database part (TL #30196, <code>Uninterruptible</code>): the
 * worker of the job is interrupted when the bus work ends, as a cancel at that moment does, and the
 * database part that follows still works and leaves the database usable. The bus is the recorded
 * test line of {@link TestBusScan}.
 */
@SuppressWarnings("javadoc")
public class TestScanJobsInterrupted extends BasicTestCase {

	private static final int ACTUATOR = 0x1305;

	public void testScanAndReadBack() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		try {
			List<TLObject> projects = SharedData.newProjects(before);
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			try (Transaction tx = kb.beginTransaction()) {
				project.tUpdateByName("gateway", "localhost");
				tx.commit();
			}

			// The scan: interrupted when the bus is closed, before the devices found are stored.
			Thread worker = Thread.currentThread();
			ScanJob scan = new ScanJob(null, null) {
				@Override
				protected ScanBus openBus(BusPath gateway) {
					return new TestBusScan.FakeBus() {
						@Override
						public void close() {
							worker.interrupt();
						}
					};
				}
			};
			Map<?, ?> result = (Map<?, ?>) new RecordingJobMonitor().run(scan,
				List.of(project, 1, 3, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE));
			assertFalse("The interrupt of the worker is absorbed.", Thread.interrupted());
			List<?> rows = (List<?>) result.get("rows");
			assertEquals(3, rows.size());
			Object actuatorRow = rows.stream()
				.filter(row -> "1.3.5".equals(((Map<?, ?>) row).get("address"))).findFirst().orElseThrow();

			// The read-back: interrupted when the device is released, before its settings are stored.
			ReadBackJob readBack = new ReadBackJob(null, null) {
				@Override
				protected DeviceReader openReader(BusPath gateway, int address) throws BusException {
					assertEquals(ACTUATOR, address);
					MockDevice device;
					try {
						device = TestBusScan.mock("DI-2-1.3.5");
					} catch (java.io.IOException ex) {
						throw new BusException(ex.getMessage(), ex);
					}
					return new InterruptingReader(device, worker);
				}
			};
			Map<?, ?> report = (Map<?, ?>) new RecordingJobMonitor().run(readBack, List.of(actuatorRow));
			assertFalse("The interrupt of the worker is absorbed.", Thread.interrupted());
			assertNotNull(report);

			// The database is still usable: a read and a commit.
			assertEquals(1, SharedData.newProjects(before).size());
			try (Transaction tx = kb.beginTransaction()) {
				project.tUpdateByName("gateway", "localhost:3671");
				tx.commit();
			}
		} finally {
			Thread.interrupted();
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A device that interrupts the worker of the job when it is released.
	 */
	private static final class InterruptingReader implements DeviceReader {

		private final MockDevice _device;

		private final Thread _worker;

		InterruptingReader(MockDevice device, Thread worker) {
			_device = device;
			_worker = worker;
		}

		@Override
		public byte[] readDescriptor() throws BusException {
			return _device.readDescriptor();
		}

		@Override
		public byte[] readProperty(int objectIndex, int propertyId, int start, int count) throws BusException {
			return _device.readProperty(objectIndex, propertyId, start, count);
		}

		@Override
		public byte[] readMemory(int address, int length) throws BusException {
			return _device.readMemory(address, length);
		}

		@Override
		public void disconnect() throws BusException {
			_device.disconnect();
		}

		@Override
		public void close() {
			_device.close();
			_worker.interrupt();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestScanJobsInterrupted.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
