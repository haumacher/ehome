/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.modules;

import java.util.Objects;

import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TransientObject;

import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ModulePlacement;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.PropertyLocation;
import de.haumacher.ehome.model.operational.Repeat;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;

/**
 * A part of a module definition as it is in one instance of the module in a device (haumacher/ehome
 * #119): the part of the template (parameter, parameter reference, communication object, object
 * reference, location, structure element) with the values the instance's arguments give it.
 *
 * <p>
 * It is the part of the template in every respect (its type, its attributes, the generated
 * interface of its type), except where the instance changes it (see
 * {@link ModuleParts#adapt(TLObject, TLStructuredTypePart, Object, InstanceInfo)}): the number of a
 * communication object, the byte offset of a location, the texts (<code>{{argument}}</code> replaced)
 * and the parts of the module it refers to or contains, which are the parts of the same instance.
 * Code reading a parameter reference, an object reference or a structure element of a device's
 * configuration therefore reads the values of the instance without knowing about modules.
 * </p>
 *
 * <p>
 * Instance parts are not stored; two of them are equal if they are the same part of the template in
 * the same instance. They cannot be changed.
 * </p>
 */
public abstract class InstancePart extends TransientObject {

	private final TLObject _template;

	private final InstanceInfo _instance;

	/**
	 * Creates an {@link InstancePart}.
	 */
	protected InstancePart(TLObject template, InstanceInfo instance) {
		_template = template;
		_instance = instance;
	}

	/**
	 * The part of the module definition.
	 */
	public TLObject getTemplate() {
		return _template;
	}

	/**
	 * The instance.
	 */
	public InstanceInfo getInstance() {
		return _instance;
	}

	@Override
	public TLStructuredType tType() {
		return _template.tType();
	}

	@Override
	public Object tValue(TLStructuredTypePart part) {
		return ModuleParts.adapt(_template, part, _template.tValue(part), _instance);
	}

	@Override
	public TLObject tContainer() {
		return ModuleParts.wrap(_template.tContainer(), _instance);
	}

	@Override
	public TLReference tContainerReference() {
		return _template.tContainerReference();
	}

	@Override
	public boolean tValid() {
		return _template.tValid();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof InstancePart other && _template.equals(other._template)
			&& Objects.equals(_instance, other._instance);
	}

	@Override
	public int hashCode() {
		return _template.hashCode() * 31 + Objects.hashCode(_instance);
	}

	@Override
	protected String toStringValues() {
		return ", template: " + _template + ", instance: " + _instance;
	}

	/**
	 * A parameter reference in an instance.
	 */
	public static final class ParameterViewPart extends InstancePart implements ParameterView {
		ParameterViewPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A communication object reference in an instance.
	 */
	public static final class InterfacePart extends InstancePart implements Interface {
		InterfacePart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A parameter in an instance.
	 */
	public static final class ParameterPart extends InstancePart implements Parameter {
		ParameterPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A communication object in an instance.
	 */
	public static final class PortPart extends InstancePart implements Port {
		PortPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A memory location in an instance.
	 */
	public static final class PointerPart extends InstancePart implements Pointer {
		PointerPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A property location in an instance.
	 */
	public static final class PropertyLocationPart extends InstancePart implements PropertyLocation {
		PropertyLocationPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A parameter block in an instance.
	 */
	public static final class ParameterGroupPart extends InstancePart implements ParameterGroup {
		ParameterGroupPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A channel in an instance.
	 */
	public static final class ChannelPart extends InstancePart implements Channel {
		ChannelPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A channel independent block in an instance.
	 */
	public static final class ChannelIndependentPart extends InstancePart implements ChannelIndependent {
		ChannelIndependentPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A switch in an instance.
	 */
	public static final class SwitchPart extends InstancePart implements Switch {
		SwitchPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A case of a switch in an instance.
	 */
	public static final class CasePart extends InstancePart implements Case {
		CasePart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A further place of a reference in an instance.
	 */
	public static final class SharedPlacementPart extends InstancePart implements SharedPlacement {
		SharedPlacementPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A placement of a sub-module in an instance.
	 */
	public static final class ModulePlacementPart extends InstancePart implements ModulePlacement {
		ModulePlacementPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * A repeated part of the structure in an instance.
	 */
	public static final class RepeatPart extends InstancePart implements Repeat {
		RepeatPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

	/**
	 * The structure of a module definition in an instance.
	 */
	public static final class ModuleDefinitionPart extends InstancePart implements ModuleDefinition {
		ModuleDefinitionPart(TLObject template, InstanceInfo instance) {
			super(template, instance);
		}
	}

}
