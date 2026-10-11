/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;

/**
 * The rows of the result table of a scan (<code>projects/scan.view.xml</code>): one dictionary per
 * device found, with the texts the columns show and, for the commands, the project
 * ({@value #PROJECT}), the device as found ({@value #FOUND}) and its match ({@value #MATCH}).
 */
public class ScanRows {

	/** Key of the project the scan is for. */
	public static final String PROJECT = "project";

	/** Key of the {@link FoundDevice}. */
	public static final String FOUND = "found";

	/** Key of the {@link CatalogMatch.Match}. */
	public static final String MATCH = "match";

	/** Key of the kind of the match: <code>unique</code>, <code>choice</code>, <code>unidentified</code>. */
	public static final String KIND = "kind";

	/**
	 * The row of a device found.
	 */
	public static Map<String, Object> row(TLObject project, FoundDevice found, CatalogMatch.Match match) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put(PROJECT, project);
		row.put(FOUND, found);
		row.put(MATCH, match);
		row.put(KIND, match.kind().name().toLowerCase());
		row.put("address", found.addressText());
		row.put("addressValue", SearchExpression.toNumber(found.address()));
		row.put("source", found.source() == FoundDevice.Source.LOCAL ? "KNXnet/IP" : "Bus");
		row.put("descriptor", found.descriptorText());
		row.put("manufacturer", manufacturer(found));
		row.put("serial", found.serialText());
		row.put("programVersion", found.programVersionText());
		row.put("hardwareType", found.hardwareTypeText());
		row.put("orderInfo", found.orderInfo());
		row.put("states", String.join(", ", found.loadStates()));
		row.put("notes", String.join("; ", found.notes()));
		row.put("candidates", candidates(match));
		row.put("matchText", text(match));
		row.put("state", state(project, found, match));
		return row;
	}

	/**
	 * A copy of the row with the state in the project computed anew.
	 */
	public static Map<String, Object> refresh(Map<?, ?> row) {
		Map<String, Object> result = new LinkedHashMap<>();
		row.forEach((key, value) -> result.put((String) key, value));
		result.put("state", state((TLObject) row.get(PROJECT), (FoundDevice) row.get(FOUND),
			(CatalogMatch.Match) row.get(MATCH)));
		return result;
	}

	/**
	 * The candidates of a match as rows of the choice dialog: <code>label</code>, <code>product</code>,
	 * <code>order</code>, <code>program</code> and the {@link CatalogMatch.Candidate} as
	 * <code>candidate</code>.
	 */
	private static List<Map<String, Object>> candidates(CatalogMatch.Match match) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (CatalogMatch.Candidate candidate : match.candidates()) {
			Map<String, Object> row = new LinkedHashMap<>();
			TLObject product = candidate.variant().tContainer();
			row.put("label", candidate.label());
			row.put("product", product == null ? null : product.tValueByName("name"));
			row.put("order", candidate.variant().tValueByName("orderNumber"));
			row.put("program", candidate.program().getExtId());
			row.put("candidate", candidate);
			result.add(row);
		}
		return result;
	}

	/**
	 * The manufacturer of a device for a person: its name in the catalog, else its KNX ID.
	 */
	private static String manufacturer(FoundDevice found) {
		if (found.manufacturerId() == null) {
			return null;
		}
		TLObject manufacturer = CatalogMatch.manufacturer(found.manufacturerId().intValue());
		String id = String.format("M-%04X", found.manufacturerId());
		return manufacturer == null ? id : manufacturer.tValueByName("name") + " (" + id + ")";
	}

	private static String text(CatalogMatch.Match match) {
		switch (match.kind()) {
			case UNIQUE:
				return match.candidates().get(0).label();
			case CHOICE:
				return Resources.getInstance().getString(I18NConstants.MATCH_CHOICE__COUNT.fill(match.candidates().size()));
			default:
				return match.reason();
		}
	}

	/**
	 * The device of the project at the address, for a person: new, the device, a conflict, or
	 * unidentified.
	 */
	static String state(TLObject project, FoundDevice found, CatalogMatch.Match match) {
		TLObject device = InstallationBuilder.device(project, found.address());
		ResKey state;
		if (device != null) {
			Program program = DeviceConfiguration.program(device);
			boolean same = match.candidates().stream().anyMatch(c -> c.program() == program);
			state = same || match.kind() == CatalogMatch.Kind.UNIDENTIFIED
				? I18NConstants.STATE_EXISTS__DEVICE.fill(device.tValueByName("name"))
				: I18NConstants.STATE_CONFLICT__DEVICE_PROGRAM.fill(device.tValueByName("name"),
					program == null ? null : program.getExtId());
		} else if (match.kind() == CatalogMatch.Kind.UNIDENTIFIED) {
			state = I18NConstants.STATE_UNIDENTIFIED;
		} else {
			state = I18NConstants.STATE_NEW;
		}
		return Resources.getInstance().getString(state);
	}

	/**
	 * The device on the bus of a row.
	 */
	public static FoundDevice found(Object row) {
		return row instanceof Map<?, ?> map && map.get(FOUND) instanceof FoundDevice found ? found : null;
	}

	/**
	 * The match of a row.
	 */
	public static CatalogMatch.Match match(Object row) {
		return row instanceof Map<?, ?> map && map.get(MATCH) instanceof CatalogMatch.Match match ? match : null;
	}

	/**
	 * The project of a row.
	 */
	public static TLObject project(Object row) {
		return row instanceof Map<?, ?> map && map.get(PROJECT) instanceof TLObject project ? project : null;
	}

	/**
	 * The rows of the unidentified devices recorded in the project, matched again against the
	 * catalog.
	 */
	public static List<Map<String, Object>> rematch(TLObject project) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object record : (Collection<?>) project.tValueByName("unidentifiedDevices")) {
			FoundDevice found = InstallationBuilder.found((TLObject) record);
			result.add(row(project, found, CatalogMatch.match(found)));
		}
		return result;
	}

}
