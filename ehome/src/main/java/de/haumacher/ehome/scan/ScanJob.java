/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.layout.view.job.JobPhase;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.view.JobInputs;

/**
 * The work of the command "Scan" of the dialog "Scan the bus" (<code>&lt;start-job&gt;</code> body of
 * <code>projects/scan.view.xml</code>): scans the bus behind the project's gateway with
 * {@link BusScanner} (nothing is written), matches every device found against the catalog
 * ({@link CatalogMatch}). The scan changes nothing in the project: "Take over" ({@link TakeOverJob})
 * creates the devices, an unidentified one as a stub (haumacher/ehome #183).
 *
 * <p>
 * The job is started with the project, what to search for ({@link ScanTarget}), the line to scan,
 * as entered (<code>area.line</code>, only for {@link ScanTarget#LINE}), and whether only the new
 * devices are read: then the addresses at which the project has a device are not read, only noted
 * (haumacher/ehome #180). Its result is a dictionary: <code>summary</code>, <code>rows</code> (see
 * {@link ScanRows}) and <code>notes</code> (what could not be read, the devices not read).
 * </p>
 *
 * <p>
 * A cancel ends the scan at its next report, or at once while it waits for the bus (the accesses of
 * {@link CalimeroScanBus} are interruptible sections of the job's
 * {@link com.top_logic.basic.thread.CancelScope}, TL #30196). Once the bus is read, the job is past
 * its point of no return ({@link JobMonitor#uncancelable()}): it ends with the devices found.
 * </p>
 */
public class ScanJob implements JobBody {

	/**
	 * Configuration of {@link ScanJob}.
	 */
	public interface Config extends PolymorphicConfiguration<ScanJob> {

		@Override
		@ClassDefault(ScanJob.class)
		Class<? extends ScanJob> getImplementationClass();
	}

	/**
	 * Creates a {@link ScanJob} from configuration.
	 */
	@CalledByReflection
	public ScanJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		List<Object> inputs = JobInputs.of(arguments, 4);
		TLObject project = (TLObject) inputs.get(0);
		ScanTarget target = inputs.get(1) instanceof ScanTarget value ? value : null;
		String lineText = inputs.get(2) instanceof String value ? value : null;
		boolean newOnly = Boolean.TRUE.equals(inputs.get(3));
		ResKey veto = ScanFunctions.scanVeto(project, target, lineText);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		BusPath address = BusPath.of(project);
		String gatewayText = address.text();
		BusScanner.Request request = target == ScanTarget.LINE ? BusScanner.Request.of(BusScanner.Line.parse(lineText))
			: BusScanner.Request.of(target);
		if (newOnly) {
			request = request.skipping(InstallationBuilder.addresses(project));
		}
		job.setPhases(phases(request));

		job.message(de.haumacher.ehome.programming.download.I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(gatewayText));
		BusScanner.Result result;
		try (ScanBus bus = openBus(address)) {
			result = BusScanner.scan(bus, request, new BusScanner.Listener() {
					@Override
					public void phase(String name) {
						job.beginPhase(name);
					}

					@Override
					public void message(ResKey text) {
						job.message(text);
					}

					@Override
					public void progress(int done, int total) {
						if (total == 0) {
							// Nothing to do in this phase: it is complete.
							job.fraction(1);
						} else {
							job.progress(done, total);
						}
					}

					@Override
					public void indeterminate() {
						job.indeterminate();
					}

					@Override
					public void checkCancelled() {
						job.checkCancelled();
					}
				});
		} catch (BusException ex) {
			throw new TopLogicException(I18NConstants.SCAN_FAILED__GATEWAY_REASON.fill(gatewayText, ex.getErrorKey()), ex);
		}
		// The last phase is complete, also where it had nothing to do or reported no progress.
		job.fraction(1);

		// The point of no return (TL #30194): the bus is read, the job ends with the devices found.
		job.uncancelable();
		List<Map<String, Object>> rows = new ArrayList<>();
		int identified = 0;
		int choices = 0;
		int unidentified = 0;
		for (FoundDevice found : result.devices()) {
			CatalogMatch.Match match = CatalogMatch.match(project, found);
			switch (match.kind()) {
				case UNIDENTIFIED:
					unidentified++;
					break;
				case CHOICE:
					choices++;
					break;
				case UNIQUE:
					identified++;
					break;
			}
			rows.add(ScanRows.row(project, found, match));
		}
		Resources resources = Resources.getInstance();
		List<String> notes = new ArrayList<>();
		for (ResKey note : result.notes()) {
			notes.add(resources.getString(note));
		}
		if (!result.skipped().isEmpty()) {
			notes.add(resources.getString(I18NConstants.NOTE_KNOWN_NOT_READ__ADDRESSES.fill(
				String.join(", ", result.skipped().stream().map(FoundDevice::formatAddress).toList()))));
		}
		if (!result.inProgrammingMode().isEmpty()) {
			notes.add(resources.getString(I18NConstants.NOTE_PROGRAMMING_MODE__ADDRESSES.fill(
				String.join(", ", result.inProgrammingMode().stream().map(FoundDevice::formatAddress).toList()))));
		}
		String summary = resources.getString(I18NConstants.SCAN_DONE__GATEWAY_FOUND_IDENTIFIED_CHOICES_UNIDENTIFIED
			.fill(gatewayText, result.devices().size(), identified, choices, unidentified));
		return result(summary, rows, notes);
	}

	/**
	 * The steps of a scan of the given request, as the job status shows them.
	 */
	static List<JobPhase> phases(BusScanner.Request request) {
		List<JobPhase> result = new ArrayList<>();
		for (String name : BusScanner.phases(request)) {
			result.add(new JobPhase(name, switch (name) {
				case BusScanner.PHASE_GATEWAY -> I18NConstants.PHASE_GATEWAY;
				case BusScanner.PHASE_LINE -> I18NConstants.PHASE_LINE;
				case BusScanner.PHASE_ROUTERS -> I18NConstants.PHASE_ROUTERS;
				case BusScanner.PHASE_PROGRAMMING -> I18NConstants.PHASE_PROGRAMMING;
				default -> I18NConstants.PHASE_DEVICES;
			}));
		}
		return result;
	}

	/**
	 * Opens the bus on the given path (the project's gateway or controller); a test replaces it.
	 */
	protected ScanBus openBus(BusPath path) throws BusException {
		return CalimeroScanBus.open(path);
	}

	/**
	 * The result of a scan as the view reads it; the display of the job shows its summary.
	 */
	static Map<String, Object> result(String summary, List<?> rows, Object notes) {
		return result(summary, summary, rows, notes);
	}

	/**
	 * The result of a scan as the view reads it, shown by the display of the job producing it as the
	 * given label.
	 */
	static Map<String, Object> result(String label, String summary, List<?> rows, Object notes) {
		Map<String, Object> outcome = new LinkedHashMap<>() {
			@Override
			public String toString() {
				return label;
			}
		};
		outcome.put("summary", summary);
		outcome.put("rows", rows);
		outcome.put("notes", notes);
		return outcome;
	}

}
