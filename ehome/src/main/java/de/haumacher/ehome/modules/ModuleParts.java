/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.modules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey.LiteralKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;

import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.ModuleArgument;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.ModulePlacement;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.PropertyLocation;
import de.haumacher.ehome.model.operational.Repeat;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;

/**
 * The effective values of the parts of a dynamic module in an instance (haumacher/ehome #119, rule
 * 3 of the design): the one place that knows how an instance changes a part of its module
 * definition.
 *
 * <ul>
 * <li>The number of a communication object is its <code>Number</code> plus the value of the argument
 * its <code>BaseNumber</code> names ({@link Port#getBaseNumber()}).</li>
 * <li>The byte offset of a parameter's location is its <code>Offset</code> plus the value of the
 * argument its <code>BaseOffset</code> names ({@link Location#getBaseOffset()}); the bit offset is
 * unchanged.</li>
 * <li>A text (in every language) has <code>{{name}}</code> replaced by the value of the instance's
 * argument of that name; other placeholders (<code>{{0}}</code> of a text parameter) stay.</li>
 * <li>A part of the module the part refers to or contains is that part in the same instance.</li>
 * </ul>
 *
 * <p>
 * A part of a module in an instance is an {@link InstancePart} ({@link #wrap(TLObject, InstanceInfo)}):
 * the configuration of a device (<code>DeviceConfiguration</code>) gives the parameters and objects of
 * module instances as such, and a setting or connector of a module instance (which stores the part of
 * the template and the instance) gives its part as such through {@link #parameter(TLObject)} and
 * {@link #port(TLObject)}. A part outside any module is its own effective value.
 * </p>
 */
public final class ModuleParts {

	/** The attribute of a setting or connector naming its module instance. */
	public static final String INSTANCE = "instance";

	/** The attribute of a setting naming its parameter reference. */
	public static final String PARAMETER = "parameter";

	/** The attribute of a connector naming its communication object reference. */
	public static final String PORT = "port";

	private static final String EXT_ID = "extId";

	private static final String INDEX = "index";

	private static final String BYTE_OFFSET = "byteOffset";

	/** A placeholder of an argument: <code>{{name}}</code>. */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}:]+)\\}\\}");

	private ModuleParts() {
		// Static helpers only.
	}

	/**
	 * The given part of a module definition as it is in the given instance; anything else (a part of
	 * another module, a part of the program, an instance part) as it is.
	 */
	public static TLObject wrap(TLObject obj, InstanceInfo instance) {
		if (obj == null || instance == null || obj instanceof InstancePart) {
			return obj;
		}
		if (!wrappable(obj) || definitionOf(obj) != instance.getDefinition()) {
			return obj;
		}
		if (obj instanceof ParameterView) {
			return new InstancePart.ParameterViewPart(obj, instance);
		}
		if (obj instanceof Interface) {
			return new InstancePart.InterfacePart(obj, instance);
		}
		if (obj instanceof Parameter) {
			return new InstancePart.ParameterPart(obj, instance);
		}
		if (obj instanceof Port) {
			return new InstancePart.PortPart(obj, instance);
		}
		if (obj instanceof Pointer) {
			return new InstancePart.PointerPart(obj, instance);
		}
		if (obj instanceof PropertyLocation) {
			return new InstancePart.PropertyLocationPart(obj, instance);
		}
		if (obj instanceof ParameterGroup) {
			return new InstancePart.ParameterGroupPart(obj, instance);
		}
		if (obj instanceof Channel) {
			return new InstancePart.ChannelPart(obj, instance);
		}
		if (obj instanceof ChannelIndependent) {
			return new InstancePart.ChannelIndependentPart(obj, instance);
		}
		if (obj instanceof Switch) {
			return new InstancePart.SwitchPart(obj, instance);
		}
		if (obj instanceof Case) {
			return new InstancePart.CasePart(obj, instance);
		}
		if (obj instanceof SharedPlacement) {
			return new InstancePart.SharedPlacementPart(obj, instance);
		}
		if (obj instanceof ModulePlacement) {
			return new InstancePart.ModulePlacementPart(obj, instance);
		}
		if (obj instanceof Repeat) {
			return new InstancePart.RepeatPart(obj, instance);
		}
		return new InstancePart.ModuleDefinitionPart(obj, instance);
	}

	private static boolean wrappable(TLObject obj) {
		return obj instanceof ParameterView || obj instanceof Interface || obj instanceof Parameter
			|| obj instanceof Port || obj instanceof Pointer || obj instanceof PropertyLocation
			|| obj instanceof ParameterGroup || obj instanceof Channel || obj instanceof ChannelIndependent
			|| obj instanceof Switch || obj instanceof Case || obj instanceof SharedPlacement
			|| obj instanceof ModulePlacement || obj instanceof Repeat || obj instanceof ModuleDefinition;
	}

	/**
	 * The module definition the given part belongs to (the nearest one containing it, itself for a
	 * module definition), <code>null</code> for a part of the program outside any module.
	 */
	public static ModuleDefinition definitionOf(TLObject obj) {
		for (TLObject current = template(obj); current != null; current = current.tContainer()) {
			if (current instanceof ModuleDefinition definition) {
				return definition;
			}
			if (current instanceof Program) {
				return null;
			}
		}
		return null;
	}

	/**
	 * The program the given part belongs to, <code>null</code> if it belongs to none.
	 */
	public static Program programOf(TLObject obj) {
		for (TLObject current = template(obj); current != null; current = current.tContainer()) {
			if (current instanceof Program program) {
				return program;
			}
		}
		return null;
	}

	/**
	 * The value of an attribute of a part of a module definition as it is in the given instance, see
	 * the class comment.
	 *
	 * @param template
	 *        The part of the template.
	 * @param part
	 *        The attribute.
	 * @param value
	 *        Its value in the part of the template.
	 */
	public static Object adapt(TLObject template, TLStructuredTypePart part, Object value, InstanceInfo instance) {
		if (value == null) {
			return null;
		}
		if (value instanceof String text) {
			return substitute(text, instance);
		}
		if (value instanceof ResKey key) {
			return substitute(key, instance);
		}
		if (value instanceof TLObject obj) {
			return wrap(obj, instance);
		}
		if (value instanceof Collection<?> collection) {
			List<Object> result = new ArrayList<>(collection.size());
			for (Object element : collection) {
				result.add(element instanceof TLObject obj ? wrap(obj, instance) : element);
			}
			return result;
		}
		if (value instanceof Number number) {
			String name = part.getName();
			if (INDEX.equals(name) && template instanceof Port port) {
				return add(number, instance, port.getBaseNumber());
			}
			if (BYTE_OFFSET.equals(name) && template instanceof Location location) {
				return add(number, instance, location.getBaseOffset());
			}
		}
		return value;
	}

	private static Object add(Number number, InstanceInfo instance, ModuleArgument base) {
		if (base == null) {
			return number;
		}
		return Integer.valueOf((int) (number.longValue() + instance.number(base)));
	}

	/**
	 * The given text with <code>{{name}}</code> replaced by the value of the argument of that name in
	 * the given instance; placeholders without an argument of their name stay.
	 */
	public static String substitute(String text, InstanceInfo instance) {
		if (text == null || instance == null || !text.contains("{{")) {
			return text;
		}
		Matcher matcher = PLACEHOLDER.matcher(text);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String value = instance.valueByName(matcher.group(1));
			matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? matcher.group() : value));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * The given text with the arguments of the instance filled in each language, see
	 * {@link #substitute(String, InstanceInfo)}.
	 */
	public static ResKey substitute(ResKey key, InstanceInfo instance) {
		if (!(key instanceof LiteralKey literal) || instance == null) {
			return key;
		}
		boolean placeholders = false;
		for (String text : literal.getTranslations().values()) {
			placeholders |= text != null && text.contains("{{");
		}
		if (!placeholders) {
			return key;
		}
		ResKey.Builder builder = ResKey.builder();
		for (Map.Entry<Locale, String> entry : literal.getTranslations().entrySet()) {
			builder.add(entry.getKey(), substitute(entry.getValue(), instance));
		}
		return builder.build();
	}

	/**
	 * The part of the template of an instance part; anything else as it is.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T template(T obj) {
		return obj instanceof InstancePart part ? (T) part.getTemplate() : obj;
	}

	/**
	 * The instance of an instance part, <code>null</code> for anything else.
	 */
	public static InstanceInfo instance(Object obj) {
		return obj instanceof InstancePart part ? part.getInstance() : null;
	}

	/**
	 * The parameter reference a setting of a device (<code>ehome.physical:ParameterSetting</code>) sets,
	 * as it is in the setting's module instance; <code>null</code> if it names none.
	 */
	public static ParameterView parameter(TLObject setting) {
		return (ParameterView) effective(setting, PARAMETER);
	}

	/**
	 * The communication object reference of a connector of a device (<code>ehome.physical:Connector</code>),
	 * as it is in the connector's module instance; <code>null</code> if it names none.
	 */
	public static Interface port(TLObject connector) {
		return (Interface) effective(connector, PORT);
	}

	private static TLObject effective(TLObject holder, String attribute) {
		if (holder == null || !(holder.tValueByName(attribute) instanceof TLObject template)) {
			return null;
		}
		if (holder.tType().getPart(INSTANCE) == null
			|| !(holder.tValueByName(INSTANCE) instanceof TLObject stored)) {
			return template;
		}
		return wrap(template, InstanceInfo.of(stored));
	}

	/**
	 * Lets a setting or connector of a device name the given (instance) part: the part of the template
	 * and the module instance the device stores for it.
	 *
	 * @param holder
	 *        The setting or connector.
	 * @param attribute
	 *        {@link #PARAMETER} or {@link #PORT}.
	 */
	public static void bind(TLObject holder, String attribute, TLObject part) {
		holder.tUpdateByName(attribute, template(part));
		InstanceInfo instance = instance(part);
		holder.tUpdateByName(INSTANCE, instance == null ? null : ModuleInstances.stored(instance));
	}

	/**
	 * Whether a setting or connector of a device names the given (instance) part.
	 *
	 * @param attribute
	 *        {@link #PARAMETER} or {@link #PORT}.
	 */
	public static boolean refersTo(TLObject holder, String attribute, TLObject part) {
		if (holder.tValueByName(attribute) != template(part)) {
			return false;
		}
		return sameInstance(holder, instance(part));
	}

	/**
	 * Whether a setting or connector of a device belongs to the given instance (<code>null</code>: to
	 * no module instance).
	 */
	public static boolean sameInstance(TLObject holder, InstanceInfo instance) {
		Object stored = holder.tType().getPart(INSTANCE) == null ? null : holder.tValueByName(INSTANCE);
		if (instance == null) {
			return stored == null;
		}
		return stored instanceof TLObject obj && instance.getId().equals(obj.tValueByName(ModuleInstances.NAME));
	}

	/**
	 * The ID of the module instance a setting or connector belongs to, <code>null</code> outside of
	 * module instances.
	 */
	public static String instanceId(TLObject holder) {
		Object stored = holder.tType().getPart(INSTANCE) == null ? null : holder.tValueByName(INSTANCE);
		return stored instanceof TLObject obj ? (String) obj.tValueByName(ModuleInstances.NAME) : null;
	}

	/**
	 * Whether a connector of a device is in use: not marked inactive, and its module instance (if any)
	 * in effect.
	 */
	public static boolean inEffect(TLObject connector) {
		if (Boolean.FALSE.equals(connector.tValueByName("active"))) {
			return false;
		}
		Object stored = connector.tType().getPart(INSTANCE) == null ? null : connector.tValueByName(INSTANCE);
		return !(stored instanceof TLObject instance) || !Boolean.FALSE.equals(instance.tValueByName("active"));
	}

	/**
	 * The ID ETS gives the given part in a device: the ID of the part itself, for a part of a module
	 * instance the ID of the program, the instance and the part within its module
	 * (<code>M-0083_A-..._MD-1_M-1_MI-1_P-3_R-1</code>).
	 */
	public static String partId(TLObject part) {
		TLObject template = template(part);
		String id = template == null ? null : (String) template.tValueByName(EXT_ID);
		InstanceInfo instance = instance(part);
		if (id == null || instance == null) {
			return id;
		}
		String definition = instance.getDefinition().getExtId();
		Program program = programOf(template);
		String programId = program == null ? null : program.getExtId();
		if (definition == null || programId == null || !id.startsWith(definition + "_")) {
			return id;
		}
		return programId + "_" + instance.getId() + id.substring(definition.length());
	}

	/**
	 * The ID ETS gives the part a setting or connector names, see {@link #partId(TLObject)}.
	 *
	 * @param attribute
	 *        {@link #PARAMETER} or {@link #PORT}.
	 */
	public static String partId(TLObject holder, String attribute) {
		return partId(effective(holder, attribute));
	}

}
