/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.xml.TagUtil;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;

import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * The TL-Script functions of the programming tab of a device: the plan of its download for the
 * preview, the reasons a command is refused, and the confirmation texts.
 *
 * <p>
 * Each function takes the device (<code>ehome.physical:Device</code>) and is registered in
 * <code>ehomeConf.config.xml</code> under the name of its {@link Function} constant with the
 * {@link Builder} of this class. The plan depends on the device's settings and connections, so a
 * view calling them names those types in <code>observed-types</code>.
 * </p>
 */
public class ProgrammingFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>downloadPlanRows(device)</code>: the operations of the full download, as rows. */
		downloadPlanRows,
		/** <code>downloadSummary(device)</code>: the number of operations and bytes, and the procedure. */
		downloadSummary,
		/** <code>downloadProblems(device)</code>: why the full download is refused (texts). */
		downloadProblems,
		/** <code>downloadImageDump(device)</code>: the download image as HTML (a preformatted text). */
		downloadImageDump,
		/** <code>downloadVeto(device)</code>: why the full download command is disabled, or nothing. */
		downloadVeto,
		/** <code>addressVeto(device)</code>: why the address command is disabled, or nothing. */
		addressVeto,
		/** <code>compareVeto(device)</code>: why the compare command is disabled, or nothing. */
		compareVeto,
		/** <code>downloadConfirmation(device)</code>: the question before a full download. */
		downloadConfirmation,
		/** <code>addressConfirmation(device)</code>: the question before writing the address. */
		addressConfirmation;
	}

	private final Function _function;

	/**
	 * Creates a {@link ProgrammingFunctions}.
	 */
	protected ProgrammingFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ProgrammingFunctions(getName(), _function, arguments);
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
		if (!(arguments[0] instanceof TLObject device)) {
			return null;
		}
		switch (_function) {
			case downloadPlanRows:
				return rows(DownloadPlanner.plan(device, DownloadPlan.Kind.FULL));
			case downloadSummary: {
				DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
				return plan.isExecutable() ? I18NConstants.PLAN_SUMMARY__COUNT_BYTES_PROCEDURE.fill(
					plan.getOperations().size(), plan.bytesWritten(), plan.getProcedure()) : null;
			}
			case downloadProblems: {
				List<String> result = new ArrayList<>();
				for (ResKey problem : DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getProblems()) {
					result.add(Resources.getInstance().getString(problem));
				}
				return result;
			}
			case downloadImageDump: {
				DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
				return plan.getImage() == null ? null : "<pre>" + TagUtil.encodeXML(plan.getImage().dump()) + "</pre>";
			}
			case downloadVeto:
				return downloadVeto(device, DownloadPlan.Kind.FULL);
			case addressVeto:
				return downloadVeto(device, DownloadPlan.Kind.ADDRESS);
			case compareVeto:
				return compareVeto(device);
			case downloadConfirmation:
				return I18NConstants.CONFIRM_DOWNLOAD__DEVICE_ADDRESS_GATEWAY_CHANGES.fill(device.tValueByName("name"),
					address(device), Gateways.of(device), changes(device));
			case addressConfirmation:
				return I18NConstants.CONFIRM_ADDRESS__ADDRESS_GATEWAY.fill(address(device), Gateways.of(device));
		}
		return null;
	}

	/**
	 * The device among the values a job is started with: the first object.
	 */
	static TLObject device(List<Object> arguments) {
		for (Object argument : arguments) {
			if (argument instanceof TLObject device) {
				return device;
			}
		}
		throw new IllegalArgumentException("No device among " + arguments);
	}

	/**
	 * What the download changes in the device, for a person: the number of changes since its last
	 * download.
	 */
	private static ResKey changes(TLObject device) {
		DeviceChanges changes = DeviceChanges.sinceDownload(device);
		if (changes == null) {
			return I18NConstants.CHANGES_NO_DOWNLOAD;
		}
		return I18NConstants.CHANGES_SINCE_DOWNLOAD__COUNT.fill(changes.getCount());
	}

	private static String address(TLObject device) {
		Integer address = DownloadImage.individualAddress(device);
		return address == null ? "?" : DownloadPlanner.formatAddress(address);
	}

	/**
	 * Why a download of the given kind into the device is refused, <code>null</code> if it can be
	 * started.
	 */
	public static ResKey downloadVeto(TLObject device, DownloadPlan.Kind kind) {
		if (!CalimeroWriter.writeEnabled()) {
			return I18NConstants.ERROR_WRITE_DISABLED;
		}
		ResKey gateway = gatewayVeto(device);
		if (gateway != null) {
			return gateway;
		}
		DownloadPlan plan = DownloadPlanner.plan(device, kind);
		if (!plan.isExecutable()) {
			List<String> problems = new ArrayList<>();
			for (ResKey problem : plan.getProblems()) {
				problems.add(Resources.getInstance().getString(problem));
			}
			return I18NConstants.ERROR_REFUSED__PROBLEMS.fill(String.join(" ", problems));
		}
		return null;
	}

	/**
	 * Why a comparison of the device with its image is refused, <code>null</code> if it can be
	 * started.
	 */
	public static ResKey compareVeto(TLObject device) {
		ResKey gateway = gatewayVeto(device);
		if (gateway != null) {
			return gateway;
		}
		if (DownloadImage.individualAddress(device) == null) {
			return I18NConstants.ERROR_NO_ADDRESS;
		}
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		if (plan.getImage() == null) {
			return plan.getProblems().isEmpty() ? I18NConstants.ERROR_NO_PROGRAM : plan.getProblems().get(0);
		}
		if (!plan.getImage().getProblems().isEmpty()) {
			return I18NConstants.ERROR_IMAGE__PROBLEM.fill(String.join(" ", plan.getImage().getProblems()));
		}
		return null;
	}

	private static ResKey gatewayVeto(TLObject device) {
		String gateway = Gateways.of(device);
		if (gateway == null) {
			return I18NConstants.ERROR_NO_GATEWAY;
		}
		if (Gateways.parse(gateway) == null) {
			return I18NConstants.ERROR_BAD_GATEWAY__VALUE.fill(gateway);
		}
		return null;
	}

	private static List<Map<String, Object>> rows(DownloadPlan plan) {
		List<Map<String, Object>> result = new ArrayList<>();
		int number = 1;
		for (Operation operation : plan.getOperations()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("number", SearchExpression.toNumber(number++));
			row.put("step", operation.step());
			row.put("operation", operation.describe());
			row.put("bytes", SearchExpression.toNumber(operation.bytesWritten()));
			result.add(row);
		}
		for (Block block : plan.getUnloadedBlocks()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("number", null);
			row.put("step", "");
			row.put("operation", Resources.getInstance().getString(I18NConstants.NOTE_NOT_LOADED__BLOCK.fill(
				String.format("%s %04X-%04X", block.name(), block.address(), block.end() - 1))));
			row.put("bytes", SearchExpression.toNumber(0));
			result.add(row);
		}
		return result;
	}

	/**
	 * Builder of a {@link ProgrammingFunctions} function: the {@link Function} named as the function
	 * is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ProgrammingFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ProgrammingFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 1, 1);
			return new ProgrammingFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
