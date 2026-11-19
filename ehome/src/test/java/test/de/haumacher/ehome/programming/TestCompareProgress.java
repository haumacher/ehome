/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.job.JobHarness;

import com.top_logic.basic.sched.SchedulerService;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.view.job.JobState;
import com.top_logic.layout.view.job.JobStatus;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CompareJob;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.ProgrammingReport;

/**
 * The progress and the cancel of the comparison of a device ({@link CompareJob}, haumacher/ehome
 * #190), run by the engine's job runner ({@link JobHarness}) on a device in memory: the progress
 * counts the bytes read of all bytes to compare and moves with every read, the message names the
 * block and the address read; a cancel takes effect before the next read, and the report of the
 * cancelled comparison lists what was compared up to then.
 */
@SuppressWarnings("javadoc")
public class TestCompareProgress extends BasicTestCase {

	/** The projects before the test. */
	static List<TLObject> _before;

	/** The device of Test.knxproj, imported once for the tests of this class. */
	static TLObject _device;

	public void testProgressPerRead() throws Exception {
		Reads expected = reads();
		JobHarness job = new JobHarness(List.of());
		CountingDevice device = new CountingDevice();
		job.start(compare(device), List.of(_device));
		JobState state = job.await();
		assertEquals(String.valueOf(state.error()), JobStatus.COMPLETED, state.status());
		// Every read of the blocks, then the tables where the device has them, as far as the blocks did
		// not read them (haumacher/ehome #193).
		assertTrue("Every read of the blocks: " + device.reads(), device.reads() >= expected.count());

		List<Long> done = new ArrayList<>();
		List<String> messages = new ArrayList<>();
		for (String event : job.events()) {
			if (event.startsWith("progress:") && event.endsWith("/" + expected.bytes())) {
				done.add(Long.valueOf(event.substring("progress:".length(), event.indexOf('/'))));
			}
			if (event.matches("message:.+: [0-9A-F]{4} of [0-9A-F]{4}-[0-9A-F]{4}")) {
				messages.add(event);
			}
		}
		assertTrue("A progress before every read and one at the end: " + done, done.size() >= expected.count() + 1);
		for (int n = 1; n < done.size(); n++) {
			assertTrue("The progress never goes back: " + done, done.get(n) >= done.get(n - 1));
		}
		assertEquals(Long.valueOf(0), done.get(0));
		assertEquals(Long.valueOf(expected.bytes()), done.get(done.size() - 1));
		assertEquals("A message naming the address of every read: " + job.events(), expected.count(), messages.size());
	}

	public void testCancelBeforeNextRead() throws Exception {
		Reads expected = reads();
		int cancelAt = expected.count() / 2 + 1;
		JobHarness job = new JobHarness(List.of());
		CountingDevice device = new CountingDevice();
		device.onRead(read -> {
			if (read == cancelAt) {
				job.cancel();
			}
		});
		job.start(compare(device), List.of(_device));
		JobState state = job.await();

		assertEquals(JobStatus.CANCELLED, state.status());
		assertEquals("The read in flight ends, no further read.", cancelAt, device.reads());
		Map<?, ?> report = (Map<?, ?>) state.result();
		assertNotNull("The cancelled comparison reports what it compared.", report);
		assertEquals(Boolean.FALSE, report.get(ProgrammingReport.SUCCESS));
		String summary = (String) report.get(ProgrammingReport.SUMMARY);
		assertTrue(summary, summary.contains("aborted") || summary.contains("abgebrochen"));
		List<?> messages = (List<?>) report.get(ProgrammingReport.MESSAGES);
		long blocks = messages.stream().filter(m -> !"".equals(((Map<?, ?>) m).get("file"))).count();
		assertTrue("The blocks compared, up to the one aborted: " + messages, blocks >= 1);
	}

	private static CompareJob compare(DeviceReader reader) {
		return new CompareJob(null, null) {
			@Override
			protected DeviceReader openReader(BusPath path, int address) {
				return reader;
			}
		};
	}

	/**
	 * The memory reads the comparison of {@link #_device} makes, and the bytes it compares.
	 */
	record Reads(int count, long bytes) {
		// A record.
	}

	private static Reads reads() {
		DownloadPlan plan = DownloadPlanner.plan(_device, DownloadPlan.Kind.FULL);
		int count = 0;
		long bytes = 0;
		for (Block block : plan.getImage().getBlocks()) {
			if (plan.getUnloadedBlocks().contains(block) || block.stateMachine() != null) {
				continue;
			}
			byte[] mask = block.mask();
			int n = 0;
			while (n < mask.length) {
				if (mask[n] == 0) {
					n++;
					continue;
				}
				int start = n;
				while (n < mask.length && mask[n] != 0 && n - start < DownloadPlanner.MAX_CHUNK) {
					n++;
				}
				count++;
				bytes += n - start;
			}
		}
		assertTrue("The comparison needs several reads: " + count, count >= 4);
		return new Reads(count, bytes);
	}

	interface ReadHook {
		void read(int number);
	}

	/**
	 * A device in memory counting the reads of its memory.
	 */
	static final class CountingDevice extends MockDevice {

		private final AtomicInteger _reads = new AtomicInteger();

		private volatile ReadHook _hook = read -> {
			// Nothing.
		};

		void onRead(ReadHook hook) {
			_hook = hook;
		}

		int reads() {
			return _reads.get();
		}

		@Override
		public byte[] readMemory(int address, int length) {
			_hook.read(_reads.incrementAndGet());
			return super.readMemory(address, length);
		}
	}

	private static Test withDevice(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestCompareProgress.class, () -> {
					_before = instances("ehome.project:Project");
					importSample("samples/Test.knxproj");
					List<TLObject> projects = SharedData.newProjects(_before);
					assertEquals(1, projects.size());
					try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
						((Project) projects.get(0)).setGateway("localhost");
						tx.commit();
					}
					_device = SharedData.within("ehome.physical:Device", projects).get(0);
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestCompareProgress.class, () -> {
					SharedData.deleteProjects(SharedData.newProjects(_before));
					return null;
				});
				_device = null;
				_before = null;
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(withDevice(new TestSuite(TestCompareProgress.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SchedulerService.Module.INSTANCE));
	}

}
