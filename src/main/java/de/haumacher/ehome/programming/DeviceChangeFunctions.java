/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;

import de.haumacher.ehome.programming.DeviceChanges.Reference;

/**
 * The TL-Script functions reading and discarding the changes of a device since its last download
 * (or against the defaults of its program), see {@link DeviceChanges}.
 *
 * <p>
 * Each function is registered in <code>ehomeConf.config.xml</code> under the name of its
 * {@link Function} constant with the {@link Builder} of this class. A <code>reference</code>
 * argument is a classifier of <code>ehome.physical:ChangeReference</code>; nothing stands for the
 * state of the last download if one is known, else the defaults.
 * </p>
 *
 * <p>
 * The functions are implemented in Java, so a view calling them names the types whose changes
 * recompute them (<code>observed-types</code>: the settings, connectors and connections of devices,
 * and the devices for a download), see {@link DeviceFunctions}.
 * </p>
 */
public class DeviceChangeFunctions extends GenericMethod {

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/** <code>deviceHasReference(device)</code>: whether a download of the device is known. */
		deviceHasReference(1, true),
		/**
		 * <code>deviceChangeReferences(device)</code>: the references the device can be compared
		 * with.
		 */
		deviceChangeReferences(1, true),
		/**
		 * <code>deviceChangeReference(device, reference)</code>: the reference in effect for the
		 * given choice.
		 */
		deviceChangeReference(2, true),
		/** <code>deviceChangeCount(device, reference)</code>: the number of changes. */
		deviceChangeCount(2, true),
		/**
		 * <code>deviceChangeGroups(device, reference)</code>: the changes grouped by the page they are
		 * shown on, each group a dictionary with <code>title</code> and <code>items</code>.
		 */
		deviceChangeGroups(2, true),
		/**
		 * <code>deviceChanged(device, object)</code>: whether an entry, a page or the device is
		 * changed since the last download.
		 */
		deviceChanged(2, true),
		/** <code>deviceChangedPages(device)</code>: the pages marked as changed since the last download. */
		deviceChangedPages(1, true),
		/**
		 * <code>deviceReferenceHint(device, item)</code>: "Device: …", what the device holds for a
		 * changed entry since the last download; nothing for an unchanged one.
		 */
		deviceReferenceHint(2, true),
		/**
		 * <code>deviceResetToReference(device, item)</code>: sets an entry back to the state of the
		 * last download.
		 */
		deviceResetToReference(2, false),
		/** <code>deviceDiscardVeto(device)</code>: why the changes cannot be discarded. */
		deviceDiscardVeto(1, true),
		/** <code>deviceDiscardConfirmation(device)</code>: the question before discarding. */
		deviceDiscardConfirmation(1, true),
		/** <code>deviceDiscardChanges(device)</code>: discards the changes since the last download. */
		deviceDiscardChanges(1, false),
		/** <code>projectDiscardVeto(project)</code>: why the changes cannot be discarded. */
		projectDiscardVeto(1, true),
		/** <code>projectDiscardConfirmation(project)</code>: the question before discarding. */
		projectDiscardConfirmation(1, true),
		/**
		 * <code>projectDiscardChanges(project)</code>: discards the changes of all devices since
		 * their last download.
		 */
		projectDiscardChanges(1, false);

		private final int _arguments;

		private final boolean _sideEffectFree;

		Function(int arguments, boolean sideEffectFree) {
			_arguments = arguments;
			_sideEffectFree = sideEffectFree;
		}
	}

	private final Function _function;

	/**
	 * Creates a {@link DeviceChangeFunctions}.
	 */
	protected DeviceChangeFunctions(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new DeviceChangeFunctions(getName(), _function, arguments);
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
		if (!(arguments[0] instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		switch (_function) {
			case deviceHasReference:
				return Boolean.valueOf(DeviceChanges.referenceRevision(obj) != null);
			case deviceChangeReferences: {
				List<Object> result = new ArrayList<>();
				if (DeviceChanges.referenceRevision(obj) != null) {
					result.add(Reference.DEVICE.classifier());
				}
				result.add(Reference.DEFAULT.classifier());
				return result;
			}
			case deviceChangeReference:
				return DeviceChanges.of(obj, Reference.of(arguments[1])).getReference().classifier();
			case deviceChangeCount:
				return SearchExpression.toNumber(DeviceChanges.of(obj, Reference.of(arguments[1])).getCount());
			case deviceChangeGroups:
				return DeviceChanges.of(obj, Reference.of(arguments[1])).getGroups();
			case deviceChanged: {
				DeviceChanges changes = DeviceChanges.sinceDownload(obj);
				return Boolean.valueOf(changes != null && changes.isChanged(arguments[1]));
			}
			case deviceChangedPages: {
				DeviceChanges changes = DeviceChanges.sinceDownload(obj);
				return changes == null ? List.of() : new ArrayList<>(changes.getChangedPages());
			}
			case deviceReferenceHint: {
				DeviceChanges changes = DeviceChanges.sinceDownload(obj);
				String text = changes == null ? null : changes.referenceText(arguments[1]);
				return text == null ? null
					: Resources.getInstance().getString(I18NConstants.HINT_DEVICE__VALUE.fill(text));
			}
			case deviceResetToReference: {
				DeviceChanges changes = DeviceChanges.sinceDownload(obj);
				if (changes != null) {
					changes.reset(arguments[1]);
				}
				return null;
			}
			case deviceDiscardVeto:
				return DeviceChanges.discardVeto(obj);
			case deviceDiscardConfirmation: {
				DeviceChanges changes = DeviceChanges.sinceDownload(obj);
				return I18NConstants.CONFIRM_DISCARD__DEVICE_COUNT.fill(obj, changes == null ? 0 : changes.getCount());
			}
			case deviceDiscardChanges:
				return SearchExpression.toNumber(DeviceChanges.discard(obj));
			case projectDiscardVeto:
				return DeviceChanges.projectDiscardVeto(obj);
			case projectDiscardConfirmation:
				return I18NConstants.CONFIRM_DISCARD_PROJECT__COUNT_DEVICES.fill(DeviceChanges.projectCount(obj),
					DeviceChanges.changedDevices(obj).size());
			case projectDiscardChanges:
				return SearchExpression.toNumber(DeviceChanges.discardProject(obj));
		}
		return null;
	}

	/**
	 * Builder of a {@link DeviceChangeFunctions} function: the {@link Function} named as the function
	 * is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<DeviceChangeFunctions> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public DeviceChangeFunctions build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkArgs(expr, args, _function._arguments, _function._arguments);
			return new DeviceChangeFunctions(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
