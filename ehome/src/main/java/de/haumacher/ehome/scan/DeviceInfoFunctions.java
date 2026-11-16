/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.top_logic.util.Resources;

/**
 * The TL-Script functions of the device info of a device (haumacher/ehome #175): the command in the
 * tab "Programming" (<code>projects/device-programming.view.xml</code>) and its result dialog
 * (<code>projects/device-info.view.xml</code>).
 *
 * <p>
 * Registered in <code>ehomeConf.config.xml</code> under the name of their {@link Function} constant
 * with the {@link Builder} of this class. An "info" is the result of {@link DeviceInfoJob}
 * ({@link DeviceInfo#toMap()}).
 * </p>
 */
public class DeviceInfoFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>deviceInfoVeto(device)</code>: why the device info cannot be read, or nothing. */
		deviceInfoVeto(true),
		/** <code>deviceInfoRows(info)</code>: the identity of the device beside the project's values, as rows of the dialog's table. */
		deviceInfoRows(true),
		/** <code>deviceInfoState(info)</code>: the state of the device (load states, run state, programming mode), as texts. */
		deviceInfoState(true),
		/** <code>deviceInfoNotes(info)</code>: what could not be read, as texts. */
		deviceInfoNotes(true),
		/** <code>deviceInfoSerialVeto(info)</code>: why the serial number read cannot be taken over, or nothing. */
		deviceInfoSerialVeto(true),
		/** <code>deviceInfoTakeSerial(info)</code>: takes the serial number read over into the project's device. */
		deviceInfoTakeSerial(false);

		private final boolean _sideEffectFree;

		Function(boolean sideEffectFree) {
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link DeviceInfoFunctions}.
	 */
	protected DeviceInfoFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new DeviceInfoFunctions(getName(), _function, arguments);
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
	public boolean isSideEffectFree() {
		return _function._sideEffectFree;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (_function == Function.deviceInfoVeto) {
			return arguments[0] instanceof TLObject device ? DeviceInfo.veto(device) : null;
		}
		DeviceInfo info = DeviceInfo.of(arguments[0]);
		switch (_function) {
			case deviceInfoRows:
				return info == null ? List.of() : rows(info);
			case deviceInfoState:
				return info == null ? List.of() : texts(info.state());
			case deviceInfoNotes:
				return info == null ? List.of() : texts(info.notes());
			case deviceInfoSerialVeto:
				return info == null ? I18NConstants.ERROR_INFO_NONE : info.serialVeto();
			case deviceInfoTakeSerial:
				if (info == null) {
					throw new com.top_logic.util.error.TopLogicException(I18NConstants.ERROR_INFO_NONE);
				}
				return info.takeSerial();
			default:
				return null;
		}
	}

	/**
	 * The identity of the device as rows of the dialog's table.
	 */
	public static List<Map<String, Object>> rows(DeviceInfo info) {
		Resources resources = Resources.getInstance();
		List<Map<String, Object>> result = new ArrayList<>();
		for (DeviceInfo.Row row : info.identity()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("name", row.name());
			entry.put("label", resources.getString(row.label()));
			entry.put("project", row.project());
			entry.put("device", row.device());
			entry.put("comparison", resources.getString(row.comparison().label()));
			entry.put("different", Boolean.valueOf(row.comparison() == DeviceInfo.Comparison.DIFFERENT));
			// The color role of the row: a value differing as a warning, one the project does not have as a note.
			entry.put("role", switch (row.comparison()) {
				case DIFFERENT -> "warning";
				case NOT_IN_PROJECT -> "info";
				default -> null;
			});
			result.add(entry);
		}
		return result;
	}

	private static List<String> texts(List<ResKey> keys) {
		Resources resources = Resources.getInstance();
		return keys.stream().map(resources::getString).toList();
	}

	/**
	 * Builder of a {@link DeviceInfoFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<DeviceInfoFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public DeviceInfoFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 1, 1);
			return new DeviceInfoFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
