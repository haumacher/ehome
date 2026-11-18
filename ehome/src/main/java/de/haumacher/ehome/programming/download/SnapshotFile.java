/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * A {@link Snapshot} as a file (haumacher/ehome #193): JSON, with a format name and version.
 *
 * <pre>
 * {
 * 	"format": "ehome-device-snapshot",
 * 	"version": 1,
 * 	"time": "2026-11-18T10:15:00Z",
 * 	"account": "root",
 * 	"busPath": "Gateway 192.168.178.81:3671",
 * 	"device": "1.1.74",
 * 	"mask": "0705",
 * 	"program": "M-0002_A-A002-13-8DB0",
 * 	"programVersion": "0002A00213",
 * 	"loadStates": [{"stateMachine": 1, "state": "01"}, {"stateMachine": 2, "state": null}],
 * 	"addressTable": "4000",
 * 	"associationTable": "4100",
 * 	"ranges": [{"address": "4000", "bytes": "0A11??05"}],
 * 	"properties": [{"object": 3, "property": 13, "bytes": "0002A00213"}],
 * 	"notes": ["..."],
 * 	"complete": true
 * }
 * </pre>
 *
 * <p>
 * Addresses and bytes are hex; a byte that could not be read is {@value Snapshot#UNKNOWN}, a load
 * state not read <code>null</code>. A file is taken into a device only if it was read from a device
 * of the device's program and mask ({@link #veto(TLObject, Snapshot)}).
 * </p>
 */
public final class SnapshotFile {

	/** The value of the member <code>format</code>. */
	public static final String FORMAT = "ehome-device-snapshot";

	/** The version of the format written. */
	public static final int VERSION = 1;

	/** The content type of the file. */
	public static final String CONTENT_TYPE = "application/json";

	private static final int MAX_NESTING = 8;

	private SnapshotFile() {
		// Static helpers.
	}

	/**
	 * The snapshot as JSON.
	 */
	public static String write(Snapshot snapshot) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format", FORMAT);
		root.put("version", Long.valueOf(VERSION));
		root.put("time", snapshot.getTime() == null ? null : snapshot.getTime().toInstant().toString());
		root.put("account", snapshot.getAccount());
		root.put("busPath", snapshot.getBusPath());
		root.put("device", snapshot.getAddress() == null ? null : DownloadPlanner.formatAddress(snapshot.getAddress()));
		root.put("mask", snapshot.getDescriptor());
		root.put("program", snapshot.getProgram());
		root.put("programVersion", snapshot.getProgramVersion());
		List<Object> states = new ArrayList<>();
		for (Snapshot.LoadState state : snapshot.getLoadStates()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("stateMachine", Long.valueOf(state.stateMachine()));
			entry.put("state", state.state() == null ? null : String.format("%02X", state.state()));
			states.add(entry);
		}
		root.put("loadStates", states);
		root.put("addressTable", address(snapshot.getAddressTable()));
		root.put("associationTable", address(snapshot.getAssociationTable()));
		List<Object> ranges = new ArrayList<>();
		for (Snapshot.Range range : snapshot.ranges()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("address", String.format("%04X", range.address()));
			entry.put("bytes", range.hex());
			ranges.add(entry);
		}
		root.put("ranges", ranges);
		List<Object> properties = new ArrayList<>();
		for (Map.Entry<String, byte[]> property : snapshot.getProperties().entrySet()) {
			String[] key = property.getKey().split("/");
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("object", Long.valueOf(key[0]));
			entry.put("property", Long.valueOf(key[1]));
			entry.put("bytes", Snapshot.hex(property.getValue()));
			properties.add(entry);
		}
		root.put("properties", properties);
		root.put("notes", new ArrayList<Object>(snapshot.getNotes()));
		root.put("complete", Boolean.valueOf(snapshot.isComplete()));
		return Json.write(root);
	}

	private static String address(Integer address) {
		return address == null ? null : String.format("%04X", address);
	}

	/**
	 * The snapshot as a file to download, named after the device's address, its program and the
	 * time of the read.
	 */
	public static BinaryData export(Snapshot snapshot) {
		return BinaryDataFactory.createBinaryData(write(snapshot).getBytes(StandardCharsets.UTF_8), CONTENT_TYPE,
			fileName(snapshot));
	}

	/**
	 * The name of the file of a snapshot: <code>1.1.74 M-0002_A-A002-13-8DB0 2026-11-18T10-15-00Z.json</code>.
	 */
	public static String fileName(Snapshot snapshot) {
		String address = snapshot.getAddress() == null ? "device" : DownloadPlanner.formatAddress(snapshot.getAddress());
		String time = snapshot.getTime() == null ? "" : " " + snapshot.getTime().toInstant().toString().replace(':', '-');
		String program = snapshot.getProgram() == null ? "" : " " + snapshot.getProgram();
		return (address + program + time).replaceAll("[\\\\/:*?\"<>|]", "_") + ".json";
	}

	/**
	 * Reads an uploaded file.
	 *
	 * @throws TopLogicException
	 *         If the file is no snapshot file E-Home reads, with the reason.
	 */
	public static Snapshot read(Object file) {
		if (!(file instanceof BinaryData data)) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_FILE_NONE);
		}
		String text;
		try (InputStream in = data.getStream()) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_FILE_UNREADABLE__REASON.fill(ex.getMessage()), ex);
		}
		Snapshot result = parse(text);
		result.setFile(data.getName());
		return result;
	}

	/**
	 * Reads the JSON of a snapshot.
	 *
	 * @throws TopLogicException
	 *         If the text is no snapshot file E-Home reads, with the reason.
	 */
	public static Snapshot parse(String text) {
		Object root;
		try {
			root = Json.parse(text, MAX_NESTING);
		} catch (JsonException ex) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_FILE_NO_JSON__OFFSET_REASON.fill(ex.getOffset(),
				ex.getMessage()));
		}
		if (!(root instanceof Map<?, ?> map) || !FORMAT.equals(map.get("format"))) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_FILE_NO_SNAPSHOT);
		}
		if (!(map.get("version") instanceof Long version) || version.intValue() != VERSION) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_FILE_VERSION__VERSION_SUPPORTED.fill(
				String.valueOf(map.get("version")), VERSION));
		}
		try {
			Snapshot result = new Snapshot();
			String time = text(map, "time");
			result.setTime(time == null ? new Date() : Date.from(Instant.parse(time)));
			result.setAccount(text(map, "account"));
			result.setBusPath(text(map, "busPath"));
			result.setAddress(Snapshots.parseAddress(text(map, "device")));
			result.setDescriptor(hexText(map, "mask"));
			result.setProgram(text(map, "program"));
			result.setProgramVersion(hexText(map, "programVersion"));
			for (Map<?, ?> state : list(map, "loadStates")) {
				String value = text(state, "state");
				result.setLoadState(number(state, "stateMachine"),
					value == null ? null : Integer.valueOf(Integer.parseInt(value, 16)));
			}
			result.setAddressTable(hexNumber(map, "addressTable"));
			result.setAssociationTable(hexNumber(map, "associationTable"));
			for (Map<?, ?> range : list(map, "ranges")) {
				Integer address = hexNumber(range, "address");
				String bytes = text(range, "bytes");
				if (address == null || bytes == null) {
					throw new IllegalArgumentException("range");
				}
				result.putHex(address.intValue(), bytes.replace(" ", "").toUpperCase());
			}
			for (Map<?, ?> property : list(map, "properties")) {
				String bytes = text(property, "bytes");
				result.setProperty(number(property, "object"), number(property, "property"),
					bytes == null ? new byte[0] : Snapshot.parseHex(bytes));
			}
			if (map.get("notes") instanceof List<?> notes) {
				for (Object note : notes) {
					result.note(String.valueOf(note));
				}
			}
			result.setComplete(!Boolean.FALSE.equals(map.get("complete")));
			return result;
		} catch (IllegalArgumentException | DateTimeParseException | ClassCastException ex) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_FILE_INVALID__REASON.fill(String.valueOf(ex.getMessage())),
				ex);
		}
	}

	/**
	 * Why the snapshot cannot be taken into the device: it was read from a device of another
	 * program or mask; <code>null</code> if it fits.
	 *
	 * @param device
	 *        The device (<code>ehome.physical:Device</code>).
	 */
	public static ResKey veto(TLObject device, Snapshot snapshot) {
		Program program = DeviceConfiguration.program(device);
		if (program == null) {
			return I18NConstants.ERROR_NO_PROGRAM;
		}
		if (!Objects.equals(program.getExtId(), snapshot.getProgram())) {
			return I18NConstants.SNAPSHOT_OTHER_PROGRAM__SNAPSHOT_DEVICE.fill(String.valueOf(snapshot.getProgram()),
				program.getExtId());
		}
		MaskVersion mask = program.getMask();
		if (mask != null && snapshot.getDescriptor() != null
			&& !String.format("%04X", mask.getNumber()).equalsIgnoreCase(snapshot.getDescriptor())) {
			return I18NConstants.SNAPSHOT_OTHER_MASK__SNAPSHOT_DEVICE.fill(snapshot.getDescriptor().toUpperCase(),
				String.format("%04X", mask.getNumber()));
		}
		return null;
	}

	private static String text(Map<?, ?> map, String name) {
		Object value = map.get(name);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String text)) {
			throw new IllegalArgumentException(name);
		}
		return text;
	}

	private static String hexText(Map<?, ?> map, String name) {
		String value = text(map, name);
		if (value == null) {
			return null;
		}
		String result = value.replace(" ", "").toUpperCase();
		Snapshot.parseHex(result);
		return result;
	}

	private static Integer hexNumber(Map<?, ?> map, String name) {
		String value = text(map, name);
		return value == null ? null : Integer.valueOf(Integer.parseInt(value, 16));
	}

	private static int number(Map<?, ?> map, String name) {
		if (!(map.get(name) instanceof Long value)) {
			throw new IllegalArgumentException(name);
		}
		return value.intValue();
	}

	private static List<Map<?, ?>> list(Map<?, ?> map, String name) {
		Object value = map.get(name);
		List<Map<?, ?>> result = new ArrayList<>();
		if (value == null) {
			return result;
		}
		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException(name);
		}
		for (Object element : list) {
			if (!(element instanceof Map<?, ?> entry)) {
				throw new IllegalArgumentException(name);
			}
			result.add(entry);
		}
		return result;
	}

}
