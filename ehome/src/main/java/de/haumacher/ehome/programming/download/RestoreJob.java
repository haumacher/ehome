/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.DownloadImage.PropertyWrite;
import de.haumacher.ehome.view.JobInputs;

/**
 * The work of the command "Restore" of a snapshot of a device (<code>&lt;start-job&gt;</code> body,
 * haumacher/ehome #194): writes the snapshot back into the device as a download whose bytes are the
 * snapshot's ({@link DownloadPlanner#restore(TLObject, Snapshot, boolean)}).
 *
 * <p>
 * The inputs of the job are whether a snapshot read at another individual address may be written
 * (a Boolean) and the stored snapshot (<code>ehome.physical:DeviceSnapshot</code>). Refused with the
 * reason before anything is written: writing switched off, the path to the bus not usable, another
 * download of the project running ({@link DownloadLock}, which the restore holds while it runs), a
 * plan with problems (another program, mask or program version, bytes the snapshot lacks, another
 * individual address not allowed).
 * </p>
 *
 * <p>
 * The job runs in three phases on one connection through the project's {@link BusPath}:
 * </p>
 * <ol>
 * <li><code>compare</code>: reads the device as the read-out does ({@link ReadOut}, with the plan of
 * the restore). If the device holds the snapshot already (its mask and program version, every load
 * state machine loaded, every byte and property the restore writes, the tables at the snapshot's
 * places), nothing is written; the reading is stored as a new snapshot. A cancel is possible up to
 * here.</li>
 * <li><code>write</code>: performs the plan ({@link DownloadExecutor}): the load procedure of the
 * program, every byte from the snapshot. The project is not changed: the device's record of its
 * download (<code>Device#downloadRevision</code>) stays, the compare shows where the device differs
 * from the plan.</li>
 * <li><code>readback</code>: reads the device again, stores the reading as a new snapshot and
 * compares it with the plan of the restore; the result is the report of that comparison
 * ({@link CompareJob#report(DownloadPlan, Snapshot, DeviceComparison.Result, ResKey, ResKey)}).</li>
 * </ol>
 */
public class RestoreJob implements JobBody {

	/** The phase reading the device before anything is written. */
	public static final String PHASE_COMPARE = "compare";

	/** The phase performing the plan. */
	public static final String PHASE_WRITE = "write";

	/** The phase reading back what was written. */
	public static final String PHASE_READBACK = "readback";

	/**
	 * Opens the connection to a device.
	 */
	public interface AccessFactory {

		/**
		 * The connection to the device with the given address on the given path.
		 */
		DeviceAccess open(BusPath path, int address) throws BusException;
	}

	/**
	 * Configuration of {@link RestoreJob}.
	 */
	public interface Config extends PolymorphicConfiguration<RestoreJob> {

		@Override
		@ClassDefault(RestoreJob.class)
		Class<? extends RestoreJob> getImplementationClass();
	}

	/**
	 * Creates a {@link RestoreJob} from configuration.
	 */
	@CalledByReflection
	public RestoreJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		List<Object> inputs = JobInputs.of(arguments, 2);
		if (!(inputs.get(1) instanceof TLObject stored)) {
			throw new IllegalArgumentException("No snapshot as the input of the job among " + arguments);
		}
		return restore(job, stored, Boolean.TRUE.equals(inputs.get(0)), CalimeroWriter::open);
	}

	/**
	 * Restores the stored snapshot into its device.
	 *
	 * @param otherAddress
	 *        Whether a snapshot read at another individual address than the device has may be written.
	 * @return The report ({@link ProgrammingReport#toMap(ResKey, boolean)}).
	 * @throws TopLogicException
	 *         With the reason, if the restore is refused before anything is written.
	 */
	public static Map<String, Object> restore(JobMonitor job, TLObject stored, boolean otherAddress,
			AccessFactory accesses) throws Exception {
		TLObject device = Snapshots.device(stored);
		Snapshot snapshot = Snapshots.load(stored);
		try (DownloadLock lock = DownloadLock.acquire(device,
			I18NConstants.LOCK_RESTORE__DEVICE.fill(MetaLabelProvider.INSTANCE.getLabel(device)))) {
			ResKey veto = veto(device, snapshot, otherAddress);
			if (veto != null) {
				throw new TopLogicException(veto);
			}
			return restoreLocked(job, device, snapshot, otherAddress, accesses);
		}
	}

	/**
	 * Why the restore of the snapshot into the device is refused, <code>null</code> if it can be
	 * started.
	 */
	public static ResKey veto(TLObject device, Snapshot snapshot, boolean otherAddress) {
		if (!CalimeroWriter.writeEnabled()) {
			return I18NConstants.ERROR_WRITE_DISABLED;
		}
		ResKey path = BusPath.veto(device);
		if (path != null) {
			return path;
		}
		DownloadPlan plan = DownloadPlanner.restore(device, snapshot, otherAddress);
		if (!plan.isExecutable()) {
			StringBuilder problems = new StringBuilder();
			for (ResKey problem : plan.getProblems()) {
				if (problems.length() > 0) {
					problems.append(' ');
				}
				problems.append(Resources.getInstance().getString(problem));
			}
			return I18NConstants.RESTORE_REFUSED__PROBLEMS.fill(problems.toString());
		}
		return null;
	}

	private static Map<String, Object> restoreLocked(JobMonitor job, TLObject device, Snapshot snapshot,
			boolean otherAddress, AccessFactory accesses) throws Exception {
		DownloadPlan plan = DownloadPlanner.restore(device, snapshot, otherAddress);
		Program program = DeviceConfiguration.program(device);
		BusPath path = BusPath.of(device);
		String pathText = path.text();
		ResKey label = SnapshotFunctions.label(snapshot);

		job.beginPhase(PHASE_COMPARE);
		job.message(I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(pathText));
		try (DeviceAccess access = accesses.open(path, plan.getIndividualAddress())) {
			Snapshot before;
			try {
				before = read(job, program, plan, access, true);
			} catch (BusException ex) {
				throw new TopLogicException(I18NConstants.ERROR_CONNECTION__PATH_DETAIL.fill(pathText, ex.getErrorKey()), ex);
			}
			if (!before.isComplete()) {
				// Cancelled while reading: nothing was written.
				job.checkCancelled();
			}
			before.setBusPath(pathText);
			if (holds(program, plan, snapshot, before)) {
				ReadOutJob.store(device, before);
				ProgrammingReport report = new ProgrammingReport();
				return report.toMap(I18NConstants.RESTORE_IDENTICAL__DEVICE_SNAPSHOT.fill(plan.getDevice(), label), true);
			}

			job.uncancelable();
			job.beginPhase(PHASE_WRITE);
			DownloadExecutor.Result result = new DownloadExecutor().execute(plan, access, (number, count, operation) -> {
				job.progress(number - 1, count);
				job.message(I18NConstants.PROGRESS_OPERATION__NUMBER_COUNT_TEXT.fill(number, count, operation.description()));
			});
			job.progress(result.done(), result.count());
			if (!result.success()) {
				ProgrammingReport report = new ProgrammingReport();
				report.add("error", I18NConstants.LEVEL_FAILURE, result.failed().step(),
					I18NConstants.REPORT_OPERATION_FAILED__OPERATION_REASON.fill(result.failed().description(), result.reason()));
				return report.toMap(I18NConstants.RESTORE_FAILED__DEVICE_SNAPSHOT_PATH_NUMBER_REASON.fill(plan.getDevice(),
					label, pathText, result.done() + 1, result.reason()), false);
			}

			job.beginPhase(PHASE_READBACK);
			Snapshot after;
			try {
				after = read(job, program, plan, access, false);
			} catch (BusException ex) {
				ProgrammingReport report = new ProgrammingReport();
				report.add("error", I18NConstants.LEVEL_FAILURE, "", ex.getErrorKey());
				return report.toMap(I18NConstants.RESTORE_NOT_READ_BACK__DEVICE_SNAPSHOT_REASON.fill(plan.getDevice(),
					label, ex.getErrorKey()), false);
			}
			after.setBusPath(pathText);
			ReadOutJob.store(device, after);
			DeviceComparison.Result comparison = DeviceComparison.compare(program, plan, after);
			ResKey summary = I18NConstants.RESTORE_DONE__DEVICE_SNAPSHOT_COUNT_SECONDS_EQUAL_BLOCKS.fill(plan.getDevice(),
				label, result.done(), Double.valueOf(result.duration().toMillis() / 1000.0), comparison.identicalBlocks(),
				comparison.blocks().size());
			return CompareJob.report(plan, after, comparison, summary, summary);
		}
	}

	/**
	 * Reads the device with the plan of the restore, with the progress of the job.
	 *
	 * @param cancelable
	 *        Whether a cancel of the job ends the reading (the snapshot is then not complete).
	 */
	private static Snapshot read(JobMonitor job, Program program, DownloadPlan plan, DeviceReader reader,
			boolean cancelable) throws BusException {
		long[] total = { 0 };
		Snapshot result;
		try {
			result = ReadOut.read(program, plan, reader, new ReadOut.Listener() {
				@Override
				public void block(int number, int count, Block block) {
					if (cancelable) {
						job.checkCancelled();
					}
				}

				@Override
				public void read(Block block, int address, long done, long all) {
					if (cancelable) {
						job.checkCancelled();
					}
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
		} catch (AbortExecutionException ex) {
			// Cancelled while connecting.
			return new Snapshot().abort(null);
		}
		if (result.isComplete()) {
			job.progress(total[0], total[0]);
		}
		return result;
	}

	/**
	 * Whether the device as read holds what the restore of the snapshot writes: the snapshot's mask
	 * and program version, every load state machine loaded, the tables at the snapshot's places, every
	 * byte and property of the plan's image.
	 *
	 * @param device
	 *        The device as read with the plan of the restore.
	 */
	public static boolean holds(Program program, DownloadPlan plan, Snapshot snapshot, Snapshot device) {
		if (!device.isComplete() || device.getDescriptor() == null
			|| !device.getDescriptor().equalsIgnoreCase(String.valueOf(snapshot.getDescriptor()))
			|| device.getProgramVersion() == null
			|| !device.getProgramVersion().equalsIgnoreCase(String.valueOf(snapshot.getProgramVersion()))) {
			return false;
		}
		for (Snapshot.LoadState state : device.getLoadStates()) {
			if (state.state() == null || state.state().intValue() != LoadControls.STATE_LOADED) {
				return false;
			}
		}
		if (!java.util.Objects.equals(device.getAddressTable(), snapshot.getAddressTable())
			|| !java.util.Objects.equals(device.getAssociationTable(), snapshot.getAssociationTable())) {
			return false;
		}
		for (PropertyWrite write : plan.getImage().getPropertyWrites()) {
			if (write.objectIndex() != null && !Arrays.equals(write.value(),
				device.getProperties().get(write.objectIndex() + "/" + write.propertyId()))) {
				return false;
			}
		}
		DeviceComparison.Result result = DeviceComparison.compare(program, plan, device);
		return !result.aborted() && result.identicalBlocks() == result.blocks().size();
	}

}
