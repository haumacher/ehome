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
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.layout.view.job.JobPhase;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;

/**
 * The work of the command "Read the installation" (<code>&lt;start-job&gt;</code> body of
 * <code>projects/scan.view.xml</code>): scans the bus behind the project's gateway with
 * {@link BusScanner} (nothing is written), matches every device found against the catalog
 * ({@link CatalogMatch}) and keeps the unidentified ones in the project
 * ({@link InstallationBuilder#recordUnidentified(TLObject, FoundDevice, String)}).
 *
 * <p>
 * The job is started with the project, the area and the line to scan (empty: no line), and whether
 * to scan for routers, to read the gateway through local device management and to look for the
 * devices in programming mode. Its result is a dictionary: <code>summary</code>, <code>rows</code>
 * (see {@link ScanRows}) and <code>notes</code> (what could not be read).
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
		// The cancel interrupt reaches only the blocking bus accesses, never the database (TL #30196).
		return Uninterruptible.run(() -> work(job, arguments));
	}

	private Object work(JobMonitor job, List<Object> arguments) throws Exception {
		TLObject project = (TLObject) arguments.get(0);
		Integer area = number(arguments.get(1));
		Integer line = number(arguments.get(2));
		boolean routers = Boolean.TRUE.equals(arguments.get(3));
		boolean gateway = Boolean.TRUE.equals(arguments.get(4));
		boolean programming = Boolean.TRUE.equals(arguments.get(5));
		ResKey veto = ScanFunctions.scanVeto(project, area, line, routers, gateway, programming);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		BusPath address = BusPath.of(project);
		String gatewayText = address.text();
		BusScanner.Request request = new BusScanner.Request(line == null ? null : area, line == null ? 0 : line,
			routers, gateway, programming);
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
						job.progress(done, total);
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

		List<Map<String, Object>> rows = new ArrayList<>();
		int identified = 0;
		int choices = 0;
		int unidentified = 0;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction(I18NConstants.COMMIT_SCAN__PROJECT_GATEWAY_FOUND
			.fill(MetaLabelProvider.INSTANCE.getLabel(project), gatewayText, Integer.valueOf(result.devices().size())))) {
			for (FoundDevice found : result.devices()) {
				CatalogMatch.Match match = CatalogMatch.match(project, found);
				switch (match.kind()) {
					case UNIDENTIFIED:
						unidentified++;
						if (InstallationBuilder.device(project, found.address()) == null) {
							InstallationBuilder.recordUnidentified(project, found,
								Resources.getInstance().getString(match.reasonKey()));
						}
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
			tx.commit();
		}
		Resources resources = Resources.getInstance();
		List<String> notes = new ArrayList<>();
		for (ResKey note : result.notes()) {
			notes.add(resources.getString(note));
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
	 * The result of a scan as the view reads it.
	 */
	static Map<String, Object> result(String summary, List<Map<String, Object>> rows, List<String> notes) {
		Map<String, Object> outcome = new LinkedHashMap<>() {
			@Override
			public String toString() {
				return summary;
			}
		};
		outcome.put("summary", summary);
		outcome.put("rows", rows);
		outcome.put("notes", notes);
		return outcome;
	}

	private static Integer number(Object value) {
		return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
	}

}
