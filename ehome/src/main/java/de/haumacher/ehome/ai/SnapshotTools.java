/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import static de.haumacher.ehome.ai.ToolSupport.answer;
import static de.haumacher.ehome.ai.ToolSupport.failure;
import static de.haumacher.ehome.ai.ToolSupport.id;
import static de.haumacher.ehome.ai.ToolSupport.ref;
import static de.haumacher.ehome.ai.ToolSupport.refused;
import static de.haumacher.ehome.ai.ToolSupport.writeDenied;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.layout.view.job.JobPhase;
import com.top_logic.model.TLObject;
import com.top_logic.ai.core.agent.ToolContext;
import com.top_logic.ai.tool.api.P;
import com.top_logic.ai.tool.api.ToolGroups;
import com.top_logic.ai.tool.api.ToolMethod;
import com.top_logic.ai.tool.api.ToolMode;
import com.top_logic.ai.tool.api.ToolResult;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.I18NConstants;
import de.haumacher.ehome.programming.download.ProgrammingFunctions;
import de.haumacher.ehome.programming.download.ProgrammingReport;
import de.haumacher.ehome.programming.download.ReadOutJob;
import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.programming.download.SnapshotFunctions;
import de.haumacher.ehome.programming.download.Snapshots;
import de.haumacher.ehome.scan.SnapshotTakeOver;

/**
 * The assistant's tools for the snapshots of a device (haumacher/ehome #193): what the panel
 * "Snapshots" of the tab "Programming" of a device does, for an agent.
 *
 * <p>
 * {@link #readOutDevice(ToolContext, TLObject)} runs the command "Read out" ({@link ReadOutJob}): reads
 * the device over the project's path to the bus, nothing written into the device, stores the snapshot
 * with the device and compares it with the download image. {@link #compareSnapshot(TLObject)} is the
 * command "Compare" ({@link SnapshotFunctions#compare(TLObject)}), {@link #listSnapshots(TLObject)} the
 * panel's table, and {@link #takeOverSnapshot(ToolContext, TLObject)} the command "Take over"
 * ({@link SnapshotTakeOver}), which writes the project, not the device.
 * </p>
 *
 * <p>
 * The read-out and the take-over have the mode {@link ToolMode#DATA_WRITE}: they change the project
 * only, in the transaction of the turn, committed with the turn's other changes or rolled back with
 * them. The transactions the views' code opens run nested in it. The take-over records the revision
 * the turn commits as the state of the device (<code>Device#downloadRevision</code>, the reference of
 * the changes since the last download) after that commit (TL #30531, see
 * {@link de.haumacher.ehome.scan.ReadBack#takeOver(TLObject, de.haumacher.ehome.scan.ReadBack.Memory)}).
 * The read-out talks to the bus for seconds to minutes inside the open transaction; the transaction
 * holds no database connection and no lock until its commit, the snapshot is a new object.
 * </p>
 */
public class SnapshotTools {

	private static final String DEVICE = "ehome.physical:Device";

	/**
	 * Reads a device out over the bus and stores the snapshot.
	 */
	@ToolMethod(description = "USES THE BUS. Reads a device of an E-Home project out over the bus, as the"
		+ " command \"Read out\" of the device's tab Programming does: the memory of the blocks of its download"
		+ " image, its properties, program version and load states, through the project's path to the bus"
		+ " (its KNXnet/IP gateway or its connected controller). Nothing is written into the device; the"
		+ " project gets a new snapshot of the device (its parameter values and connections are not changed)."
		+ " Waits until the read-out is done - seconds to minutes, depending on the device - and answers the"
		+ " snapshot's id, its label and summary, and the comparison with the download image (as"
		+ " compareSnapshot). Refused while the path to the bus cannot be used, the device has no address or"
		+ " no image, or the account may not change the project. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, check = "readOutRefusal")
	public ToolResult readOutDevice(ToolContext context,
			@P("The device") TLObject device) {
		ToolResult refusal = readOutVeto(context, device);
		if (refusal != null) {
			return refusal;
		}
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		BusPath path;
		Snapshot snapshot;
		try {
			path = BusPath.of(device);
			try {
				snapshot = ReadOutJob.readOut(new ToolMonitor(context), device, plan, path, this::openReader);
			} catch (BusException ex) {
				return failure(Resources.getInstance().getString(I18NConstants.READOUT_FAILED__DEVICE_PATH_REASON
					.fill(plan.getDevice(), path.text(), ex.getErrorKey())) + " Nothing was stored.");
			}
		} catch (TopLogicException ex) {
			return refused(device, ex.getErrorKey());
		}
		TLObject stored = ReadOutJob.store(device, snapshot);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", deviceRef(device));
		result.put("snapshot", snapshot(stored));
		result.put("comparison", comparison(stored));
		return answer("Read out and stored as a snapshot of the device; nothing was written into the device.",
			result);
	}

	/**
	 * The refusal of {@link #readOutDevice(ToolContext, TLObject)} before the user is asked.
	 */
	String readOutRefusal(ToolContext context, TLObject device) {
		return text(readOutVeto(context, device));
	}

	private static ToolResult readOutVeto(ToolContext context, TLObject device) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device. Nothing was read.");
		}
		// The view's access check of "Read out": Write on the device, which is the project's.
		ToolResult denied = writeDenied(context, device);
		if (denied != null) {
			return denied;
		}
		ResKey veto = ProgrammingFunctions.compareVeto(device);
		if (veto != null) {
			return refused(device, veto);
		}
		return null;
	}

	/**
	 * Compares a stored snapshot with the download image.
	 */
	@ToolMethod(description = "Compares a stored snapshot of a device (listSnapshots, readOutDevice) with the"
		+ " device's download image as the project computes it now, as the command \"Compare\" of the panel"
		+ " Snapshots does; the bus is not used and nothing is changed. Answers the summary, whether every"
		+ " block was compared, every run of differing bytes (block, address range, the device's bytes and the"
		+ " plan's bytes in hex), and the messages per block (identical, different, not compared, notes of the"
		+ " read-out), complete. Refused for a snapshot of another program or mask than the device runs now.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult compareSnapshot(
			@P("The snapshot") TLObject snapshot) {
		if (!ExtIds.isA(snapshot, Snapshots.TYPE)) {
			return failure("'" + id(snapshot) + "' is no snapshot of a device.");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", deviceRef(Snapshots.device(snapshot)));
		result.put("snapshot", snapshot(snapshot));
		try {
			result.put("comparison", report(SnapshotFunctions.compare(snapshot)));
		} catch (TopLogicException ex) {
			return refused(snapshot, ex.getErrorKey());
		}
		return answer("The comparison of the snapshot with the download image:", result);
	}

	/**
	 * The snapshots of a device.
	 */
	@ToolMethod(description = "Lists the snapshots stored with a device of an E-Home project, newest first: what"
		+ " \"Read out\" and \"Compare with the device\" read from it, or a file uploaded. Each with its id (for"
		+ " compareSnapshot and takeOverSnapshot), its label (time and path to the bus or file), its summary"
		+ " (bytes read, ranges, bytes not readable), whether the read-out was complete, the individual"
		+ " address it was read at and the account that read it. Reads the project only, never the bus.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult listSnapshots(
			@P("The device") TLObject device) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device.");
		}
		List<Object> snapshots = new ArrayList<>();
		for (TLObject stored : Snapshots.of(device)) {
			snapshots.add(snapshot(stored));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", deviceRef(device));
		result.put("snapshots", snapshots);
		return answer(snapshots.isEmpty() ? "The device has no snapshot; readOutDevice reads one."
			: "The snapshots of the device, newest first:", result);
	}

	/**
	 * The memory of a stored snapshot.
	 */
	@ToolMethod(description = "Answers the memory of a stored snapshot of a device (listSnapshots, readOutDevice)"
		+ " as it was read, for analysing a read-out: every range read, 16 bytes per line in hex with the address"
		+ " of the line, \"??\" for a byte that could not be read; the device descriptor, program identity and"
		+ " version, the load states, the addresses of the address and association tables, the properties read"
		+ " (object/property: hex) and the notes of the read-out. 'from' and 'to' (hex addresses, e.g. \"0100\","
		+ " \"01FF\") limit the memory to that range. Reads the project only, never the bus.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult snapshotMemory(
			@P("The snapshot") TLObject snapshot,
			@P(value = "The first address to answer, in hex; default the first byte read", required = false) String from,
			@P(value = "The last address to answer, in hex; default the last byte read", required = false) String to) {
		if (!ExtIds.isA(snapshot, Snapshots.TYPE)) {
			return failure("'" + id(snapshot) + "' is no snapshot of a device.");
		}
		int first;
		int last;
		try {
			first = from == null || from.isBlank() ? 0 : Integer.parseInt(from.trim(), 16);
			last = to == null || to.isBlank() ? Snapshot.MEMORY_SIZE - 1 : Integer.parseInt(to.trim(), 16);
		} catch (NumberFormatException ex) {
			return failure("'from' and 'to' are hex addresses such as \"0100\".");
		}
		if (first < 0 || last >= Snapshot.MEMORY_SIZE || first > last) {
			return failure("The range " + from + " to " + to + " is no range of the 64 KB address space.");
		}
		Snapshot loaded = Snapshots.load(snapshot);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", deviceRef(Snapshots.device(snapshot)));
		result.put("snapshot", snapshot(snapshot));
		result.put("descriptor", loaded.getDescriptor());
		result.put("program", loaded.getProgram());
		result.put("programVersion", loaded.getProgramVersion());
		List<String> loadStates = new ArrayList<>();
		for (Snapshot.LoadState state : loaded.getLoadStates()) {
			loadStates.add(state.stateMachine() + ": " + (state.state() == null ? "not read" : state.state()));
		}
		result.put("loadStates", loadStates);
		result.put("addressTable", hexAddress(loaded.getAddressTable()));
		result.put("associationTable", hexAddress(loaded.getAssociationTable()));
		List<Object> ranges = new ArrayList<>();
		for (Snapshot.Range range : loaded.ranges()) {
			int start = Math.max(range.address(), first);
			int end = Math.min(range.end() - 1, last);
			if (start > end) {
				continue;
			}
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("from", String.format("%04X", start));
			entry.put("to", String.format("%04X", end));
			List<String> lines = new ArrayList<>();
			for (int line = start; line <= end; line = (line & ~0xF) + 0x10) {
				int lineEnd = Math.min((line & ~0xF) + 0xF, end);
				StringBuilder text = new StringBuilder(String.format("%04X:", line));
				for (int address = line; address <= lineEnd; address++) {
					Integer value = loaded.byteAt(address);
					text.append(' ').append(value == null ? Snapshot.UNKNOWN : String.format("%02X", value));
				}
				lines.add(text.toString());
			}
			entry.put("lines", lines);
			ranges.add(entry);
		}
		result.put("memory", ranges);
		Map<String, Object> properties = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> property : loaded.getProperties().entrySet()) {
			properties.put(property.getKey(), Snapshot.hex(property.getValue()));
		}
		result.put("properties", properties);
		result.put("notes", loaded.getNotes());
		return answer(ranges.isEmpty() ? "The snapshot holds no memory in that range:"
			: "The memory of the snapshot as read:", result);
	}

	private static String hexAddress(Integer address) {
		return address == null ? null : String.format("%04X", address);
	}

	/**
	 * Takes a snapshot over into the project.
	 */
	@ToolMethod(description = "WRITES THE PROJECT, not the bus. Takes a stored snapshot of a device over into"
		+ " the project, as the command \"Take over\" of the panel Snapshots does: the parameter values,"
		+ " connections (group addresses missing in the project are created) and flags the snapshot's memory"
		+ " holds replace the device's in the project; where the device then holds exactly what the project"
		+ " says, the project records it as downloaded. The device itself is not touched. Answers what was"
		+ " taken over and the comparison of the snapshot with the download image afterwards (as"
		+ " compareSnapshot). Refused for a snapshot of another program or mask, and if the account may not"
		+ " change the project. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, check = "takeOverRefusal")
	public ToolResult takeOverSnapshot(ToolContext context,
			@P("The snapshot to take over") TLObject snapshot) {
		ToolResult refusal = takeOverVeto(context, snapshot);
		if (refusal != null) {
			return refusal;
		}
		String text;
		try {
			text = SnapshotTakeOver.takeOver(snapshot);
		} catch (TopLogicException ex) {
			return refused(snapshot, ex.getErrorKey());
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", deviceRef(Snapshots.device(snapshot)));
		result.put("snapshot", snapshot(snapshot));
		result.put("takenOver", text);
		result.put("comparison", comparison(snapshot));
		return answer("Taken over into the project; the device was not touched.", result);
	}

	/**
	 * The refusal of {@link #takeOverSnapshot(ToolContext, TLObject)} before the user is asked.
	 */
	String takeOverRefusal(ToolContext context, TLObject snapshot) {
		return text(takeOverVeto(context, snapshot));
	}

	private static ToolResult takeOverVeto(ToolContext context, TLObject snapshot) {
		if (!ExtIds.isA(snapshot, Snapshots.TYPE)) {
			return failure("'" + id(snapshot) + "' is no snapshot of a device. Nothing was changed.");
		}
		// The view's access check of "Take over": Write on the snapshot, which is the project's.
		ToolResult denied = writeDenied(context, snapshot);
		if (denied != null) {
			return denied;
		}
		return null;
	}

	private static String text(ToolResult refusal) {
		return refusal == null ? null : refusal.text();
	}

	/**
	 * Opens the connection to the device with the given address on the given path (the project's
	 * gateway or controller); a test replaces it.
	 */
	protected DeviceReader openReader(BusPath path, int address) throws BusException {
		return CalimeroReader.open(path, address);
	}

	private static Map<String, Object> deviceRef(TLObject device) {
		return ref(device, GroupAddressConnections.deviceLabel(device));
	}

	/**
	 * A stored snapshot in an answer.
	 */
	private static Map<String, Object> snapshot(TLObject stored) {
		Snapshot snapshot = Snapshots.load(stored);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id(stored));
		result.put("label", Resources.getInstance().getString(SnapshotFunctions.label(snapshot)));
		result.put("summary", SnapshotFunctions.summary(snapshot));
		result.put("time", snapshot.getTime() == null ? null : snapshot.getTime().toInstant().toString());
		result.put("complete", Boolean.valueOf(snapshot.isComplete()));
		result.put("address",
			snapshot.getAddress() == null ? null : DownloadPlanner.formatAddress(snapshot.getAddress()));
		if (snapshot.getBusPath() != null) {
			result.put("busPath", snapshot.getBusPath());
		}
		if (snapshot.getFile() != null) {
			result.put("file", snapshot.getFile());
		}
		result.put("account", snapshot.getAccount());
		return result;
	}

	/**
	 * The comparison of a stored snapshot, or the reason it cannot be compared.
	 */
	private static Map<String, Object> comparison(TLObject stored) {
		try {
			return report(SnapshotFunctions.compare(stored));
		} catch (TopLogicException ex) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("refused", Resources.getInstance().getString(ex.getErrorKey()));
			return result;
		}
	}

	/**
	 * The report of a comparison ({@link ProgrammingReport}) in a fixed order, nothing left out.
	 */
	private static Map<String, Object> report(Map<String, Object> report) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("summary", report.get(ProgrammingReport.SUMMARY));
		result.put("complete", report.get(ProgrammingReport.SUCCESS));
		List<Object> differences = new ArrayList<>();
		for (Object element : (List<?>) report.get(ProgrammingReport.DIFFERENCES)) {
			Map<?, ?> difference = (Map<?, ?>) element;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("block", difference.get("block"));
			entry.put("addresses", difference.get("address"));
			entry.put("device", difference.get("device"));
			entry.put("plan", difference.get("plan"));
			differences.add(entry);
		}
		result.put("differingRuns", Integer.valueOf(differences.size()));
		result.put("differences", differences);
		List<Object> messages = new ArrayList<>();
		for (Object element : (List<?>) report.get(ProgrammingReport.MESSAGES)) {
			Map<?, ?> message = (Map<?, ?>) element;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("severity", message.get("severity"));
			entry.put("level", message.get("level"));
			Object where = message.get("file");
			if (where != null && !"".equals(where)) {
				entry.put("block", where);
			}
			entry.put("message", message.get("message"));
			messages.add(entry);
		}
		result.put("messages", messages);
		return result;
	}

	/**
	 * The progress of a read-out in the run: the messages of {@link ReadOutJob} to the run's progress,
	 * and the run's cancel ending the read-out before its next read.
	 */
	private static final class ToolMonitor implements JobMonitor {

		private final ToolContext _context;

		ToolMonitor(ToolContext context) {
			_context = context;
		}

		@Override
		public void setPhases(List<JobPhase> phases) {
			checkCancelled();
		}

		@Override
		public void beginPhase(String name) {
			checkCancelled();
		}

		@Override
		public void progress(double done, double total) {
			checkCancelled();
		}

		@Override
		public void fraction(double fraction) {
			checkCancelled();
		}

		@Override
		public void indeterminate() {
			checkCancelled();
		}

		@Override
		public void message(ResKey message) {
			checkCancelled();
			if (message == null || _context.progressReporter() == null) {
				return;
			}
			// A message per read would flood the run: the connection and the steps are reported.
			if (message.plain().equals(I18NConstants.PROGRESS_READING_AT__BLOCK_ADDRESS_RANGE.fill(null, null, null).plain())) {
				return;
			}
			_context.progressReporter().progress(Resources.getInstance().getString(message));
		}

		@Override
		public void checkCancelled() {
			_context.cancellationToken().checkCancelled();
		}

		@Override
		public void uncancelable() {
			checkCancelled();
		}
	}

}
