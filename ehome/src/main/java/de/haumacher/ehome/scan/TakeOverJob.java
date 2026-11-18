/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.layout.view.job.JobRunner;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.view.JobInputs;

/**
 * The work of the command "Take over" of the devices found by a scan
 * (<code>&lt;start-job&gt;</code> body of <code>projects/scan.view.xml</code>, haumacher/ehome #180):
 * for each row, creates or updates the device in the project ({@link InstallationBuilder}) and reads
 * its configuration back from the bus in the same go ({@link ReadBack}, nothing is written into the
 * device), so that a device never stays with the defaults of its parameters.
 *
 * <p>
 * The job is started with the rows to take over (a row, the set of the selected rows) and the
 * result of the scan, or, configured with {@link Config#getAll() all}, with the result alone, whose
 * rows are all taken over. A row the catalog does not identify becomes a stub in its line (or updates
 * the stub at its address), without program and without reading its configuration (haumacher/ehome
 * #183); a row the catalog offers several products for, or whose address holds a device of another
 * program, is left alone; a device whose configuration cannot be read (the gateway itself, the bus not
 * reachable, a failed read) is created all the same, a stub at its address is replaced by it.
 * Each row says in its state what happened ({@link ScanRows#outcome(Map, String, boolean, String)}). The
 * result is the result of the scan with these rows, and <code>takeOver</code>, what was done.
 * </p>
 *
 * <p>
 * Each device is created in a transaction of its own and its configuration stored in another one,
 * so a device created stays when its configuration cannot be read. A cancel ends the reading at its
 * next report, or at once while it waits for the device (the accesses of {@link CalimeroReader} are
 * interruptible sections of the job's {@link com.top_logic.basic.thread.CancelScope}); the devices
 * taken over before stand, and the job ends as cancelled with the result of these rows (TL #30195),
 * which the view takes as on success.
 * </p>
 */
public class TakeOverJob implements JobBody {

	/** Key of the result: what the take-over did, for a person. */
	public static final String TAKE_OVER = "takeOver";

	/**
	 * Configuration of {@link TakeOverJob}.
	 */
	public interface Config extends PolymorphicConfiguration<TakeOverJob> {

		@Override
		@ClassDefault(TakeOverJob.class)
		Class<? extends TakeOverJob> getImplementationClass();

		/**
		 * Whether all rows of the result are taken over, instead of the rows given.
		 */
		@Name("all")
		boolean getAll();
	}

	private final boolean _all;

	/**
	 * Creates a {@link TakeOverJob} from configuration.
	 */
	@CalledByReflection
	public TakeOverJob(InstantiationContext context, Config config) {
		_all = config != null && config.getAll();
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		List<Object> inputs = JobInputs.of(arguments, _all ? 1 : 2);
		Object result = inputs.get(inputs.size() - 1);
		List<Map<?, ?>> rows = ScanFunctions.rows(_all ? result : inputs.get(0));
		ResKey veto = ScanFunctions.takeOverVeto(rows);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		if (!(result instanceof Map<?, ?> scan)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_ROW);
		}

		Map<FoundDevice, Map<String, Object>> outcomes = new IdentityHashMap<>();
		Counts counts = new Counts();
		try {
			for (int n = 0, cnt = rows.size(); n < cnt; n++) {
				Map<?, ?> row = rows.get(n);
				FoundDevice found = ScanRows.found(row);
				job.progress(n, cnt);
				job.message(I18NConstants.MESSAGE_TAKING_OVER__ADDRESS_NUMBER_COUNT.fill(found.addressText(), n + 1, cnt));
				outcomes.put(found, takeOver(job, row, counts, outcomes));
			}
			job.progress(rows.size(), rows.size());
		} catch (RuntimeException ex) {
			if (!JobRunner.isAbort(ex) || outcomes.isEmpty()) {
				throw ex;
			}
			// Cancelled: the devices taken over before stand, their rows say so, as does the row of a
			// device created whose configuration was being read.
		}

		List<Object> resultRows = new ArrayList<>();
		for (Map<?, ?> row : ScanFunctions.rows(scan)) {
			Map<String, Object> outcome = outcomes.get(ScanRows.found(row));
			resultRows.add(outcome != null ? outcome : ScanRows.refresh(row));
		}
		// The display of the job shows what the take-over did, the result keeps the summary of the scan.
		String done = Resources.getInstance().getString(
			I18NConstants.TAKE_OVER_DONE__TAKEN_READ_REFUSED.fill(counts._taken, counts._read, counts._refused));
		Map<String, Object> outcome = ScanJob.result(done, (String) scan.get("summary"), resultRows, scan.get("notes"));
		outcome.put(TAKE_OVER, done);
		return outcome;
	}

	/**
	 * What the job did, counted.
	 */
	private static final class Counts {
		int _taken;

		int _read;

		int _refused;
	}

	/**
	 * Takes over the device of one row: creates or updates it, then reads its configuration.
	 *
	 * @param outcomes
	 *        Takes the row of the device once it is created, saying that its configuration is not
	 *        read, for a cancel while it is read.
	 * @return The row saying what happened.
	 */
	private Map<String, Object> takeOver(JobMonitor job, Map<?, ?> row, Counts counts,
			Map<FoundDevice, Map<String, Object>> outcomes) {
		Resources resources = Resources.getInstance();
		FoundDevice found = ScanRows.found(row);
		ResKey veto = ScanFunctions.applyVeto(row);
		if (veto != null) {
			counts._refused++;
			return ScanRows.outcome(row, resources.getString(I18NConstants.TAKE_OVER_REFUSED__REASON.fill(veto)), true, null);
		}
		TLObject project = ScanRows.project(row);
		if (ScanRows.match(row).kind() == CatalogMatch.Kind.UNIDENTIFIED) {
			return takeOverStub(row, project, found, counts);
		}
		CatalogMatch.Candidate candidate = ScanRows.match(row).candidates().get(0);
		InstallationBuilder.Outcome applied;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(
			I18NConstants.COMMIT_TAKE_OVER__DEVICE.fill(found.addressText()))) {
			applied = InstallationBuilder.apply(project, found, candidate);
			tx.commit();
		}
		if (applied.action() == InstallationBuilder.Action.CONFLICT) {
			// Decided by the veto above; the project changed in between.
			counts._refused++;
			return ScanRows.outcome(row, resources.getString(I18NConstants.TAKE_OVER_REFUSED__REASON.fill(
				ResKey.text(applied.message()))), true, null);
		}
		counts._taken++;
		boolean created = applied.action() == InstallationBuilder.Action.CREATED;

		ResKey notRead = ScanFunctions.readVeto(row);
		ReadBack.Report report = null;
		if (notRead == null) {
			outcomes.put(found, notRead(row, created, I18NConstants.TAKE_OVER_CANCELLED));
			TLObject device = applied.device();
			BusPath path = BusPath.of(project);
			job.message(de.haumacher.ehome.programming.download.I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(path.text()));
			ReadBack.Memory memory;
			try (DeviceReader reader = openReader(path, found.address())) {
				memory = ReadBack.read(device, reader, (done, total, what) -> {
					job.checkCancelled();
					job.message(what == null ? null
						: de.haumacher.ehome.programming.download.I18NConstants.PROGRESS_READING__WHAT.fill(what));
				});
			} catch (BusException ex) {
				memory = null;
				notRead = ex.getErrorKey();
			}
			if (memory != null) {
				report = ReadBack.takeOver(device, memory);
			}
		}
		if (report == null) {
			return notRead(row, created, notRead);
		}
		counts._read++;
		String text = resources.getString((created ? I18NConstants.TAKE_OVER_CREATED_READ__SETTINGS_CONNECTIONS
			: I18NConstants.TAKE_OVER_UPDATED_READ__SETTINGS_CONNECTIONS).fill(report.settings(), report.connections()));
		if (!report.identical()) {
			text += " " + resources.getString(I18NConstants.TAKE_OVER_DIFFERENT__PROBLEMS_DIFFERENCES.fill(
				report.problems().size(), report.differences().size()));
		}
		List<String> notes = new ArrayList<>();
		for (ResKey problem : report.problems()) {
			notes.add(resources.getString(problem));
		}
		if (!report.createdAddresses().isEmpty()) {
			notes.add(resources.getString(I18NConstants.READBACK_CREATED_ADDRESSES__ADDRESSES.fill(
				String.join(", ", report.createdAddresses()))));
		}
		if (report.flags() > 0) {
			notes.add(resources.getString(I18NConstants.READBACK_FLAGS__COUNT.fill(report.flags())));
		}
		String details = notes.isEmpty() ? null : String.join("; ", notes);
		return ScanRows.outcome(row, text, !report.identical(), details);
	}

	/**
	 * Takes over a device the catalog does not identify: as a stub in its line, without program; its
	 * configuration is not read (haumacher/ehome #183).
	 */
	private static Map<String, Object> takeOverStub(Map<?, ?> row, TLObject project, FoundDevice found, Counts counts) {
		boolean created = InstallationBuilder.device(project, found.address()) == null;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(
			I18NConstants.COMMIT_TAKE_OVER__DEVICE.fill(found.addressText()))) {
			DeviceStubs.record(project, found);
			tx.commit();
		}
		counts._taken++;
		ResKey reason = ScanRows.match(row).reasonKey();
		return ScanRows.outcome(row, Resources.getInstance().getString((created ? I18NConstants.TAKE_OVER_STUB_CREATED__REASON
			: I18NConstants.TAKE_OVER_STUB_UPDATED__REASON).fill(reason)), true, null);
	}

	/**
	 * The row of a device created or updated whose configuration was not read, for the given reason.
	 */
	private static Map<String, Object> notRead(Map<?, ?> row, boolean created, ResKey reason) {
		return ScanRows.outcome(row, Resources.getInstance().getString((created ? I18NConstants.TAKE_OVER_CREATED_NOT_READ__REASON
			: I18NConstants.TAKE_OVER_UPDATED_NOT_READ__REASON).fill(reason)), true, null);
	}

	/**
	 * Opens the connection to the device with the given address on the given path (the project's
	 * gateway or controller); a test replaces it.
	 */
	protected DeviceReader openReader(BusPath path, int address) throws BusException {
		return CalimeroReader.open(path, address);
	}

}
