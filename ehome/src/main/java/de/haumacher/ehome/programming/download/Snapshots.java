/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.physical.DeviceSnapshot;
import de.haumacher.ehome.model.physical.EhomePhysicalFactory;

/**
 * The snapshots of a device stored with it (<code>ehome.physical:DeviceSnapshot</code>, the
 * composite reference <code>snapshots</code> of the device, haumacher/ehome #193).
 *
 * <p>
 * The memory is stored as text, one line per range read: the address (four hex digits), a space,
 * the bytes in hex, two digits each, {@value Snapshot#UNKNOWN} for a byte that could not be read
 * (<code>4000 0102??04</code>). The properties likewise as <code>object/property bytes</code>, the
 * load states as <code>state-machine state</code>, the notes one per line. The text form keeps the
 * known/unknown information inline (a binary attribute would need a mask beside it), is the form of
 * the file ({@link SnapshotFile}) and reads in a dump of the database; at two characters per byte, a
 * device of 64 KiB is a text of 128 K characters.
 * </p>
 */
public final class Snapshots {

	/** The type of a stored snapshot. */
	public static final String TYPE = "ehome.physical:DeviceSnapshot";

	private Snapshots() {
		// Static helpers.
	}

	/**
	 * Stores the snapshot with the device; must run in a transaction.
	 *
	 * @param device
	 *        The device (<code>ehome.physical:Device</code>).
	 * @return The stored snapshot.
	 */
	public static TLObject store(TLObject device, Snapshot snapshot) {
		DeviceSnapshot result =
			(DeviceSnapshot) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(TYPE));
		Person account = TLContext.currentUser();
		result.setTime(snapshot.getTime());
		result.setAccount(account);
		result.setBusPath(snapshot.getBusPath());
		result.setFile(snapshot.getFile());
		result.setAddress(snapshot.getAddress() == null ? null : DownloadPlanner.formatAddress(snapshot.getAddress()));
		result.setDescriptor(snapshot.getDescriptor());
		result.setProgram(snapshot.getProgram());
		result.setProgramVersion(snapshot.getProgramVersion());
		result.setLoadStates(loadStatesText(snapshot));
		result.setAddressTable(snapshot.getAddressTable());
		result.setAssociationTable(snapshot.getAssociationTable());
		result.setMemory(memoryText(snapshot));
		result.setProperties(propertiesText(snapshot));
		result.setNotes(snapshot.getNotes().isEmpty() ? null : String.join("\n", snapshot.getNotes()));
		result.setComplete(snapshot.isComplete());
		((Device) device).addSnapshot(result);
		return result;
	}

	/**
	 * The snapshot stored in the given object.
	 *
	 * @param stored
	 *        A <code>ehome.physical:DeviceSnapshot</code>.
	 */
	public static Snapshot load(TLObject stored) {
		DeviceSnapshot typed = (DeviceSnapshot) stored;
		Snapshot result = new Snapshot();
		result.setTime(typed.getTime());
		Person person = typed.getAccount();
		if (person != null) {
			result.setAccount(person.getName());
		}
		result.setBusPath(typed.getBusPath());
		result.setFile(typed.getFile());
		result.setAddress(parseAddress(typed.getAddress()));
		result.setDescriptor(typed.getDescriptor());
		result.setProgram(typed.getProgram());
		result.setProgramVersion(typed.getProgramVersion());
		parseLoadStates(result, typed.getLoadStates());
		result.setAddressTable(typed.getAddressTable());
		result.setAssociationTable(typed.getAssociationTable());
		parseMemory(result, typed.getMemory());
		parseProperties(result, typed.getProperties());
		String notes = typed.getNotes();
		if (notes != null && !notes.isEmpty()) {
			for (String note : notes.split("\n")) {
				result.note(note);
			}
		}
		result.setComplete(!Boolean.FALSE.equals(stored.tValue(EhomePhysicalFactory.getCompleteDeviceSnapshotAttr())));
		return result;
	}

	/**
	 * The snapshots of the device, newest first.
	 */
	public static List<TLObject> of(TLObject device) {
		List<TLObject> result = new ArrayList<>(((Device) device).getSnapshots());
		result.sort(Comparator.comparing((TLObject s) -> ((DeviceSnapshot) s).getTime()).reversed());
		return result;
	}

	/**
	 * The device of a stored snapshot.
	 */
	public static TLObject device(TLObject stored) {
		return stored.tContainer();
	}

	/**
	 * An individual address from its text (area.line.device), <code>null</code> for none.
	 *
	 * @throws IllegalArgumentException
	 *         If the text is no individual address.
	 */
	public static Integer parseAddress(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		String[] parts = text.split("\\.");
		if (parts.length != 3) {
			throw new IllegalArgumentException(text);
		}
		int area = Integer.parseInt(parts[0]);
		int line = Integer.parseInt(parts[1]);
		int device = Integer.parseInt(parts[2]);
		if (area < 0 || area > 15 || line < 0 || line > 15 || device < 0 || device > 255) {
			throw new IllegalArgumentException(text);
		}
		return Integer.valueOf(area << 12 | line << 8 | device);
	}

	/**
	 * The memory as text: a line per range.
	 */
	public static String memoryText(Snapshot snapshot) {
		StringBuilder result = new StringBuilder();
		for (Snapshot.Range range : snapshot.ranges()) {
			if (result.length() > 0) {
				result.append('\n');
			}
			result.append(String.format("%04X ", range.address())).append(range.hex());
		}
		return result.toString();
	}

	private static void parseMemory(Snapshot snapshot, String text) {
		if (text == null) {
			return;
		}
		for (String line : text.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			int space = line.indexOf(' ');
			snapshot.putHex(Integer.parseInt(line.substring(0, space), 16), line.substring(space + 1).trim());
		}
	}

	private static String propertiesText(Snapshot snapshot) {
		StringBuilder result = new StringBuilder();
		for (Map.Entry<String, byte[]> entry : snapshot.getProperties().entrySet()) {
			if (result.length() > 0) {
				result.append('\n');
			}
			result.append(entry.getKey()).append(' ').append(Snapshot.hex(entry.getValue()));
		}
		return result.toString();
	}

	private static void parseProperties(Snapshot snapshot, String text) {
		if (text == null) {
			return;
		}
		for (String line : text.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			String[] parts = line.trim().split(" ", 2);
			snapshot.getProperties().put(parts[0], parts.length < 2 ? new byte[0] : Snapshot.parseHex(parts[1]));
		}
	}

	private static String loadStatesText(Snapshot snapshot) {
		StringBuilder result = new StringBuilder();
		for (Snapshot.LoadState state : snapshot.getLoadStates()) {
			if (result.length() > 0) {
				result.append('\n');
			}
			result.append(state.stateMachine()).append(' ').append(
				state.state() == null ? Snapshot.UNKNOWN : String.format("%02X", state.state()));
		}
		return result.toString();
	}

	private static void parseLoadStates(Snapshot snapshot, String text) {
		if (text == null) {
			return;
		}
		for (String line : text.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			String[] parts = line.trim().split(" ");
			snapshot.setLoadState(Integer.parseInt(parts[0]),
				Snapshot.UNKNOWN.equals(parts[1]) ? null : Integer.valueOf(Integer.parseInt(parts[1], 16)));
		}
	}

}
