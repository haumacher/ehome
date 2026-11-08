/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.modules;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.operational.ModuleArgument;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ModulePlacement;

/**
 * An instance of a dynamic module in a device (haumacher/ehome #119): the placement it instantiates,
 * its ID, its repeat index and the values of its arguments.
 *
 * <p>
 * An {@link InstanceInfo} is computed by the walk of a device's configuration
 * (<code>DeviceConfiguration</code>), or read from the instance the device stores
 * (<code>ehome.physical:ModuleInstance</code>, {@link #of(TLObject)}). Two of them are equal if they
 * name the same instance of the same device, whatever their argument values: the values of an
 * instance change with the values of the device, its identity does not.
 * </p>
 */
public final class InstanceInfo {

	private final TLObject _device;

	private final String _id;

	private final ModulePlacement _placement;

	private final InstanceInfo _parent;

	private final String _repeatIndex;

	private final Map<ModuleArgument, String> _values;

	private final Map<String, String> _byName = new LinkedHashMap<>();

	private TLObject _stored;

	/**
	 * Creates an {@link InstanceInfo}.
	 *
	 * @param device
	 *        The device (<code>ehome.physical:Device</code>).
	 * @param id
	 *        The ID of the instance relative to the program, see {@link #getId()}.
	 * @param placement
	 *        The placement of the module definition (the template, not an instance part).
	 * @param parent
	 *        The instance of the module whose structure places this one, <code>null</code> for a
	 *        placement of the program.
	 * @param repeatIndex
	 *        See {@link #getRepeatIndex()}.
	 * @param values
	 *        The values of the arguments, in the order of the definition.
	 * @param stored
	 *        The instance the device stores, <code>null</code> if it stores none (yet).
	 */
	public InstanceInfo(TLObject device, String id, ModulePlacement placement, InstanceInfo parent,
			String repeatIndex, Map<ModuleArgument, String> values, TLObject stored) {
		_device = device;
		_id = id;
		_placement = placement;
		_parent = parent;
		_repeatIndex = repeatIndex;
		_values = new LinkedHashMap<>(values);
		for (Map.Entry<ModuleArgument, String> entry : values.entrySet()) {
			String name = entry.getKey().getName();
			if (name != null) {
				_byName.put(name, entry.getValue());
			}
		}
		_stored = stored;
	}

	/**
	 * The {@link InstanceInfo} of an instance a device stores (<code>ehome.physical:ModuleInstance</code>),
	 * with the argument values stored with it.
	 */
	public static InstanceInfo of(TLObject stored) {
		if (stored == null) {
			return null;
		}
		Map<ModuleArgument, String> values = new LinkedHashMap<>();
		for (Object element : (Collection<?>) stored.tValueByName(ModuleInstances.ARGUMENTS)) {
			TLObject argument = (TLObject) element;
			if (argument.tValueByName(ModuleInstances.ARGUMENT) instanceof ModuleArgument definition) {
				values.put(definition, (String) argument.tValueByName(ModuleInstances.VALUE));
			}
		}
		TLObject parent = (TLObject) stored.tValueByName(ModuleInstances.PARENT);
		return new InstanceInfo(stored.tContainer(), (String) stored.tValueByName(ModuleInstances.NAME),
			(ModulePlacement) stored.tValueByName(ModuleInstances.PLACEMENT), parent == null ? null : of(parent),
			(String) stored.tValueByName(ModuleInstances.REPEAT_INDEX), values, stored);
	}

	/**
	 * The device.
	 */
	public TLObject getDevice() {
		return _device;
	}

	/**
	 * The ID ETS gives the instance, relative to the program: <code>MD-1_M-1_MI-1</code>, for an
	 * instance of a sub-module <code>MD-4_M-15_MI-1_SM-1_M-1_MI-1-1-2</code>.
	 */
	public String getId() {
		return _id;
	}

	/**
	 * The placement of the module definition.
	 */
	public ModulePlacement getPlacement() {
		return _placement;
	}

	/**
	 * The module definition the instance instantiates.
	 */
	public ModuleDefinition getDefinition() {
		return _placement.getModule();
	}

	/**
	 * The instance of the enclosing module of an instance of a sub-module, <code>null</code> for an
	 * instance of a placement of the program.
	 */
	public InstanceInfo getParent() {
		return _parent;
	}

	/**
	 * Per enclosing <code>Repeat</code> and for the placement itself, the position of the element in
	 * the document (ETS: XmlOrder) and the iteration, separated by <code>x</code>, the entries by
	 * spaces (ETS: RepeatIndex).
	 */
	public String getRepeatIndex() {
		return _repeatIndex;
	}

	/**
	 * The values of the arguments, in the order of the definition.
	 */
	public Map<ModuleArgument, String> getValues() {
		return Collections.unmodifiableMap(_values);
	}

	/**
	 * The value of the given argument, <code>null</code> if the instance has none.
	 */
	public String value(ModuleArgument argument) {
		return _values.get(argument);
	}

	/**
	 * The value of the given argument as a number, 0 if it has none or is no number.
	 */
	public long number(ModuleArgument argument) {
		return parse(value(argument));
	}

	/**
	 * The value of the argument with the given name, <code>null</code> if there is none.
	 */
	public String valueByName(String name) {
		return _byName.get(name);
	}

	/**
	 * The instance the device stores, <code>null</code> if it stores none (yet).
	 */
	public TLObject getStored() {
		return _stored;
	}

	/**
	 * Sets {@link #getStored()}.
	 */
	void setStored(TLObject stored) {
		_stored = stored;
	}

	/**
	 * A text as a number, 0 if it is none.
	 */
	public static long parse(String text) {
		if (text == null) {
			return 0;
		}
		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException ex) {
			try {
				return (long) Double.parseDouble(text.trim());
			} catch (NumberFormatException ex2) {
				return 0;
			}
		}
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof InstanceInfo other && Objects.equals(_id, other._id) && Objects.equals(_device, other._device);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(_id) * 31 + Objects.hashCode(_device);
	}

	@Override
	public String toString() {
		return _id;
	}

}
