/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.List;

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
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.download.Snapshot;
import de.haumacher.ehome.programming.download.SnapshotFile;
import de.haumacher.ehome.programming.download.Snapshots;

/**
 * The take-over of a stored snapshot into its device (haumacher/ehome #193): the decoding of
 * "Take over" ({@link ReadBack#takeOver(TLObject, ReadBack.Memory)}) on what the snapshot holds,
 * without the bus.
 *
 * <p>
 * As the TL-Script function <code>snapshotTakeOver(snapshot)</code> (registered in
 * <code>ehomeConf.config.xml</code>): returns what was taken over, for a person. It runs transactions
 * of its own and must not run in one.
 * </p>
 */
public class SnapshotTakeOver extends GenericMethod {

	/**
	 * Creates a {@link SnapshotTakeOver}.
	 */
	protected SnapshotTakeOver(String name, SearchExpression[] arguments) {
		super(name, arguments);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new SnapshotTakeOver(getName(), arguments);
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return false;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		if (!(arguments[0] instanceof TLObject stored)) {
			return null;
		}
		return takeOver(stored);
	}

	/**
	 * Takes the stored snapshot over into its device.
	 *
	 * @return What was taken over, for a person.
	 * @throws TopLogicException
	 *         If the snapshot was read from a device of another program or mask, with the reason.
	 */
	public static String takeOver(TLObject stored) {
		TLObject device = Snapshots.device(stored);
		Snapshot snapshot = Snapshots.load(stored);
		ResKey veto = SnapshotFile.veto(device, snapshot);
		if (veto != null) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_TAKE_OVER_REFUSED__REASON.fill(veto));
		}
		ReadBack.Report report = ReadBack.takeOver(device, ReadBack.Memory.of(snapshot, device));
		Resources resources = Resources.getInstance();
		List<String> texts = new ArrayList<>();
		texts.add(resources.getString(I18NConstants.SNAPSHOT_TAKEN_OVER__SETTINGS_CONNECTIONS.fill(report.settings(),
			report.connections())));
		if (!report.identical()) {
			texts.add(resources.getString(I18NConstants.TAKE_OVER_DIFFERENT__PROBLEMS_DIFFERENCES.fill(
				report.problems().size(), report.differences().size())));
		}
		for (ResKey problem : report.problems()) {
			texts.add(resources.getString(problem));
		}
		if (!report.createdAddresses().isEmpty()) {
			texts.add(resources.getString(I18NConstants.READBACK_CREATED_ADDRESSES__ADDRESSES.fill(
				String.join(", ", report.createdAddresses()))));
		}
		if (report.flags() > 0) {
			texts.add(resources.getString(I18NConstants.READBACK_FLAGS__COUNT.fill(report.flags())));
		}
		return String.join(" ", texts);
	}

	/**
	 * Builder of {@link SnapshotTakeOver}.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<SnapshotTakeOver> {

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
		}

		@Override
		public SnapshotTakeOver build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, 1, 1);
			return new SnapshotTakeOver(getConfig().getName(), args);
		}
	}

}
