/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * The work of the command "Read out" of a device (<code>&lt;start-job&gt;</code> body,
 * haumacher/ehome #193): reads the device on the project's {@link BusPath} (its gateway or its
 * controller) with {@link CalimeroReader} into a {@link Snapshot} ({@link ReadOut}, nothing is
 * written) and stores it with the device ({@link Snapshots}).
 *
 * <p>
 * The device is the input <code>device</code> of the job ({@link ProgrammingFunctions#device(List)}).
 * The result is the stored snapshot (<code>ehome.physical:DeviceSnapshot</code>). The progress counts
 * the bytes read of all bytes of the image's blocks and moves with every read; the message names the
 * block and the address read (as the comparison, haumacher/ehome #190). A cancel ends the read-out
 * before its next read; what was read up to then is stored as a snapshot that is not complete, which
 * the cancelled job returns.
 * </p>
 */
public class ReadOutJob implements JobBody {

	/**
	 * Opens the connection to a device.
	 */
	public interface ReaderFactory {

		/**
		 * The connection to the device with the given address on the given path.
		 */
		DeviceReader open(BusPath path, int address) throws BusException;
	}

	/**
	 * Configuration of {@link ReadOutJob}.
	 */
	public interface Config extends PolymorphicConfiguration<ReadOutJob> {

		@Override
		@ClassDefault(ReadOutJob.class)
		Class<? extends ReadOutJob> getImplementationClass();
	}

	/**
	 * Creates a {@link ReadOutJob} from configuration.
	 */
	@CalledByReflection
	public ReadOutJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		TLObject device = ProgrammingFunctions.device(arguments);
		ResKey veto = ProgrammingFunctions.compareVeto(device);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		BusPath path = BusPath.of(device);
		Snapshot snapshot;
		try {
			snapshot = readOut(job, device, plan, path, this::openReader);
		} catch (BusException ex) {
			throw new TopLogicException(I18NConstants.READOUT_FAILED__DEVICE_PATH_REASON.fill(plan.getDevice(),
				path.text(), ex.getErrorKey()));
		}
		return store(device, snapshot);
	}

	/**
	 * Reads the device with the progress and the cancel of the job.
	 *
	 * @return The snapshot; not {@link Snapshot#isComplete() complete} if the job was cancelled.
	 * @throws BusException
	 *         If the device cannot be reached.
	 */
	public static Snapshot readOut(JobMonitor job, TLObject device, DownloadPlan plan, BusPath path,
			ReaderFactory readers) throws BusException {
		String pathText = path.text();
		job.message(I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(pathText));
		long[] total = { 0 };
		Snapshot snapshot;
		try (DeviceReader reader = readers.open(path, plan.getIndividualAddress())) {
			snapshot = ReadOut.read(DeviceConfiguration.program(device), plan, reader, new ReadOut.Listener() {
				@Override
				public void block(int number, int count, Block block) {
					job.checkCancelled();
				}

				@Override
				public void read(Block block, int address, long done, long all) {
					job.checkCancelled();
					total[0] = all;
					job.progress(done, all);
					job.message(I18NConstants.PROGRESS_READING_AT__BLOCK_ADDRESS_RANGE.fill(block.label(),
						String.format("%04X", address), String.format("%04X-%04X", block.address(), block.end() - 1)));
				}

				@Override
				public void step(ResKey what) {
					job.message(I18NConstants.PROGRESS_READING__WHAT.fill(what));
				}
			});
		}
		snapshot.setBusPath(pathText);
		if (snapshot.isComplete()) {
			job.progress(total[0], total[0]);
		}
		return snapshot;
	}

	/**
	 * Stores the snapshot with the device, in a transaction of its own.
	 *
	 * @return The stored snapshot.
	 */
	public static TLObject store(TLObject device, Snapshot snapshot) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(
			I18NConstants.COMMIT_SNAPSHOT__DEVICE.fill(MetaLabelProvider.INSTANCE.getLabel(device)))) {
			TLObject result = Snapshots.store(device, snapshot);
			tx.commit();
			return result;
		}
	}

	/**
	 * Opens the connection to the device with the given address on the given path (the project's
	 * gateway or controller); a test replaces it.
	 */
	protected DeviceReader openReader(BusPath path, int address) throws BusException {
		return CalimeroReader.open(path, address);
	}

}
