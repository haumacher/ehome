/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.text.DateFormat;
import java.util.Date;
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
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.DeviceConfiguration;

/**
 * The TL-Script functions of the snapshots of a device (haumacher/ehome #193), registered in
 * <code>ehomeConf.config.xml</code> under the name of their {@link Function} constant with the
 * {@link Builder} of this class.
 */
public class SnapshotFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>deviceSnapshots(device)</code>: the snapshots stored with the device, newest first. */
		deviceSnapshots(1, true),
		/**
		 * <code>snapshotCompare(snapshot)</code>: the comparison of a stored snapshot with the download
		 * plan of its device, as the report of the comparison ({@link ProgrammingReport}); without the
		 * bus.
		 */
		snapshotCompare(1, true),
		/** <code>snapshotFile(snapshot)</code>: the snapshot as a file to download ({@link SnapshotFile}). */
		snapshotFile(1, true),
		/**
		 * <code>snapshotSummary(snapshot)</code>: what the snapshot holds, for a person: the bytes read,
		 * the ranges, the bytes not readable.
		 */
		snapshotSummary(1, true),
		/**
		 * <code>snapshotLabel(snapshot)</code>: the snapshot for a person: its time and where it came
		 * from.
		 */
		snapshotLabel(1, true),
		/**
		 * <code>snapshotUpload(device, file)</code>: stores the uploaded file as a snapshot of the device;
		 * refused with the reason if it is no snapshot file or was read from a device of another program
		 * or mask. Returns the stored snapshot. Must run in a transaction.
		 */
		snapshotUpload(2, false);

		private final int _arity;

		private final boolean _sideEffectFree;

		Function(int arity, boolean sideEffectFree) {
			_arity = arity;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link SnapshotFunctions}.
	 */
	protected SnapshotFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new SnapshotFunctions(getName(), _function, arguments);
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
		if (!(arguments[0] instanceof TLObject object)) {
			return null;
		}
		switch (_function) {
			case deviceSnapshots:
				return Snapshots.of(object);
			case snapshotCompare:
				return compare(object);
			case snapshotFile:
				return SnapshotFile.export(Snapshots.load(object));
			case snapshotSummary:
				return summary(Snapshots.load(object));
			case snapshotLabel:
				return Resources.getInstance().getString(label(Snapshots.load(object)));
			case snapshotUpload:
				return upload(object, arguments[1]);
		}
		return null;
	}

	/**
	 * The report of the comparison of a stored snapshot with the plan of its device.
	 */
	public static Map<String, Object> compare(TLObject stored) {
		TLObject device = Snapshots.device(stored);
		Snapshot snapshot = Snapshots.load(stored);
		ResKey veto = SnapshotFile.veto(device, snapshot);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		if (plan.getImage() == null) {
			throw new TopLogicException(plan.getProblems().isEmpty() ? I18NConstants.ERROR_NO_PROGRAM
				: plan.getProblems().get(0));
		}
		DeviceComparison.Result result = DeviceComparison.compare(DeviceConfiguration.program(device), plan, snapshot);
		ResKey label = label(snapshot);
		return CompareJob.report(plan, snapshot, result,
			I18NConstants.SNAPSHOT_COMPARE_DONE__DEVICE_SNAPSHOT_EQUAL_BLOCKS.fill(plan.getDevice(), label,
				result.identicalBlocks(), result.blocks().size()),
			I18NConstants.SNAPSHOT_COMPARE_ABORTED__DEVICE_SNAPSHOT_EQUAL_BLOCKS.fill(plan.getDevice(), label,
				result.identicalBlocks(), result.blocks().size()));
	}

	/**
	 * The snapshot for a person: its time and its path to the bus or its file.
	 */
	public static ResKey label(Snapshot snapshot) {
		Date time = snapshot.getTime();
		String when = time == null ? "?"
			: DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Resources.getInstance().getLocale())
				.format(time);
		if (snapshot.getFile() != null) {
			return I18NConstants.SNAPSHOT_LABEL_FILE__TIME_FILE.fill(when, snapshot.getFile());
		}
		return I18NConstants.SNAPSHOT_LABEL__TIME_PATH.fill(when, String.valueOf(snapshot.getBusPath()));
	}

	/**
	 * What the snapshot holds, for a person.
	 */
	public static String summary(Snapshot snapshot) {
		int known = snapshot.known().cardinality();
		int unknown = 0;
		List<Snapshot.Range> ranges = snapshot.ranges();
		for (Snapshot.Range range : ranges) {
			unknown += range.data().length - range.known().cardinality();
		}
		ResKey text = I18NConstants.SNAPSHOT_SUMMARY__BYTES_RANGES_UNKNOWN.fill(known, ranges.size(), unknown);
		if (!snapshot.isComplete()) {
			text = I18NConstants.SNAPSHOT_SUMMARY_INCOMPLETE__SUMMARY.fill(text);
		}
		return Resources.getInstance().getString(text);
	}

	/**
	 * Stores an uploaded snapshot file with the device; must run in a transaction.
	 *
	 * @throws TopLogicException
	 *         If the file is no snapshot file or does not fit the device, with the reason.
	 */
	public static TLObject upload(TLObject device, Object file) {
		Snapshot snapshot = SnapshotFile.read(file);
		ResKey veto = SnapshotFile.veto(device, snapshot);
		if (veto != null) {
			throw new TopLogicException(I18NConstants.SNAPSHOT_UPLOAD_REFUSED__REASON.fill(veto));
		}
		return Snapshots.store(device, snapshot);
	}

	/**
	 * Builder of a {@link SnapshotFunctions} function: the {@link Function} named as the function is
	 * registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<SnapshotFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public SnapshotFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arity, _function._arity);
			return new SnapshotFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
