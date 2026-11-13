/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.security.ModelAccessRights;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.simple.SimpleBoundCommandGroup;
import com.top_logic.util.TLContext;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.runtime.FunctionKinds;

/**
 * The presets of the device templates (haumacher/ehome #146): named configurations of a channel of
 * an application program (<code>ehome.template:Preset</code>), saved from a configured channel of a
 * device and applied to the channels of other devices of the program.
 *
 * <p>
 * <b>A preset</b> holds the parameter values that make it (<code>values</code>, typed like a
 * device's settings), the roles of the channel's communication objects (<code>objects</code>) and
 * what it is for: an actuator preset the kind of the function the channel does, a source preset the
 * operations it can do with a function (toggle, on, off, on/off, show the status). It names the
 * channel it was saved from by its key ({@link ChannelShape}).
 * </p>
 *
 * <p>
 * <b>Saving</b> ({@link #save(TLObject, String, String, TLObject, TLObject, Collection, List, Map)})
 * takes the values the installer confirmed of those {@link #candidates(TLObject, String)} offers: the
 * values of the channel's part of the structure, the values of the inputs that bring its objects and
 * parameters in effect (also outside the channel and also where they have their default, as the mode
 * of the AKK-04's channel or the operation concept of the Tastsensor's keys), and every other value of
 * the device that differs from its default. The template of the program is created where it has none.
 * </p>
 *
 * <p>
 * <b>Where a preset applies</b> ({@link #offered(TLObject, String)}): on the channel it was saved
 * from, in every device of the program; on a channel the template declares alike to it
 * ({@link #declareAlike(TLObject, List)}: the channels' shapes map onto each other exactly), by
 * position; on the same channel of another instance of a module definition, by the module. It is
 * offered on another channel only when every parameter and object it names has a counterpart there.
 * </p>
 *
 * <p>
 * <b>Applying</b> ({@link #apply(TLObject, String, TLObject)}) sets the counterparts' values on the
 * device (each value once its switches show it, so that the order of the program's structure does not
 * matter) and records the use on the device (<code>ehome.physical:PresetUse</code>), one per channel.
 * A preset wanting another value for a parameter than a preset applied to another channel of the
 * device is refused, naming both. {@link #values(TLObject, String, TLObject)} and
 * {@link #objects(TLObject, String, TLObject)} give what a preset means for a channel without
 * changing anything, for a derivation that plans several presets at once
 * ({@link #conflict(TLObject, String, TLObject, String, TLObject)}).
 * </p>
 *
 * <p>
 * Presets are master data like the templates: who writes the templates (administrators) saves,
 * changes and deletes them; whoever writes a device applies one to it. The operations check the
 * rights themselves (Java is not access checked) and must run in a transaction.
 * </p>
 */
public final class Presets {

	private static final String PRESET = "ehome.template:Preset";

	private static final String VALUE = "ehome.template:PresetValue";

	private static final String OBJECT = "ehome.template:PresetObject";

	private static final String ALIKE = "ehome.template:AlikeChannel";

	private static final String USE = "ehome.physical:PresetUse";

	/** The roles of the objects of a source preset. */
	public static final List<String> SOURCE_ROLES = List.of("sendCommand", "listenStatus", "receiveStatus");

	/**
	 * A parameter value of a device the installer may save in a preset of a channel.
	 *
	 * @param view
	 *        The placement (of a module instance: its instance part).
	 * @param value
	 *        The typed value the device has (see {@link DeviceSettings#value(TLObject, ParameterView)}).
	 * @param inChannel
	 *        Whether the placement is part of the channel's part of the structure.
	 * @param differs
	 *        Whether the value differs from the default.
	 * @param proposed
	 *        Whether the value is proposed for the preset: a value of the channel that differs from
	 *        its default, or the value of an input that brings the channel's objects or parameters in
	 *        effect.
	 */
	public record Candidate(ParameterView view, Object value, boolean inChannel, boolean differs, boolean proposed) {
		// Value.
	}

	private Presets() {
		// Static only.
	}

	/**
	 * The keys of the channels of the device, see {@link ChannelShape#of(TLObject)}.
	 */
	public static List<String> channels(TLObject device) {
		return new ArrayList<>(ChannelShape.of(device).keySet());
	}

	/**
	 * The keys of the channels of the device that matter now: with communication objects in effect,
	 * with a preset offered or applied.
	 */
	public static List<String> activeChannels(TLObject device) {
		Set<Object> active = new LinkedHashSet<>(new DeviceConfiguration(device).getActiveInterfaces().values());
		List<String> result = new ArrayList<>();
		for (Map.Entry<String, ChannelShape> entry : ChannelShape.of(device).entrySet()) {
			String key = entry.getKey();
			if (entry.getValue().getObjects().stream().anyMatch(active::contains) || use(device, key) != null
				|| !offered(device, key).isEmpty()) {
				result.add(key);
			}
		}
		return result;
	}

	/**
	 * The communication objects (of the program, of a module definition) of the channel of the given
	 * key in the template's program, the parts a preset of the channel names.
	 */
	public static List<Interface> templateChannelObjects(TLObject template, String key) {
		Program program = program(template);
		ChannelShape shape = ChannelShape.of(program).get(key);
		if (shape != null) {
			return shape.getObjects();
		}
		Channel channel = ChannelShape.moduleChannel(program, key);
		return channel == null ? List.of() : ChannelShape.objectsOf(channel);
	}

	/**
	 * The keys of the channels of the template's program outside module instances.
	 */
	public static List<String> templateChannels(TLObject template) {
		return new ArrayList<>(ChannelShape.of(program(template)).keySet());
	}

	/**
	 * The name of the channel of the given key in the device, the key itself for a channel the device
	 * does not have.
	 */
	public static String channelLabel(TLObject device, String key) {
		ChannelShape shape = key == null ? null : ChannelShape.of(device).get(key);
		return shape == null ? key : shape.label(device);
	}

	/**
	 * The name of the channel of the given key in the template's program.
	 */
	public static String templateChannelLabel(TLObject template, String key) {
		ChannelShape shape = key == null ? null : ChannelShape.of(program(template)).get(key);
		if (shape == null) {
			Program program = program(template);
			if (ChannelShape.moduleChannel(program, key) != null) {
				return (String) ChannelShape.moduleChannel(program, key).tValueByName("displayName");
			}
			return key;
		}
		return shape.label(null);
	}

	/**
	 * The name of the channel a preset was saved from.
	 */
	public static String presetChannelLabel(TLObject preset) {
		TLObject template = preset == null ? null : preset.tContainer();
		return template == null ? null : templateChannelLabel(template, (String) preset.tValueByName("channel"));
	}

	/**
	 * The application program of a template.
	 */
	public static Program program(TLObject template) {
		return template != null && template.tValueByName("program") instanceof Program program ? program : null;
	}

	// ---------------------------------------------------------------------------------------------
	// Alike channels.

	/**
	 * The key of the channel the given one is declared alike to; the key itself for a channel declared
	 * alike to none.
	 */
	public static String model(TLObject template, String key) {
		for (TLObject alike : list(template, "alikeChannels")) {
			if (Objects.equals(alike.tValueByName("channel"), key)) {
				return (String) alike.tValueByName("model");
			}
		}
		return key;
	}

	/**
	 * The channel of the given key and those declared alike to it or to the same channel, in the order
	 * of the program; the channel alone if it is declared alike to none.
	 */
	public static List<String> alikeGroup(TLObject template, String key) {
		String model = model(template, key);
		List<String> result = new ArrayList<>();
		for (String channel : templateChannels(template)) {
			if (channel.equals(model) || model(template, channel).equals(model)) {
				result.add(channel);
			}
		}
		if (!result.contains(key)) {
			result.add(key);
		}
		return result;
	}

	/**
	 * Why the channel of the given key cannot be declared alike to the channel of the given model,
	 * <code>null</code> if it can: the shapes of the channels do not map onto each other exactly (see
	 * {@link ChannelShape}).
	 */
	public static ResKey alikeVeto(TLObject template, String key, String model) {
		Map<String, ChannelShape> shapes = ChannelShape.of(program(template));
		ChannelShape shape = shapes.get(key);
		ChannelShape modelShape = shapes.get(model);
		if (shape == null || modelShape == null) {
			return I18NConstants.ERROR_NO_CHANNEL__CHANNEL.fill(shape == null ? key : model);
		}
		return modelShape.map(shape, null).getProblem();
	}

	/**
	 * Why the channels of the given keys cannot be declared alike, <code>null</code> if they can: fewer
	 * than two are given, or one does not map onto the first (the channel the first is declared alike
	 * to), with the reason; see {@link #declareAlike(TLObject, List)}.
	 */
	public static ResKey declareAlikeVeto(TLObject template, List<String> keys) {
		List<String> channels = new ArrayList<>(new LinkedHashSet<>(keys));
		if (channels.size() < 2) {
			return I18NConstants.ERROR_ALIKE_TWO_CHANNELS;
		}
		String model = model(template, channels.get(0));
		for (String key : members(template, channels, model)) {
			ResKey veto = alikeVeto(template, key, model);
			if (veto != null) {
				return I18NConstants.ERROR_NOT_ALIKE__CHANNEL_MODEL_REASON.fill(templateChannelLabel(template, key),
					templateChannelLabel(template, model), veto);
			}
		}
		return null;
	}

	private static Set<String> members(TLObject template, List<String> channels, String model) {
		Set<String> members = new LinkedHashSet<>();
		for (String key : channels) {
			members.addAll(alikeGroup(template, key));
		}
		members.remove(model);
		return members;
	}

	/**
	 * Declares the channels of the given keys alike: every one alike to the first (or to the channel
	 * the first is declared alike to), together with the channels declared alike to them.
	 *
	 * @throws TopLogicException
	 *         If the current user may not write the templates, or with the reason of
	 *         {@link #declareAlikeVeto(TLObject, List)}.
	 */
	public static void declareAlike(TLObject template, List<String> keys) {
		checkTemplateWrite();
		ResKey veto = declareAlikeVeto(template, keys);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		List<String> channels = new ArrayList<>(new LinkedHashSet<>(keys));
		String model = model(template, channels.get(0));
		Set<String> members = members(template, channels, model);
		List<TLObject> declarations = new ArrayList<>();
		for (TLObject alike : list(template, "alikeChannels")) {
			Object channel = alike.tValueByName("channel");
			if (members.contains(channel) || model.equals(channel)) {
				alike.tDelete();
			} else {
				declarations.add(alike);
			}
		}
		for (String key : members) {
			TLObject alike = create(ALIKE);
			alike.tUpdateByName("channel", key);
			alike.tUpdateByName("model", model);
			declarations.add(alike);
		}
		template.tUpdateByName("alikeChannels", declarations);
	}

	/**
	 * Takes back the declarations of the channels of the given keys: a channel declared alike to
	 * another one is alike to none any more; for a channel others are declared alike to, the
	 * declarations of the whole group are taken back.
	 */
	public static void removeAlike(TLObject template, Collection<String> keys) {
		checkTemplateWrite();
		for (TLObject alike : list(template, "alikeChannels")) {
			if (keys.contains(alike.tValueByName("channel")) || keys.contains(alike.tValueByName("model"))) {
				alike.tDelete();
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Saving.

	/**
	 * The parameter values of the device the installer may save in a preset of the given channel, in
	 * the order the device's configuration shows them: the values of the channel's part of the
	 * structure, the inputs of the switches that bring its parts in effect, and every other value
	 * differing from its default; see {@link Candidate}. Only values the installer can change are
	 * offered.
	 */
	public static List<Candidate> candidates(TLObject device, String key) {
		ChannelShape shape = shape(device, key);
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Set<ParameterView> own = new LinkedHashSet<>(shape.getParameters());
		Set<ParameterView> proposed = new LinkedHashSet<>();
		Set<Object> active = new LinkedHashSet<>(configuration.getActiveInterfaces().values());
		for (ChannelShape.Item item : shape.getItems()) {
			boolean inEffect = item.part() instanceof Interface placed ? active.contains(placed)
				: configuration.isShown((ParameterView) item.part());
			if (!inEffect) {
				continue;
			}
			for (ChannelShape.Condition condition : item.conditions()) {
				if (condition.input() != null) {
					proposed.add(condition.input());
				}
			}
			if (item.part() instanceof ParameterView view && DeviceSettings.setting(device, view) != null) {
				proposed.add(view);
			}
		}
		List<Candidate> result = new ArrayList<>();
		for (Option item : configuration.getShownItems().keySet()) {
			if (!(item instanceof ParameterView view) || !settable(configuration, view)) {
				continue;
			}
			boolean differs = DeviceSettings.setting(device, view) != null;
			boolean inChannel = own.contains(view);
			boolean isProposed = proposed.contains(view);
			if (inChannel || differs || isProposed) {
				result.add(new Candidate(view, DeviceSettings.value(device, view), inChannel, differs, isProposed));
			}
		}
		return result;
	}

	private static boolean settable(DeviceConfiguration configuration, ParameterView view) {
		return DeviceConfiguration.isEditable(view) && !configuration.isAssigned(view)
			&& ParameterValues.valueAttribute(ParameterValues.valueType(view)) != null;
	}

	/**
	 * The communication objects of the channel in effect in the device: those the installer gives
	 * roles when saving a preset.
	 */
	public static List<Interface> channelObjects(TLObject device, String key) {
		ChannelShape shape = shape(device, key);
		Set<Interface> active = new LinkedHashSet<>(new DeviceConfiguration(device).getActiveInterfaces().values());
		List<Interface> result = new ArrayList<>();
		for (Interface placed : shape.getObjects()) {
			if (active.contains(placed)) {
				result.add(placed);
			}
		}
		return result;
	}

	/**
	 * Saves a preset of the given channel of the device in the template of its program (created where
	 * it has none).
	 *
	 * @param name
	 *        The name of the preset ("Schalten", "Taste: Umschalten").
	 * @param purpose
	 *        <code>ehome.template:PresetPurpose</code>.
	 * @param kind
	 *        The kind of the function of an actuator preset (<code>ehome.runtime:FunctionKind</code>).
	 * @param operations
	 *        The operations of a source preset (<code>ehome.template:SourceOperation</code>).
	 * @param views
	 *        The placements whose current values the preset holds: values of the device, see
	 *        {@link #candidates(TLObject, String)}.
	 * @param roles
	 *        The roles (<code>ehome.template:PresetRole</code>) of communication objects of the channel;
	 *        an object without a role is not part of the preset.
	 * @return The new preset.
	 * @throws TopLogicException
	 *         With the reason: the user may not write the templates, the name is empty or taken by a
	 *         preset of the channel, no value is given, a value or object is not the device's, or a
	 *         role does not fit the purpose.
	 */
	public static TLObject save(TLObject device, String key, String name, TLObject purpose, TLObject kind,
			Collection<?> operations, List<ParameterView> views, Map<Interface, TLObject> roles) {
		checkTemplateWrite();
		ChannelShape shape = shape(device, key);
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_PRESET_NO_NAME);
		}
		boolean source = isSource(purpose);
		if (!source && kind == null) {
			throw new TopLogicException(I18NConstants.ERROR_PRESET_NO_KIND);
		}
		if (views.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_PRESET_NO_VALUES);
		}
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Set<ParameterView> allowed = new LinkedHashSet<>();
		for (Candidate candidate : candidates(device, key)) {
			allowed.add(candidate.view());
		}
		for (ParameterView view : views) {
			if (!allowed.contains(view)) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_FOREIGN_VALUE__PARAMETER
					.fill(ChannelShape.parameterLabel(view)));
			}
		}
		List<String> allowedRoles = source ? SOURCE_ROLES : FunctionKinds.roles(name(kind));
		List<Interface> objects = channelObjects(device, key);
		for (Map.Entry<Interface, TLObject> entry : roles.entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			if (!objects.contains(entry.getKey())) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_FOREIGN_OBJECT__OBJECT
					.fill(ChannelShape.objectLabel(device, entry.getKey())));
			}
			if (!allowedRoles.contains(name(entry.getValue()))) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_ROLE__OBJECT_ROLE.fill(
					ChannelShape.objectLabel(device, entry.getKey()), entry.getValue()));
			}
		}
		Program program = configuration.getProgram();
		TLObject template = DeviceTemplates.template(program);
		if (template == null) {
			template = DeviceTemplates.create(program);
		}
		for (TLObject existing : list(template, "presets")) {
			if (key.equals(existing.tValueByName("channel")) && trimmed.equals(existing.tValueByName("name"))) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_NAME_TAKEN__NAME_CHANNEL.fill(trimmed,
					shape.label(device)));
			}
		}
		TLObject preset = create(PRESET);
		preset.tUpdateByName("name", trimmed);
		preset.tUpdateByName("channel", key);
		preset.tUpdateByName("purpose", purpose);
		preset.tUpdateByName("kind", source ? null : kind);
		preset.tUpdateByName("operations", source ? new ArrayList<>(operations) : List.of());
		List<TLObject> values = new ArrayList<>();
		Set<ParameterView> chosen = new LinkedHashSet<>(views);
		for (Candidate candidate : candidates(device, key)) {
			if (!chosen.contains(candidate.view())) {
				continue;
			}
			TLObject value = create(VALUE);
			value.tUpdateByName("parameter", ModuleParts.template(candidate.view()));
			ParameterType type = ParameterValues.valueType(ModuleParts.template(candidate.view()));
			value.tUpdateByName(ParameterValues.valueAttribute(type), candidate.value());
			values.add(value);
		}
		preset.tUpdateByName("values", values);
		List<TLObject> presetObjects = new ArrayList<>();
		for (Interface placed : objects) {
			TLObject role = roles.get(placed);
			if (role == null) {
				continue;
			}
			TLObject object = create(OBJECT);
			object.tUpdateByName("placement", ModuleParts.template(placed));
			object.tUpdateByName("role", role);
			presetObjects.add(object);
		}
		preset.tUpdateByName("objects", presetObjects);
		List<TLObject> presets = list(template, "presets");
		presets.add(preset);
		template.tUpdateByName("presets", presets);
		return preset;
	}

	/**
	 * The roles an object of the given preset may take: for an actuator preset the roles of its kind,
	 * for a source preset {@link #SOURCE_ROLES}.
	 */
	public static List<TLClassifier> roleOptions(TLObject preset) {
		List<String> names;
		if (preset == null) {
			names = List.of();
		} else if (isSource((TLObject) preset.tValueByName("purpose"))) {
			names = SOURCE_ROLES;
		} else {
			names = FunctionKinds.roles(name((TLObject) preset.tValueByName("kind")));
		}
		TLEnumeration roles = (TLEnumeration) TLModelUtil.findType("ehome.template:PresetRole");
		List<TLClassifier> result = new ArrayList<>();
		for (String role : names) {
			TLClassifier classifier = roles.getClassifier(role);
			if (classifier != null) {
				result.add(classifier);
			}
		}
		return result;
	}

	// ---------------------------------------------------------------------------------------------
	// Applying.

	/**
	 * The presets of the device's template offered on the given channel of the device, in the order of
	 * the template, see the class comment.
	 */
	public static List<TLObject> offered(TLObject device, String key) {
		List<TLObject> result = new ArrayList<>();
		TLObject template = DeviceTemplates.template(DeviceConfiguration.program(device));
		if (template == null || key == null) {
			return result;
		}
		for (TLObject preset : list(template, "presets")) {
			if (offerVeto(device, key, preset) == null) {
				result.add(preset);
			}
		}
		return result;
	}

	/**
	 * Why the given preset is not offered on the given channel of the device, <code>null</code> if it
	 * is.
	 */
	public static ResKey offerVeto(TLObject device, String key, TLObject preset) {
		try {
			resolve(device, key, preset);
			return null;
		} catch (TopLogicException ex) {
			return ex.getErrorKey();
		}
	}

	/**
	 * The values the given preset sets on the given channel of the device: the counterparts of its
	 * parameters there with its values, in the order of the preset.
	 *
	 * @throws TopLogicException
	 *         If the preset is not offered on the channel, with the reason.
	 */
	public static Map<ParameterView, Object> values(TLObject device, String key, TLObject preset) {
		return resolve(device, key, preset).values();
	}

	/**
	 * The communication objects of the given channel of the device taking the roles of the given
	 * preset, by the names of the roles (<code>ehome.template:PresetRole</code>), in the order of the
	 * preset.
	 *
	 * @throws TopLogicException
	 *         If the preset is not offered on the channel, with the reason.
	 */
	public static Map<String, Interface> objects(TLObject device, String key, TLObject preset) {
		return resolve(device, key, preset).objects();
	}

	/** What a preset means for a channel of a device. */
	private record Resolved(Map<ParameterView, Object> values, Map<String, Interface> objects) {
		// Value.
	}

	private static Resolved resolve(TLObject device, String key, TLObject preset) {
		Program program = DeviceConfiguration.program(device);
		TLObject template = preset == null ? null : preset.tContainer();
		if (template == null || program(template) != program) {
			throw new TopLogicException(I18NConstants.ERROR_PRESET_OTHER_PROGRAM__PRESET.fill(label(preset)));
		}
		Map<String, ChannelShape> shapes = ChannelShape.of(device);
		ChannelShape target = shapes.get(key);
		if (target == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CHANNEL__CHANNEL.fill(key));
		}
		String source = (String) preset.tValueByName("channel");
		Counterparts counterparts = counterparts(device, template, source, target, shapes);
		Map<ParameterView, Object> values = new LinkedHashMap<>();
		for (TLObject value : list(preset, "values")) {
			ParameterView view = (ParameterView) value.tValueByName("parameter");
			Option counterpart = view == null ? null : counterparts.of(view);
			if (!(counterpart instanceof ParameterView mapped)) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_NO_COUNTERPART__PRESET_CHANNEL_PARAMETER
					.fill(label(preset), target.label(device), view == null ? "?" : ChannelShape.parameterLabel(view)));
			}
			values.put(mapped, ParameterValues.value(value));
		}
		Map<String, Interface> objects = new LinkedHashMap<>();
		for (TLObject object : list(preset, "objects")) {
			Interface placed = (Interface) object.tValueByName("placement");
			Option counterpart = placed == null ? null : counterparts.of(placed);
			if (!(counterpart instanceof Interface mapped)) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_NO_OBJECT__PRESET_CHANNEL_OBJECT
					.fill(label(preset), target.label(device), placed == null ? "?" : ChannelShape.objectLabel(device, placed)));
			}
			if (object.tValueByName("role") instanceof TLClassifier role) {
				objects.putIfAbsent(role.getName(), mapped);
			}
		}
		return new Resolved(values, objects);
	}

	/** The counterparts of the parts a preset names (parts of the program, of a module definition). */
	private interface Counterparts {
		Option of(Option part);
	}

	private static Counterparts counterparts(TLObject device, TLObject template, String source, ChannelShape target,
			Map<String, ChannelShape> shapes) {
		InstanceInfo instance = target.getInstance();
		boolean same = target.getKey().equals(source);
		if (instance != null) {
			// A channel of a module instance: the module's parts in the instance (haumacher/ehome #119);
			// the program's parts on the channel the preset was saved from only.
			Program program = DeviceConfiguration.program(device);
			if (!same && ChannelShape.moduleChannel(program, source) != target.getModuleChannel()) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_OTHER_CHANNEL__PRESET_CHANNEL
					.fill(Presets.templateChannelLabel(template, source), target.label(device)));
			}
			ModuleDefinition definition = instance.getDefinition();
			return part -> {
				TLObject templatePart = ModuleParts.template(part);
				if (ModuleParts.definitionOf(templatePart) == definition) {
					return (Option) ModuleParts.wrap(templatePart, instance);
				}
				return same && ModuleParts.definitionOf(templatePart) == null ? (Option) templatePart : null;
			};
		}
		if (same) {
			return part -> ModuleParts.definitionOf(part) == null ? part : null;
		}
		ChannelShape from = shapes.get(source);
		if (from == null || !model(template, source).equals(model(template, target.getKey()))) {
			throw new TopLogicException(I18NConstants.ERROR_PRESET_OTHER_CHANNEL__PRESET_CHANNEL
				.fill(Presets.templateChannelLabel(template, source), target.label(device)));
		}
		ChannelShape.Mapping mapping = from.map(target, device);
		if (mapping.getProblem() != null) {
			throw new TopLogicException(mapping.getProblem());
		}
		return mapping::counterpart;
	}

	/**
	 * Why the given preset on the given channel of the device and another preset on another channel
	 * of it cannot be applied together, <code>null</code> if they can: they want different values for
	 * the same parameter (the same memory of the device). Both presets must be offered on their
	 * channels.
	 */
	public static ResKey conflict(TLObject device, String key, TLObject preset, String otherKey, TLObject other) {
		Map<ParameterView, Object> values = values(device, key, preset);
		Map<ParameterView, Object> otherValues = values(device, otherKey, other);
		Map<Object, ParameterView> byParameter = new LinkedHashMap<>();
		for (ParameterView view : otherValues.keySet()) {
			byParameter.putIfAbsent(parameter(view), view);
		}
		for (Map.Entry<ParameterView, Object> entry : values.entrySet()) {
			ParameterView view = entry.getKey();
			ParameterView otherView = byParameter.get(parameter(view));
			if (otherView == null) {
				continue;
			}
			String wanted = encode(view, entry.getValue());
			String otherWanted = encode(otherView, otherValues.get(otherView));
			if (!Objects.equals(wanted, otherWanted)) {
				return I18NConstants.ERROR_PRESET_CONFLICT__PRESET_CHANNEL_PARAMETER_VALUE_OTHER_OTHER_CHANNEL_OTHER_VALUE
					.fill(label(preset), channelLabel(device, key), ChannelShape.parameterLabel(view),
						display(view, entry.getValue()), label(other), channelLabel(device, otherKey),
						display(otherView, otherValues.get(otherView)));
			}
		}
		return null;
	}

	private static Object parameter(ParameterView view) {
		Parameter parameter = ModuleParts.template(view).getParameter();
		return ModuleParts.wrap(parameter, ModuleParts.instance(view));
	}

	private static String encode(ParameterView view, Object value) {
		return ParameterValues.encodeValue(ParameterValues.valueType(ModuleParts.template(view)), value);
	}

	private static Object display(ParameterView view, Object value) {
		if (value instanceof EnumerationValue entry) {
			return entry.tValueByName("name");
		}
		return value == null ? "" : value;
	}

	/**
	 * The presets applied to the device (<code>ehome.physical:PresetUse</code>).
	 */
	public static List<TLObject> uses(TLObject device) {
		return list(device, "presets");
	}

	/**
	 * The use of a preset on the given channel of the device, <code>null</code> if none is applied
	 * there.
	 */
	public static TLObject use(TLObject device, String key) {
		for (TLObject use : uses(device)) {
			if (Objects.equals(use.tValueByName("channel"), key)) {
				return use;
			}
		}
		return null;
	}

	/**
	 * Why the given preset cannot be applied to the given channel of the device, <code>null</code> if
	 * it can: the user may not write the device, the preset is not offered on the channel, or it
	 * conflicts with a preset applied to another channel of the device.
	 */
	public static ResKey applyVeto(TLObject device, String key, TLObject preset) {
		ResKey denied = DeviceSettings.writeVeto(device);
		if (denied != null) {
			return denied;
		}
		if (preset == null) {
			return I18NConstants.ERROR_NO_PRESET;
		}
		ResKey offer = offerVeto(device, key, preset);
		if (offer != null) {
			return offer;
		}
		for (TLObject use : uses(device)) {
			String otherKey = (String) use.tValueByName("channel");
			TLObject other = (TLObject) use.tValueByName("preset");
			if (otherKey.equals(key) || other == null || offerVeto(device, otherKey, other) != null) {
				// The preset applied there before is replaced; one no longer offered there wants nothing.
				continue;
			}
			ResKey conflict = conflict(device, key, preset, otherKey, other);
			if (conflict != null) {
				return conflict;
			}
		}
		return null;
	}

	/**
	 * Applies the given preset to the given channel of the device: sets the values of
	 * {@link #values(TLObject, String, TLObject)} and records the use, replacing the use of another
	 * preset on the channel.
	 *
	 * @return The use (<code>ehome.physical:PresetUse</code>).
	 * @throws TopLogicException
	 *         With the reason of {@link #applyVeto(TLObject, String, TLObject)}, or why a value cannot be
	 *         set (nothing is changed then if the caller rolls the transaction back).
	 */
	public static TLObject apply(TLObject device, String key, TLObject preset) {
		ResKey veto = applyVeto(device, key, preset);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		Map<ParameterView, Object> pending = new LinkedHashMap<>(values(device, key, preset));
		while (!pending.isEmpty()) {
			boolean progress = false;
			ResKey first = null;
			ParameterView blocked = null;
			for (Map.Entry<ParameterView, Object> entry : new ArrayList<>(pending.entrySet())) {
				ParameterView view = entry.getKey();
				ResKey edit = DeviceSettings.editVeto(device, view);
				if (edit != null) {
					if (first == null) {
						first = edit;
						blocked = view;
					}
					continue;
				}
				DeviceSettings.setValue(device, view, applicable(entry.getValue()));
				pending.remove(view);
				progress = true;
			}
			if (!progress) {
				throw new TopLogicException(I18NConstants.ERROR_PRESET_VALUE__PRESET_PARAMETER_REASON
					.fill(label(preset), ChannelShape.parameterLabel(blocked), first));
			}
		}
		TLObject existing = use(device, key);
		if (existing != null) {
			existing.tDelete();
		}
		TLObject use = create(USE);
		use.tUpdateByName("preset", preset);
		use.tUpdateByName("channel", key);
		List<TLObject> uses = uses(device);
		uses.add(use);
		device.tUpdateByName("presets", uses);
		return use;
	}

	/**
	 * A value of a preset as {@link DeviceSettings#setValue(TLObject, ParameterView, Object)} takes it
	 * for the counterpart: an entry of an enumeration by its value, as the counterpart's type may be
	 * another one with the same values.
	 */
	private static Object applicable(Object value) {
		return value instanceof EnumerationValue entry ? Long.valueOf(entry.getUnsignedValue()) : value;
	}

	/**
	 * Removes the use of a preset from its device; the values it set stay.
	 */
	public static void removeUse(TLObject use) {
		TLObject device = use == null ? null : use.tContainer();
		if (device == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PRESET);
		}
		ResKey denied = DeviceSettings.writeVeto(device);
		if (denied != null) {
			throw new TopLogicException(denied);
		}
		use.tDelete();
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers.

	private static ChannelShape shape(TLObject device, String key) {
		ChannelShape shape = key == null ? null : ChannelShape.of(device).get(key);
		if (shape == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_CHANNEL__CHANNEL.fill(key));
		}
		return shape;
	}

	/**
	 * Why the current user may not write the device templates (administrators only,
	 * <code>ehome.security.config.xml</code>), <code>null</code> if the user may; code running without
	 * a user (a test, a job) is not checked.
	 */
	public static ResKey templateWriteVeto() {
		Person person = TLContext.getContext() == null ? null : TLContext.getContext().getPerson();
		TLObject root = DeviceTemplates.root();
		if (person == null || root == null
			|| ModelAccessRights.getInstance().isAllowed(person, root, SimpleBoundCommandGroup.WRITE)) {
			return null;
		}
		return I18NConstants.ERROR_TEMPLATES_NOT_ALLOWED;
	}

	private static void checkTemplateWrite() {
		ResKey denied = templateWriteVeto();
		if (denied != null) {
			throw new TopLogicException(denied);
		}
	}

	private static boolean isSource(TLObject purpose) {
		return "source".equals(name(purpose));
	}

	private static String name(TLObject classifier) {
		return classifier instanceof TLClassifier value ? value.getName() : null;
	}

	private static String label(TLObject preset) {
		return preset == null ? "?" : String.valueOf(preset.tValueByName("name"));
	}

	private static List<TLObject> list(TLObject holder, String reference) {
		List<TLObject> result = new ArrayList<>();
		if (holder != null && holder.tValueByName(reference) instanceof Collection<?> values) {
			for (Object value : values) {
				result.add((TLObject) value);
			}
		}
		return result;
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

}
