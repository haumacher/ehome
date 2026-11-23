/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.programming.BuildingDevices;

/**
 * The TL-Script functions of the programming of several devices and of the project's queue of
 * downloads (haumacher/ehome #200, #218; <code>projects/devices-programming.view.xml</code>,
 * <code>projects/download-queue.view.xml</code>): the devices of a selection in the tree of the
 * devices or those of the project needing a download, the {@link DownloadSelection} of them with the
 * kind per row, the project's {@link DownloadQueue} with its entries as rows, and the commands on
 * both.
 *
 * <p>
 * Each is registered in <code>ehomeConf.config.xml</code> under the name of its {@link Function}
 * constant with the {@link Builder} of this class. The functions changing a selection or the queue
 * change runtime state only, no model; those of a selection return its
 * {@link DownloadSelection#version() version}, which the view writes into a channel the cells of its
 * table follow; the queue's changes reach the views through {@link DownloadQueueSource}.
 * </p>
 */
public class DownloadQueueFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>downloadQueueDevices(project, selection)</code>: the devices with a program among the
		 * selected nodes of the tree of the devices of the project and below them (an area, a line, a
		 * building part, "No location"), each once, in the order of their individual addresses.
		 */
		downloadQueueDevices(2, true),
		/** <code>downloadSelection(project, devices)</code>: a new selection of the devices, each kind preset. */
		downloadSelection(2, true),
		/** <code>downloadSelectionRows(selection)</code>: the rows of the selection. */
		downloadSelectionRows(1, true),
		/** <code>downloadSelectionSetAll(selection, parametersOnly)</code>: chooses the kind of every row. */
		downloadSelectionSetAll(2, false),
		/** <code>downloadSelectionChooseVeto(selection)</code>: why no kind can be chosen, or nothing. */
		downloadSelectionChooseVeto(1, true),
		/** <code>downloadSelectionVeto(selection)</code>: why "Program" is disabled, or nothing. */
		downloadSelectionVeto(1, true),
		/** <code>downloadSelectionConfirmation(selection)</code>: the question before "Program". */
		downloadSelectionConfirmation(1, true),
		/** <code>downloadSelectionEnqueue(selection)</code>: appends the rows to the project's queue. */
		downloadSelectionEnqueue(1, false),
		/** <code>downloadQueueDevice(row)</code>: the device of a row or an entry, as address and name. */
		downloadQueueDevice(1, true),
		/** <code>downloadQueueParametersOnly(row)</code>: whether the row or entry downloads the parameters only. */
		downloadQueueParametersOnly(1, true),
		/** <code>downloadQueueKind(row)</code>: the kind of the download of a row or an entry, as text. */
		downloadQueueKind(1, true),
		/** <code>downloadQueueCanChoose(row)</code>: whether the kind of the row can be changed. */
		downloadQueueCanChoose(1, true),
		/** <code>downloadQueueSetParametersOnly(row, value)</code>: chooses the kind of a row. */
		downloadQueueSetParametersOnly(2, false),
		/** <code>downloadQueueEntries(project)</code>: the entries of the project's queue, as rows. */
		downloadQueueEntries(1, true),
		/** <code>downloadQueueState(entry)</code>: what became of the entry, as text. */
		downloadQueueState(1, true),
		/** <code>downloadQueueStateRole(entry)</code>: the color role of the state. */
		downloadQueueStateRole(1, true),
		/** <code>downloadQueueDetails(entry)</code>: progress, outcome or reason, as text. */
		downloadQueueDetails(1, true),
		/** <code>downloadQueueReport(selected)</code>: the report of the one selected finished entry, or nothing. */
		downloadQueueReport(1, true),
		/** <code>downloadQueueReportVeto(selected)</code>: why no report can be shown, or nothing. */
		downloadQueueReportVeto(1, true),
		/**
		 * <code>downloadQueuePending(project)</code>: the devices of the project needing a download
		 * (<code>Device#downloadPending</code>), in the order of their individual addresses.
		 */
		downloadQueuePending(1, true),
		/** <code>downloadQueuePendingVeto(project)</code>: why "All with changes" is disabled, or nothing. */
		downloadQueuePendingVeto(1, true),
		/** <code>downloadQueuePendingConfirmation(project)</code>: the question before "All with changes". */
		downloadQueuePendingConfirmation(1, true),
		/** <code>downloadQueueEnqueuePending(project)</code>: appends the devices needing a download, each kind preset. */
		downloadQueueEnqueuePending(1, false),
		/** <code>downloadQueueEnqueueDevice(device, parametersOnly)</code>: appends the download of one device. */
		downloadQueueEnqueueDevice(2, false),
		/** <code>downloadQueueCancel(project, selected)</code>: cancels the selected waiting entries. */
		downloadQueueCancel(2, false),
		/** <code>downloadQueueCancelVeto(project, selected)</code>: why no selected entry can be cancelled, or nothing. */
		downloadQueueCancelVeto(2, true),
		/** <code>downloadQueueCancelAll(project)</code>: cancels every waiting entry. */
		downloadQueueCancelAll(1, false),
		/** <code>downloadQueueCancelAllVeto(project)</code>: why no entry can be cancelled, or nothing. */
		downloadQueueCancelAllVeto(1, true),
		/** <code>downloadQueueAbort(project)</code>: aborts the download running. */
		downloadQueueAbort(1, false),
		/** <code>downloadQueueAbortVeto(project)</code>: why no download can be aborted, or nothing. */
		downloadQueueAbortVeto(1, true),
		/** <code>downloadQueueClear(project)</code>: removes the finished entries. */
		downloadQueueClear(1, false),
		/** <code>downloadQueueClearVeto(project)</code>: why no entry can be removed, or nothing. */
		downloadQueueClearVeto(1, true);

		private final int _arity;

		private final boolean _sideEffectFree;

		Function(int arity, boolean sideEffectFree) {
			_arity = arity;
			_sideEffectFree = sideEffectFree;
		}
	}

	/** The node "No location" of the tree of the devices by building part. */
	private static final String NO_LOCATION = "noLocation";

	/** The type of a building part. */
	private static final String BUILDING_PART = "ehome.building:BuildingDefinition";

	private final Function _function;

	/**
	 * Creates a {@link DownloadQueueFunctions}.
	 */
	protected DownloadQueueFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new DownloadQueueFunctions(getName(), _function, arguments);
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
	public boolean isSideEffectFree() {
		return _function._sideEffectFree;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		Object first = arguments[0];
		Object second = arguments.length > 1 ? arguments[1] : null;
		switch (_function) {
			case downloadQueueDevices:
				return devices(first instanceof TLObject project ? project : null, second);
			case downloadSelection:
				return first instanceof TLObject project && project.tValid() ? new DownloadSelection(project, objects(second))
					: null;
			case downloadQueueReport:
				return entry(first) instanceof DownloadQueue.Entry entry ? entry.report() : null;
			case downloadQueueReportVeto:
				return entry(first) instanceof DownloadQueue.Entry entry && entry.report() != null ? null
					: I18NConstants.QUEUE_NO_REPORT;
			default:
				break;
		}
		if (first instanceof DownloadSelection selection) {
			return selection(selection, second);
		}
		if (first instanceof DownloadSelection.Row row) {
			switch (_function) {
				case downloadQueueDevice:
					return row.label();
				case downloadQueueParametersOnly:
					return Boolean.valueOf(row.parametersOnly());
				case downloadQueueKind:
					return text(kind(row.parametersOnly()));
				case downloadQueueCanChoose:
					return Boolean.valueOf(row.canChoose());
				case downloadQueueSetParametersOnly:
					row.setParametersOnly(Boolean.TRUE.equals(second));
					return SearchExpression.toNumber(row.selection().version());
				default:
					return null;
			}
		}
		if (first instanceof DownloadQueue.Entry entry) {
			switch (_function) {
				case downloadQueueDevice:
					return entry.label();
				case downloadQueueParametersOnly:
					return Boolean.valueOf(entry.parametersOnly());
				case downloadQueueKind:
					return text(kind(entry.parametersOnly()));
				case downloadQueueCanChoose:
					return Boolean.FALSE;
				case downloadQueueState:
					return text(state(entry.state()));
				case downloadQueueStateRole:
					return role(entry.state());
				case downloadQueueDetails:
					return text(entry.details());
				default:
					return null;
			}
		}
		if (!(first instanceof TLObject object) || !object.tValid()) {
			return null;
		}
		if (_function == Function.downloadQueueEnqueueDevice) {
			DownloadQueue.of(object).enqueue(object, Boolean.TRUE.equals(second));
			return null;
		}
		return project(object, second);
	}

	private Object selection(DownloadSelection selection, Object second) {
		switch (_function) {
			case downloadSelectionRows:
				return selection.rows();
			case downloadSelectionSetAll:
				selection.setParametersOnly(Boolean.TRUE.equals(second));
				return SearchExpression.toNumber(selection.version());
			case downloadSelectionChooseVeto:
				return selection.rows().stream().anyMatch(DownloadSelection.Row::canChoose) ? null
					: I18NConstants.QUEUE_NO_CHOICE;
			case downloadSelectionVeto: {
				if (selection.rows().isEmpty()) {
					return I18NConstants.QUEUE_NO_DEVICES;
				}
				return DownloadQueue.enqueueVeto(selection.project());
			}
			case downloadSelectionConfirmation: {
				long parameters = selection.parametersOnlyCount();
				int count = selection.rows().size();
				return I18NConstants.QUEUE_CONFIRM__COUNT_PATH_FULL_PARAMETERS.fill(count,
					BusPath.describe(selection.project()), count - parameters, parameters);
			}
			case downloadSelectionEnqueue:
				selection.enqueue();
				return SearchExpression.toNumber(selection.version());
			default:
				return null;
		}
	}

	private Object project(TLObject project, Object second) {
		DownloadQueue queue = DownloadQueue.find(project);
		switch (_function) {
			case downloadQueueEntries:
				return queue == null ? List.of() : queue.entries();
			case downloadQueuePending:
				return DownloadQueue.pendingDevices(project);
			case downloadQueuePendingVeto: {
				if (DownloadQueue.pendingDevices(project).isEmpty()) {
					return I18NConstants.QUEUE_NONE_PENDING;
				}
				return DownloadQueue.enqueueVeto(project);
			}
			case downloadQueuePendingConfirmation: {
				List<TLObject> pending = DownloadQueue.pendingDevices(project);
				long parameters = pending.stream()
					.filter(d -> DownloadQueue.allowsParametersOnly(d) && ProgrammingFunctions.parametersOnlyByDefault(d))
					.count();
				return I18NConstants.QUEUE_CONFIRM__COUNT_PATH_FULL_PARAMETERS.fill(pending.size(),
					BusPath.describe(project), pending.size() - parameters, parameters);
			}
			case downloadQueueEnqueuePending:
				DownloadQueue.of(project).enqueue(DownloadQueue.pendingDevices(project),
					d -> Boolean.valueOf(ProgrammingFunctions.parametersOnlyByDefault(d)));
				return null;
			case downloadQueueCancel:
				if (queue != null) {
					queue.cancel(entries(queue, second));
				}
				return null;
			case downloadQueueCancelVeto:
				return queue != null && entries(queue, second).stream().anyMatch(e -> e.state() == DownloadQueue.State.WAITING)
					? null : I18NConstants.QUEUE_NOTHING_SELECTED_WAITS;
			case downloadQueueCancelAll:
				if (queue != null) {
					queue.cancelWaiting();
				}
				return null;
			case downloadQueueCancelAllVeto:
				return queue != null && !queue.entries(DownloadQueue.State.WAITING).isEmpty() ? null
					: I18NConstants.QUEUE_NOTHING_WAITS;
			case downloadQueueAbort:
				if (queue != null) {
					queue.abortRunning();
				}
				return null;
			case downloadQueueAbortVeto:
				return queue != null && queue.current() != null ? null : I18NConstants.QUEUE_NOTHING_RUNS;
			case downloadQueueClear:
				if (queue != null) {
					queue.clearFinished();
				}
				return null;
			case downloadQueueClearVeto:
				return queue != null && queue.entries().stream().anyMatch(e -> e.state().isFinal()) ? null
					: I18NConstants.QUEUE_NOTHING_FINISHED;
			default:
				return null;
		}
	}

	/**
	 * The devices with a program among and below the given nodes of the tree of the devices of the
	 * project, each once, in the order of their individual addresses: a device itself, the devices of an
	 * area's lines and of a line, the devices placed in a building part and its parts, and for "No
	 * location" (the text <code>noLocation</code>) those of the project placed in no part.
	 */
	public static List<TLObject> devices(TLObject project, Object selection) {
		List<TLObject> result = new ArrayList<>();
		Collection<?> nodes = selection instanceof Collection<?> collection ? collection
			: selection == null ? List.of() : List.of(selection);
		for (Object node : nodes) {
			if (NO_LOCATION.equals(node)) {
				if (project != null) {
					addDevices(result, BuildingDevices.unplaced(project));
				}
			} else if (node instanceof TLObject object && object.tValid()) {
				collect(result, object);
			}
		}
		return DownloadQueue.sorted(result);
	}

	private static void collect(List<TLObject> result, TLObject node) {
		if (TLModelUtil.isCompatibleInstance(TLModelUtil.findType(BUILDING_PART), node)) {
			addDevices(result, BuildingDevices.devicesWithin(node));
		} else if (node.tType().getPart("program") != null) {
			result.add(node);
		} else {
			for (String reference : List.of("lines", "devices")) {
				if (node.tType().getPart(reference) != null
					&& node.tValueByName(reference) instanceof Collection<?> children) {
					for (Object child : children) {
						if (child instanceof TLObject object) {
							collect(result, object);
						}
					}
				}
			}
		}
	}

	private static void addDevices(List<TLObject> result, Collection<TLObject> devices) {
		for (TLObject device : devices) {
			if (device.tType().getPart("program") != null) {
				result.add(device);
			}
		}
	}

	private static List<TLObject> objects(Object value) {
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				if (element instanceof TLObject object) {
					result.add(object);
				}
			}
		} else if (value instanceof TLObject object) {
			result.add(object);
		}
		return result;
	}

	/**
	 * The one entry of the given selection of the table of the queue, <code>null</code> for none or
	 * several.
	 */
	private static DownloadQueue.Entry entry(Object selection) {
		if (selection instanceof DownloadQueue.Entry entry) {
			return entry;
		}
		if (selection instanceof Collection<?> collection && collection.size() == 1
			&& collection.iterator().next() instanceof DownloadQueue.Entry entry) {
			return entry;
		}
		return null;
	}

	private static List<DownloadQueue.Entry> entries(DownloadQueue queue, Object selection) {
		Collection<?> selected = selection instanceof Collection<?> collection ? collection
			: selection == null ? Set.of() : Set.of(selection);
		List<DownloadQueue.Entry> result = new ArrayList<>();
		for (Object element : selected) {
			if (element instanceof DownloadQueue.Entry entry && entry.queue() == queue) {
				result.add(entry);
			}
		}
		return result;
	}

	private static ResKey kind(boolean parametersOnly) {
		return parametersOnly ? I18NConstants.QUEUE_KIND_PARAMETERS : I18NConstants.QUEUE_KIND_FULL;
	}

	private static ResKey state(DownloadQueue.State state) {
		return switch (state) {
			case WAITING -> I18NConstants.QUEUE_STATE_WAITING;
			case RUNNING -> I18NConstants.QUEUE_STATE_RUNNING;
			case DONE -> I18NConstants.QUEUE_STATE_DONE;
			case FAILED -> I18NConstants.QUEUE_STATE_FAILED;
			case CANCELLED -> I18NConstants.QUEUE_STATE_CANCELLED;
			case ABORTED -> I18NConstants.QUEUE_STATE_ABORTED;
		};
	}

	private static String role(DownloadQueue.State state) {
		return switch (state) {
			case WAITING -> null;
			case RUNNING -> "info";
			case DONE -> "success";
			case FAILED -> "error";
			case CANCELLED -> "neutral";
			case ABORTED -> "warning";
		};
	}

	private static String text(ResKey key) {
		return key == null ? null : Resources.getInstance().getString(key);
	}

	/**
	 * Builder of a {@link DownloadQueueFunctions} function: the {@link Function} named as the
	 * function is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<DownloadQueueFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public DownloadQueueFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arity, _function._arity);
			return new DownloadQueueFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
