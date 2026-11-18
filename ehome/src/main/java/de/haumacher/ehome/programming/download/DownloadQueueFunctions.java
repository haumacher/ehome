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
 * The TL-Script functions of the programming of several devices (haumacher/ehome #200,
 * <code>projects/download-queue.view.xml</code>): the devices of a selection in the tree of the
 * devices or those of the project needing a download, the {@link DownloadQueue} of them, its entries
 * as rows, and the commands on it.
 *
 * <p>
 * Each is registered in <code>ehomeConf.config.xml</code> under the name of its {@link Function}
 * constant with the {@link Builder} of this class. The functions changing the queue (the kind of an
 * entry, cancel, abort) change runtime state only, no model; they return the queue's
 * {@link DownloadQueue#version() version}, which the view writes into a channel the cells of its
 * table follow.
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
		/**
		 * <code>downloadQueuePending(project)</code>: the devices of the project needing a download
		 * (<code>Device#downloadPending</code>), in the order of their individual addresses.
		 */
		downloadQueuePending(1, true),
		/** <code>downloadQueueSelectVeto(devices)</code>: why no programming can be started for them, or nothing. */
		downloadQueueSelectVeto(1, true),
		/** <code>downloadQueuePendingVeto(project)</code>: why "All with changes" is disabled, or nothing. */
		downloadQueuePendingVeto(1, true),
		/** <code>downloadQueueNew(project, devices)</code>: a new queue of the devices, each kind preset. */
		downloadQueueNew(2, true),
		/** <code>downloadQueueEntries(queue)</code>: the entries, as the rows of the table. */
		downloadQueueEntries(1, true),
		/** <code>downloadQueueDevice(entry)</code>: the device of the entry, as address and name. */
		downloadQueueDevice(1, true),
		/** <code>downloadQueueParametersOnly(entry)</code>: whether the entry downloads the parameters only. */
		downloadQueueParametersOnly(1, true),
		/** <code>downloadQueueCanChoose(entry)</code>: whether the kind of the entry can be changed. */
		downloadQueueCanChoose(1, true),
		/** <code>downloadQueueSetParametersOnly(entry, value)</code>: chooses the kind of a waiting entry. */
		downloadQueueSetParametersOnly(2, false),
		/** <code>downloadQueueState(entry)</code>: what became of the entry, as text. */
		downloadQueueState(1, true),
		/** <code>downloadQueueStateRole(entry)</code>: the color role of the state. */
		downloadQueueStateRole(1, true),
		/** <code>downloadQueueDetails(entry)</code>: progress, outcome or reason, as text. */
		downloadQueueDetails(1, true),
		/** <code>downloadQueueSetAll(queue, parametersOnly)</code>: chooses the kind of every waiting entry. */
		downloadQueueSetAll(2, false),
		/** <code>downloadQueueChooseVeto(queue)</code>: why no kind can be chosen any more, or nothing. */
		downloadQueueChooseVeto(1, true),
		/** <code>downloadQueueStartVeto(queue)</code>: why the queue cannot start, or nothing. */
		downloadQueueStartVeto(1, true),
		/** <code>downloadQueueConfirmation(queue)</code>: the question before the start. */
		downloadQueueConfirmation(1, true),
		/** <code>downloadQueueCancel(queue, selection)</code>: cancels the selected waiting entries. */
		downloadQueueCancel(2, false),
		/** <code>downloadQueueCancelVeto(queue, selection)</code>: why no selected entry can be cancelled, or nothing. */
		downloadQueueCancelVeto(2, true),
		/** <code>downloadQueueCancelAll(queue)</code>: cancels every waiting entry. */
		downloadQueueCancelAll(1, false),
		/** <code>downloadQueueAbort(queue)</code>: aborts the download running. */
		downloadQueueAbort(1, false),
		/** <code>downloadQueueAbortVeto(queue)</code>: why no download can be aborted, or nothing. */
		downloadQueueAbortVeto(1, true);

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
			case downloadQueuePending:
				return first instanceof TLObject project ? DownloadQueue.pendingDevices(project) : List.of();
			case downloadQueueSelectVeto:
				return first instanceof Collection<?> devices && !devices.isEmpty() ? null : I18NConstants.QUEUE_NO_DEVICES;
			case downloadQueuePendingVeto:
				return first instanceof TLObject project && !DownloadQueue.pendingDevices(project).isEmpty() ? null
					: I18NConstants.QUEUE_NONE_PENDING;
			case downloadQueueNew:
				return first instanceof TLObject project ? new DownloadQueue(project, objects(second)) : null;
			default:
				break;
		}
		if (first instanceof DownloadQueue.Entry entry) {
			switch (_function) {
				case downloadQueueDevice:
					return entry.label();
				case downloadQueueParametersOnly:
					return Boolean.valueOf(entry.parametersOnly());
				case downloadQueueCanChoose:
					return Boolean.valueOf(entry.canChoose() && DownloadQueue.allowsParametersOnly(entry.device()));
				case downloadQueueSetParametersOnly:
					entry.setParametersOnly(Boolean.TRUE.equals(second));
					return version(entry.queue());
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
		if (!(first instanceof DownloadQueue queue)) {
			return null;
		}
		switch (_function) {
			case downloadQueueEntries:
				return queue.entries();
			case downloadQueueSetAll:
				queue.setParametersOnly(Boolean.TRUE.equals(second));
				return version(queue);
			case downloadQueueChooseVeto:
				return queue.entries(DownloadQueue.State.WAITING).isEmpty() ? I18NConstants.QUEUE_NOTHING_WAITS : null;
			case downloadQueueStartVeto:
				return queue.startVeto();
			case downloadQueueConfirmation:
				return confirmation(queue);
			case downloadQueueCancel:
				queue.cancel(entries(queue, second));
				return version(queue);
			case downloadQueueCancelVeto:
				return entries(queue, second).stream().anyMatch(e -> e.state() == DownloadQueue.State.WAITING) ? null
					: I18NConstants.QUEUE_NOTHING_SELECTED_WAITS;
			case downloadQueueCancelAll:
				queue.cancelWaiting();
				return version(queue);
			case downloadQueueAbort:
				queue.abortRunning();
				return version(queue);
			case downloadQueueAbortVeto:
				return queue.current() == null ? I18NConstants.QUEUE_NOTHING_RUNS : null;
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

	private static Long version(DownloadQueue queue) {
		return Long.valueOf(queue.version());
	}

	private static ResKey confirmation(DownloadQueue queue) {
		List<DownloadQueue.Entry> waiting = queue.entries(DownloadQueue.State.WAITING);
		long parameters = waiting.stream().filter(DownloadQueue.Entry::parametersOnly).count();
		return I18NConstants.QUEUE_CONFIRM__COUNT_PATH_FULL_PARAMETERS.fill(waiting.size(),
			BusPath.describe(queue.project()), waiting.size() - parameters, parameters);
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
