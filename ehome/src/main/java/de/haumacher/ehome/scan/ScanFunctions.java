/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroWriter;

/**
 * The TL-Script functions of the scan of an installation (<code>projects/scan.view.xml</code>).
 *
 * <p>
 * Registered in <code>ehomeConf.config.xml</code> under the name of their {@link Function} constant
 * with the {@link Builder} of this class. A row is a dictionary of {@link ScanRows}.
 * </p>
 */
public class ScanFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>scanVeto(project, target, line)</code>: why the scan is refused. */
		scanVeto(3),
		/** <code>scanTargets()</code>: what a scan can search for ({@link ScanTarget}), in the order offered. */
		scanTargets(0),
		/** <code>scanTarget(name)</code>: the {@link ScanTarget} of the given name. */
		scanTarget(1),
		/**
		 * <code>scanTakeOverVeto(rows)</code>: why none of the rows can be taken over; the rows are a
		 * row, several or the result of a scan.
		 */
		scanTakeOverVeto(1),
		/**
		 * <code>scanRow(selection)</code>: the row of a selection of exactly one row,
		 * <code>null</code> otherwise (a row is a dictionary, whose size is not the size of the
		 * selection).
		 */
		scanRow(1),
		/** <code>scanChooseVeto(row)</code>: why no product can be chosen for the device of the row. */
		scanChooseVeto(1),
		/** <code>scanChoose(result, row, candidate)</code>: the result with the candidate chosen for the row. */
		scanChoose(3),
		/** <code>scanRematch(project)</code>: the result of matching the project's unidentified devices again. */
		scanRematch(1),
		/** <code>scanLine(node)</code>: the line of a node of the topology as entered for a scan (<code>1.1</code>). */
		scanLine(1),
		/** <code>scanLedVeto(row)</code>: why the programming mode cannot be switched. */
		scanLedVeto(1),
		/** <code>scanLedConfirmation(row, on)</code>: the question before switching the programming mode. */
		scanLedConfirmation(2);

		private final int _arguments;

		Function(int arguments) {
			_arguments = arguments;
		}

		/**
		 * The number of arguments.
		 */
		public int arguments() {
			return _arguments;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link ScanFunctions}.
	 */
	protected ScanFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ScanFunctions(getName(), _function, arguments);
	}

	@Override
	public Object getId() {
		return _function;
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		switch (_function) {
			case scanVeto:
				return scanVeto(arguments[0] instanceof TLObject project ? project : null,
					arguments[1] instanceof ScanTarget target ? target : null,
					arguments[2] instanceof String line ? line : null);
			case scanTargets:
				return List.of(ScanTarget.values());
			case scanTarget:
				return ScanTarget.valueOf(String.valueOf(arguments[0]));
			case scanTakeOverVeto:
				return takeOverVeto(arguments[0]);
			case scanRow:
				return singleRow(arguments[0]);
			case scanChooseVeto:
				return chooseVeto(arguments[0]);
			case scanChoose:
				return choose(arguments[0], arguments[1], arguments[2]);
			case scanRematch: {
				if (!(arguments[0] instanceof TLObject project)) {
					return null;
				}
				List<Map<String, Object>> rows = new ArrayList<>(ScanRows.rematch(project));
				return ScanJob.result(com.top_logic.util.Resources.getInstance()
					.getString(I18NConstants.REMATCH_DONE__COUNT.fill(rows.size())), new ArrayList<>(rows), List.of());
			}
			case scanLine:
				return lineText(arguments[0] instanceof TLObject node ? node : null);
			case scanLedVeto:
				return ledVeto(arguments[0]);
			case scanLedConfirmation: {
				FoundDevice found = ScanRows.found(arguments[0]);
				if (found == null) {
					return null;
				}
				return (Boolean.TRUE.equals(arguments[1]) ? I18NConstants.CONFIRM_LED_ON__ADDRESS_GATEWAY
					: I18NConstants.CONFIRM_LED_OFF__ADDRESS_GATEWAY).fill(found.addressText(),
					BusPath.describe(ScanRows.project(arguments[0])));
			}
		}
		return null;
	}

	/**
	 * Why a scan is refused, <code>null</code> if it can be started.
	 *
	 * @param target
	 *        What to search for.
	 * @param line
	 *        The line to scan as entered (<code>area.line</code>), needed for
	 *        {@link ScanTarget#LINE} only.
	 */
	public static ResKey scanVeto(TLObject project, ScanTarget target, String line) {
		if (project == null) {
			return de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_BUS_PATH;
		}
		ResKey veto = gatewayVeto(project);
		if (veto != null) {
			return veto;
		}
		if (target == null) {
			return I18NConstants.ERROR_NOTHING_TO_SCAN;
		}
		if (target == ScanTarget.GATEWAY && BusPath.controller(project) != null) {
			return I18NConstants.ERROR_GATEWAY_THROUGH_CONTROLLER;
		}
		if (target == ScanTarget.LINE && BusScanner.Line.parse(line) == null) {
			return I18NConstants.ERROR_NO_LINE;
		}
		return null;
	}

	/**
	 * Why the path to the bus of the project cannot be used now ({@link BusPath#veto(TLObject)}): its
	 * gateway or its controller (haumacher/ehome #148).
	 */
	private static ResKey gatewayVeto(TLObject project) {
		return BusPath.veto(project);
	}

	/**
	 * The line of a node of the topology as a scan takes it (<code>area.line</code>, e.g.
	 * <code>1.1</code>): a line by its number and the number of its area; <code>null</code> for any
	 * other node (an area, the project) and a line without numbers.
	 */
	public static String lineText(TLObject node) {
		if (node == null || !node.tValid() || !ExtIds.isA(node, "ehome.physical:Line")) {
			return null;
		}
		TLObject area = node.tContainer();
		if (area == null || !(area.tValueByName("address") instanceof Integer areaNumber)
			|| !(node.tValueByName("address") instanceof Integer lineNumber)) {
			return null;
		}
		BusScanner.Line line = BusScanner.Line.parse(areaNumber + "." + lineNumber);
		return line == null ? null : line.toString();
	}

	/**
	 * The row of a selection of exactly one row, <code>null</code> for none or several.
	 */
	public static Map<?, ?> singleRow(Object selection) {
		if (selection instanceof Map<?, ?> row) {
			return row.containsKey(ScanRows.FOUND) ? row : null;
		}
		List<Map<?, ?>> rows = selection instanceof Collection<?> ? rows(selection) : List.of();
		return rows.size() == 1 ? rows.get(0) : null;
	}

	/**
	 * The rows given to a command: a row, a collection of rows (a selection of several), or the
	 * result of a scan (all its rows).
	 */
	public static List<Map<?, ?>> rows(Object rows) {
		List<Map<?, ?>> result = new ArrayList<>();
		if (rows instanceof Map<?, ?> map) {
			if (map.containsKey(ScanRows.FOUND)) {
				result.add(map);
			} else if (map.get("rows") instanceof Collection<?> all) {
				return rows(all);
			}
		} else if (rows instanceof Collection<?> collection) {
			for (Object row : collection) {
				if (row instanceof Map<?, ?> map && map.containsKey(ScanRows.FOUND)) {
					result.add(map);
				}
			}
		}
		return result;
	}

	/**
	 * Why none of the given rows can be taken over, <code>null</code> if one can at least; the others
	 * are left alone by the take-over, each saying why ({@link TakeOverJob}).
	 *
	 * @param rows
	 *        See {@link #rows(Object)}.
	 */
	public static ResKey takeOverVeto(Object rows) {
		List<Map<?, ?>> list = rows(rows);
		if (list.isEmpty()) {
			return I18NConstants.ERROR_NO_ROW;
		}
		List<String> reasons = new ArrayList<>();
		for (Map<?, ?> row : list) {
			ResKey veto = applyVeto(row);
			if (veto == null) {
				return null;
			}
			if (list.size() == 1) {
				return veto;
			}
			reasons.add(ScanRows.found(row).addressText() + ": " + com.top_logic.util.Resources.getInstance().getString(veto));
		}
		return I18NConstants.ERROR_NONE_TAKEN_OVER__REASONS.fill(String.join("; ", reasons));
	}

	/**
	 * Why the device of a row cannot be created or updated, <code>null</code> if it can: it must be
	 * identified by the catalog as exactly one product and program (one chosen of several), and a
	 * device of the project at its address must run that program.
	 */
	public static ResKey applyVeto(Object row) {
		FoundDevice found = ScanRows.found(row);
		CatalogMatch.Match match = ScanRows.match(row);
		TLObject project = ScanRows.project(row);
		if (found == null || match == null || project == null) {
			return I18NConstants.ERROR_NO_ROW;
		}
		switch (match.kind()) {
			case UNIDENTIFIED:
				return I18NConstants.ERROR_UNIDENTIFIED__REASON.fill(match.reasonKey());
			case CHOICE:
				return I18NConstants.ERROR_CHOOSE__COUNT.fill(match.candidates().size());
			case UNIQUE:
				break;
		}
		TLObject device = InstallationBuilder.device(project, found.address());
		if (device != null) {
			Program program = DeviceConfiguration.program(device);
			if (match.candidates().get(0).program() != program) {
				return I18NConstants.ERROR_OTHER_PROGRAM__ADDRESS_PROGRAM.fill(found.addressText(),
					program == null ? null : program.getExtId());
			}
		}
		return null;
	}

	/**
	 * Why no product can be chosen for the device of a row, <code>null</code> if the catalog offers
	 * several.
	 */
	public static ResKey chooseVeto(Object row) {
		CatalogMatch.Match match = ScanRows.match(row);
		if (match == null) {
			return I18NConstants.ERROR_NO_ROW;
		}
		if (match.kind() != CatalogMatch.Kind.CHOICE) {
			return I18NConstants.ERROR_NO_CHOICE;
		}
		return null;
	}

	/**
	 * The result of a scan with the given candidate (a row of the choice dialog) chosen for the
	 * given row.
	 */
	public static Map<String, Object> choose(Object result, Object row, Object candidate) {
		if (!(result instanceof Map<?, ?> outcome) || !(row instanceof Map<?, ?> chosenRow)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_ROW);
		}
		CatalogMatch.Candidate chosen = candidate instanceof Map<?, ?> map
			&& map.get("candidate") instanceof CatalogMatch.Candidate value ? value : null;
		CatalogMatch.Match match = ScanRows.match(row);
		if (chosen == null || match == null || !match.candidates().contains(chosen)) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CANDIDATE);
		}
		FoundDevice found = ScanRows.found(row);
		List<Object> rows = new ArrayList<>();
		for (Map<?, ?> other : rows(outcome)) {
			rows.add(ScanRows.found(other) == found ? ScanRows.choose(chosenRow, chosen) : other);
		}
		return ScanJob.result((String) outcome.get("summary"), rows, outcome.get("notes"));
	}

	/**
	 * Why the configuration of the device of a row cannot be read back, <code>null</code> if it can.
	 */
	public static ResKey readVeto(Object row) {
		FoundDevice found = ScanRows.found(row);
		TLObject project = ScanRows.project(row);
		if (found == null || project == null) {
			return I18NConstants.ERROR_NO_ROW;
		}
		if (found.source() == FoundDevice.Source.LOCAL) {
			return I18NConstants.ERROR_LOCAL__ADDRESS.fill(found.addressText());
		}
		ResKey gateway = gatewayVeto(project);
		if (gateway != null) {
			return gateway;
		}
		TLObject device = InstallationBuilder.device(project, found.address());
		if (device == null) {
			return I18NConstants.ERROR_NO_DEVICE__ADDRESS.fill(found.addressText());
		}
		Program program = DeviceConfiguration.program(device);
		CatalogMatch.Match match = ScanRows.match(row);
		if (match == null || match.candidates().stream().noneMatch(c -> c.program() == program)) {
			return I18NConstants.ERROR_OTHER_PROGRAM__ADDRESS_PROGRAM.fill(found.addressText(),
				program == null ? null : program.getExtId());
		}
		return null;
	}

	/**
	 * Why the programming mode of the device of a row cannot be switched, <code>null</code> if it
	 * can.
	 */
	public static ResKey ledVeto(Object row) {
		FoundDevice found = ScanRows.found(row);
		TLObject project = ScanRows.project(row);
		if (found == null || project == null) {
			return I18NConstants.ERROR_NO_ROW;
		}
		if (!CalimeroWriter.writeEnabled()) {
			return de.haumacher.ehome.programming.download.I18NConstants.ERROR_WRITE_DISABLED;
		}
		if (found.source() == FoundDevice.Source.LOCAL) {
			return I18NConstants.ERROR_LOCAL__ADDRESS.fill(found.addressText());
		}
		return gatewayVeto(project);
	}

	/**
	 * Builder of a {@link ScanFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ScanFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ScanFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function.arguments(), _function.arguments());
			return new ScanFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
