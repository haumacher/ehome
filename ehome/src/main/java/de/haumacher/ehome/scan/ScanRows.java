/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
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
 *
 * <p>
 * The rows are values: a command changing a row (a choice of the product, a take-over) writes a new
 * result with a copy of it.
 * </p>
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

	/** Key of what could not be read of the device, <code>null</code> if everything was read. */
	public static final String NOTES = "notes";

	/** Key of the tooltip of the row: how the device was read, its mask version, its load states. */
	public static final String DETAILS = "details";

	/** Key of the state of the device in the project, or of what its take-over did. */
	public static final String STATE = "state";

	/** Key of the color role of the state: <code>warning</code> where the take-over refused or failed in part. */
	public static final String STATE_ROLE = "stateRole";

	/** Key of the tooltip of the state: the state with what else the take-over said. */
	public static final String STATE_DETAILS = "stateDetails";

	/** Key of whether the row was taken over. */
	public static final String TAKEN = "taken";

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
		row.put("order", found.orderText());
		row.put("states", texts(found.loadStates(), ", "));
		String notes = texts(found.notes(), "; ");
		row.put(NOTES, notes.isEmpty() ? null : notes);
		row.put(DETAILS, details(found));
		row.put("candidates", candidates(match));
		row.put("matchText", text(match));
		row.put(STATE, state(project, found, match));
		return row;
	}

	/**
	 * A copy of the row with the state in the project computed anew; a row taken over keeps what
	 * the take-over said ({@link #outcome(Map, String, boolean, String)}).
	 */
	public static Map<String, Object> refresh(Map<?, ?> row) {
		Map<String, Object> result = copy(row);
		if (result.get(STATE_ROLE) == null && !Boolean.TRUE.equals(result.get(TAKEN))) {
			result.put(STATE, state((TLObject) row.get(PROJECT), (FoundDevice) row.get(FOUND),
				(CatalogMatch.Match) row.get(MATCH)));
		}
		return result;
	}

	/**
	 * A copy of the row saying in its state what its take-over did.
	 *
	 * @param text
	 *        What happened, for a person.
	 * @param problem
	 *        Whether the device was not taken over or its configuration not read.
	 * @param details
	 *        What else the take-over has to say (what could not be decoded), <code>null</code> for
	 *        nothing.
	 */
	public static Map<String, Object> outcome(Map<?, ?> row, String text, boolean problem, String details) {
		Map<String, Object> result = copy(row);
		result.put(STATE, text);
		result.put(STATE_DETAILS, details == null ? text : text + "; " + details);
		result.put(STATE_ROLE, problem ? "warning" : null);
		result.put(TAKEN, Boolean.TRUE);
		return result;
	}

	/**
	 * A copy of the row of a device the catalog offers several candidates for, with the given
	 * candidate as its unique match (haumacher/ehome #180: chosen in the dialog
	 * <code>projects/scan-choose.view.xml</code>, then taken over as any other row).
	 */
	public static Map<String, Object> choose(Map<?, ?> row, CatalogMatch.Candidate candidate) {
		Map<String, Object> result = copy(row);
		CatalogMatch.Match match = new CatalogMatch.Match(CatalogMatch.Kind.UNIQUE, List.of(candidate), null);
		result.put(MATCH, match);
		result.put(KIND, match.kind().name().toLowerCase());
		result.put("matchText", text(match));
		result.put(STATE, state((TLObject) row.get(PROJECT), (FoundDevice) row.get(FOUND), match));
		result.remove(STATE_ROLE);
		result.remove(STATE_DETAILS);
		result.remove(TAKEN);
		return result;
	}

	private static Map<String, Object> copy(Map<?, ?> row) {
		Map<String, Object> result = new LinkedHashMap<>();
		row.forEach((key, value) -> result.put((String) key, value));
		return result;
	}

	/**
	 * What the device told about itself that the table does not show, for its tooltip: how it was
	 * read, its mask version and its load states (haumacher/ehome #180).
	 */
	private static String details(FoundDevice found) {
		Resources resources = Resources.getInstance();
		List<String> result = new ArrayList<>();
		result.add(resources.getString(found.source() == FoundDevice.Source.LOCAL ? I18NConstants.DETAILS_READ_LOCAL
			: I18NConstants.DETAILS_READ_BUS));
		if (found.descriptor() != null) {
			result.add(resources.getString(I18NConstants.DETAILS_MASK__MASK.fill(found.descriptorText())));
		}
		if (!found.loadStates().isEmpty()) {
			result.add(resources.getString(I18NConstants.DETAILS_STATES__STATES.fill(texts(found.loadStates(), ", "))));
		}
		return String.join("; ", result);
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
				return Resources.getInstance().getString(match.reasonKey());
		}
	}

	/**
	 * The given texts in the reader's language, separated by the given separator.
	 */
	private static String texts(List<ResKey> keys, String separator) {
		Resources resources = Resources.getInstance();
		return String.join(separator, keys.stream().map(resources::getString).toList());
	}

	/**
	 * The device of the project at the address, for a person: new, the device, a conflict, the stub
	 * of an unidentified device, or new and unidentified.
	 */
	static String state(TLObject project, FoundDevice found, CatalogMatch.Match match) {
		TLObject device = InstallationBuilder.device(project, found.address());
		ResKey state;
		if (DeviceStubs.isStub(device)) {
			state = I18NConstants.STATE_STUB__DEVICE.fill(device.tValueByName("name"));
		} else if (device != null) {
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

}
