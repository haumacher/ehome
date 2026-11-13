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
		/** <code>scanVeto(project, area, line, routers, gateway, programming)</code>: why the scan is refused. */
		scanVeto(6),
		/** <code>scanApplyVeto(row)</code>: why the device cannot be created or updated. */
		scanApplyVeto(1),
		/** <code>scanApply(row, candidate)</code>: creates or updates the device; the candidate row may be empty for a unique match. */
		scanApply(2),
		/** <code>scanRefresh(rows)</code>: the rows with the state in the project computed anew. */
		scanRefresh(1),
		/** <code>scanRematch(project)</code>: the result of matching the project's unidentified devices again. */
		scanRematch(1),
		/** <code>scanReadVeto(row)</code>: why the configuration cannot be read back. */
		scanReadVeto(1),
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
				return scanVeto(arguments[0] instanceof TLObject project ? project : null, number(arguments[1]),
					number(arguments[2]), Boolean.TRUE.equals(arguments[3]), Boolean.TRUE.equals(arguments[4]),
					Boolean.TRUE.equals(arguments[5]));
			case scanApplyVeto:
				return applyVeto(arguments[0]);
			case scanApply:
				return apply(arguments[0], arguments[1]);
			case scanRefresh: {
				List<Object> result = new ArrayList<>();
				if (arguments[0] instanceof Collection<?> rows) {
					for (Object row : rows) {
						result.add(row instanceof Map<?, ?> map ? ScanRows.refresh(map) : row);
					}
				}
				return result;
			}
			case scanRematch: {
				if (!(arguments[0] instanceof TLObject project)) {
					return null;
				}
				List<Map<String, Object>> rows = new ArrayList<>(ScanRows.rematch(project));
				return ScanJob.result(com.top_logic.util.Resources.getInstance()
					.getString(I18NConstants.REMATCH_DONE__COUNT.fill(rows.size())), new ArrayList<>(rows), List.of());
			}
			case scanReadVeto:
				return readVeto(arguments[0]);
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
	 */
	public static ResKey scanVeto(TLObject project, Integer area, Integer line, boolean routers, boolean gateway,
			boolean programming) {
		if (project == null) {
			return de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_BUS_PATH;
		}
		ResKey veto = gatewayVeto(project);
		if (veto != null) {
			return veto;
		}
		if (gateway && BusPath.controller(project) != null) {
			return I18NConstants.ERROR_GATEWAY_THROUGH_CONTROLLER;
		}
		if ((area == null) != (line == null) || area != null && (area < 0 || area > 15 || line < 0 || line > 15)) {
			return I18NConstants.ERROR_NO_LINE;
		}
		if (area == null && !routers && !gateway && !programming) {
			return I18NConstants.ERROR_NOTHING_TO_SCAN;
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
	 * Why the device of a row cannot be created or updated, <code>null</code> if it can.
	 */
	public static ResKey applyVeto(Object row) {
		FoundDevice found = ScanRows.found(row);
		CatalogMatch.Match match = ScanRows.match(row);
		if (found == null || match == null) {
			return I18NConstants.ERROR_NO_ROW;
		}
		if (match.kind() == CatalogMatch.Kind.UNIDENTIFIED) {
			return I18NConstants.ERROR_UNIDENTIFIED__REASON.fill(match.reasonKey());
		}
		return null;
	}

	/**
	 * Creates or updates the device of a row in its project.
	 *
	 * @param candidate
	 *        The candidate chosen (a row of the choice dialog), <code>null</code> for a unique match.
	 * @return What happened, for a person.
	 */
	public static ResKey apply(Object row, Object candidate) {
		ResKey veto = applyVeto(row);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		CatalogMatch.Match match = ScanRows.match(row);
		CatalogMatch.Candidate chosen = candidate instanceof Map<?, ?> map
			&& map.get("candidate") instanceof CatalogMatch.Candidate value ? value : null;
		if (chosen == null) {
			if (match.kind() != CatalogMatch.Kind.UNIQUE) {
				throw new TopLogicException(I18NConstants.ERROR_NO_CANDIDATE);
			}
			chosen = match.candidates().get(0);
		}
		FoundDevice found = ScanRows.found(row);
		InstallationBuilder.Outcome outcome = InstallationBuilder.apply(ScanRows.project(row), found, chosen);
		switch (outcome.action()) {
			case CREATED:
				return I18NConstants.APPLY_CREATED__ADDRESS.fill(found.addressText());
			case UPDATED:
				return I18NConstants.APPLY_UPDATED__ADDRESS.fill(found.addressText());
			default:
				return I18NConstants.APPLY_CONFLICT__MESSAGE.fill(outcome.message());
		}
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

	private static Integer number(Object value) {
		return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
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
