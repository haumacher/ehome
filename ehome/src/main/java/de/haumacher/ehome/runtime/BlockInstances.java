/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.BlockRegistry;
import de.haumacher.ehome.blocks.BlockSet;
import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;

/**
 * The virtual devices of a project (haumacher/ehome #75, homecontrol <code>spec/blocks.md</code>):
 * the instances of block types (<code>ehome.runtime:BlockInstance</code>) with their ports
 * (<code>BlockPort</code>, bound to datapoints through <code>BlockBinding</code>s) and parameters
 * (<code>BlockParameter</code>).
 *
 * <p>
 * The block types are not objects of the model: they are E-Home's Java ports of the firmware's
 * built-in types ({@link BlockRegistry#builtins()}), which the simulation runs and whose manifests
 * the image is checked against, named by an instance with their id and version. So the manifest an
 * instance is edited against is the one the simulation runs and the validator checks, without a copy
 * that could drift.
 * </p>
 *
 * <p>
 * An instance is the datapoint <code>block/&lt;id&gt;/enabled</code> of the image, an adjustable
 * parameter <code>block/&lt;id&gt;/&lt;parameter&gt;</code>, a port
 * <code>block/&lt;id&gt;/&lt;port&gt;</code>: an output always, an input while it binds several
 * datapoints (<code>spec/blocks.md</code> 6).
 * </p>
 */
public final class BlockInstances {

	/** The model type of an instance. */
	public static final String INSTANCE = "ehome.runtime:BlockInstance";

	/** The model type of a port. */
	public static final String PORT = "ehome.runtime:BlockPort";

	/** The model type of a parameter. */
	public static final String PARAMETER = "ehome.runtime:BlockParameter";

	/** The model type of a binding. */
	public static final String BINDING = "ehome.runtime:BlockBinding";

	/** The reference of a project to its instances. */
	public static final String REFERENCE = "blocks";

	private BlockInstances() {
		// Static helpers.
	}

	/** The block types a project may use. */
	public static BlockRegistry registry() {
		return BlockRegistry.builtins();
	}

	private static boolean is(TLObject obj, String name) {
		return obj != null && obj.tValid() && "ehome.runtime".equals(obj.tType().getModule().getName())
			&& name.equals(obj.tType().getName());
	}

	/** Whether the object is a virtual device. */
	public static boolean isInstance(TLObject obj) {
		return is(obj, "BlockInstance");
	}

	/** Whether the object is a port of a virtual device. */
	public static boolean isPort(TLObject obj) {
		return is(obj, "BlockPort");
	}

	/** Whether the object is a parameter of a virtual device. */
	public static boolean isParameter(TLObject obj) {
		return is(obj, "BlockParameter");
	}

	/** Whether the object is a virtual device, one of its ports or parameters. */
	public static boolean isBlockDatapoint(TLObject obj) {
		return isInstance(obj) || isPort(obj) || isParameter(obj);
	}

	/** The virtual devices of the project in block order. */
	public static List<TLObject> of(TLObject project) {
		return project == null ? List.of() : RuleExport.objects(project.tValueByName(REFERENCE));
	}

	/** The type of the instance, <code>null</code> when E-Home has no such type. */
	public static BlockType type(TLObject instance) {
		Object type = instance.tValueByName("blockType");
		Object version = instance.tValueByName("version");
		return type == null || version == null ? null : registry().resolve(type.toString(), version.toString());
	}

	/** The identifier of the instance, <code>null</code> for none. */
	public static String identifier(TLObject instance) {
		Object id = instance.tValueByName("identifier");
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

	/** The port of the instance with the id, <code>null</code> if none. */
	public static TLObject port(TLObject instance, String id) {
		for (TLObject port : RuleExport.objects(instance.tValueByName("ports"))) {
			if (id.equals(port.tValueByName("port"))) {
				return port;
			}
		}
		return null;
	}

	/** The parameter of the instance with the id, <code>null</code> if none. */
	public static TLObject parameter(TLObject instance, String id) {
		for (TLObject parameter : RuleExport.objects(instance.tValueByName("parameters"))) {
			if (id.equals(parameter.tValueByName("parameter"))) {
				return parameter;
			}
		}
		return null;
	}

	/** The declaration of a port, <code>null</code> when its type does not declare it. */
	public static Port portDecl(TLObject port) {
		BlockType type = port.tContainer() == null ? null : type(port.tContainer());
		int index = type == null ? -1 : type.manifest().port(String.valueOf(port.tValueByName("port")));
		return index < 0 ? null : type.manifest().ports().get(index);
	}

	/** The declaration of a parameter, <code>null</code> when its type does not declare it. */
	public static Param paramDecl(TLObject parameter) {
		BlockType type = parameter.tContainer() == null ? null : type(parameter.tContainer());
		int index = type == null ? -1 : type.manifest().param(String.valueOf(parameter.tValueByName("parameter")));
		return index < 0 ? null : type.manifest().params().get(index);
	}

	/** The datapoints bound to the port, in binding order (deleted ones left out). */
	public static List<TLObject> bound(TLObject port) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject binding : RuleExport.objects(port.tValueByName("bindings"))) {
			if (binding.tValueByName("datapoint") instanceof TLObject datapoint && datapoint.tValid()) {
				result.add(datapoint);
			}
		}
		return result;
	}

	/**
	 * The id the virtual device, port or parameter has in the image
	 * (<code>block/&lt;id&gt;/&lt;x&gt;</code>), <code>null</code> for another object.
	 */
	public static String imageId(TLObject target) {
		if (isInstance(target)) {
			String id = identifier(target);
			return id == null ? null : BlockSet.mirrorName(id, "enabled");
		}
		if (isPort(target) || isParameter(target)) {
			String id = target.tContainer() == null ? null : identifier(target.tContainer());
			Object member = target.tValueByName(isPort(target) ? "port" : "parameter");
			return id == null || member == null ? null : BlockSet.mirrorName(id, member.toString());
		}
		return null;
	}

	/**
	 * Whether the image has the datapoint the object stands for: an instance, an adjustable
	 * parameter, an output, an input bound to several datapoints (<code>spec/blocks.md</code> 6).
	 */
	public static boolean hasMirror(TLObject target) {
		if (isInstance(target)) {
			return type(target) != null;
		}
		if (isParameter(target)) {
			Param decl = paramDecl(target);
			return decl != null && decl.adjustable();
		}
		if (isPort(target)) {
			Port decl = portDecl(target);
			return decl != null && (!decl.isInput() || bound(target).size() > 1);
		}
		return false;
	}

	/**
	 * The datapoints of the project's virtual devices, in the order of the image
	 * (<code>spec/blocks.md</code> 6): per instance <code>enabled</code>, the adjustable parameters,
	 * the outputs, the aggregated inputs.
	 */
	public static List<TLObject> datapoints(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject instance : of(project)) {
			BlockType type = type(instance);
			if (type == null) {
				continue;
			}
			result.add(instance);
			for (Param decl : type.manifest().params()) {
				TLObject parameter = parameter(instance, decl.id());
				if (decl.adjustable() && parameter != null) {
					result.add(parameter);
				}
			}
			for (Port decl : type.manifest().ports()) {
				TLObject port = port(instance, decl.id());
				if (!decl.isInput() && port != null) {
					result.add(port);
				}
			}
			for (Port decl : type.manifest().ports()) {
				TLObject port = port(instance, decl.id());
				if (decl.isInput() && port != null && bound(port).size() > 1) {
					result.add(port);
				}
			}
		}
		return result;
	}

	/**
	 * The value type of the datapoint the object stands for in the image (the spec's names),
	 * <code>null</code> for another object.
	 */
	public static String valueType(TLObject target) {
		if (isInstance(target)) {
			return ValueTypes.BOOLEAN;
		}
		if (isParameter(target)) {
			Param decl = paramDecl(target);
			return decl == null ? null : decl.type().datapointType().externalName();
		}
		if (isPort(target)) {
			Port decl = portDecl(target);
			return decl == null ? null : decl.type().datapointType().externalName();
		}
		return null;
	}

	/** Whether the datapoint the object stands for may be commanded (the enable switch, a parameter). */
	public static boolean writable(TLObject target) {
		if (isInstance(target)) {
			return !Boolean.FALSE.equals(target.tValueByName("enabledAdjustable"));
		}
		return isParameter(target) && hasMirror(target);
	}

	/**
	 * The output a datapoint of a chart stands for: the datapoint an output port is bound to (the
	 * mirror of an output keeps no history, <code>spec/blocks.md</code> 6: a chart shows the bound
	 * datapoint, which keeps one); every other datapoint itself.
	 */
	public static TLObject historyTarget(TLObject datapoint) {
		if (isPort(datapoint)) {
			Port decl = portDecl(datapoint);
			List<TLObject> bound = bound(datapoint);
			if (decl != null && !decl.isInput() && !bound.isEmpty()) {
				return bound.get(0);
			}
		}
		return datapoint;
	}

	// ---- Creating ----------------------------------------------------------------------------

	/**
	 * Creates a virtual device of the type in the project (to be called in a transaction): with a
	 * new identifier derived from its name (stable from then on), a port per port and a parameter per
	 * parameter of the type, every parameter at the type's default.
	 *
	 * @param typeKey
	 *        <code>&lt;type id&gt; &lt;major&gt;.&lt;minor&gt;</code> as {@link #typeKeys()} lists them.
	 * @param name
	 *        The name; <code>null</code>: the type's label.
	 */
	public static TLObject create(TLObject project, String typeKey, TLObject room, ResKey name) {
		BlockType type = typeOfKey(typeKey);
		if (type == null) {
			throw new IllegalArgumentException("No block type '" + typeKey + "'.");
		}
		Manifest m = type.manifest();
		ResKey label = name != null ? name : FunctionProposal.text(typeLabel(m));
		TLObject instance = createObject(INSTANCE);
		Set<String> ids = new HashSet<>();
		for (TLObject other : of(project)) {
			String id = identifier(other);
			if (id != null) {
				ids.add(id);
			}
		}
		String base = com.top_logic.util.Resources.getInstance().getString(label);
		instance.tUpdateByName("identifier", FunctionProposal.uniqueIdentifier(base, ids));
		instance.tUpdateByName("name", label);
		instance.tUpdateByName("blockType", m.type());
		instance.tUpdateByName("version", m.version());
		instance.tUpdateByName("room", room);
		List<TLObject> ports = new ArrayList<>();
		for (Port decl : m.ports()) {
			TLObject port = createObject(PORT);
			port.tUpdateByName("port", decl.id());
			ports.add(port);
		}
		instance.tUpdateByName("ports", ports);
		List<TLObject> params = new ArrayList<>();
		for (Param decl : m.params()) {
			TLObject param = createObject(PARAMETER);
			param.tUpdateByName("parameter", decl.id());
			params.add(param);
		}
		instance.tUpdateByName("parameters", params);
		List<Object> all = new ArrayList<>(of(project));
		all.add(instance);
		project.tUpdateByName(REFERENCE, all);
		return instance;
	}

	/**
	 * Binds the datapoint to the port (to be called in a transaction): a new binding at the end,
	 * which keeps the datapoint's id in the image.
	 */
	public static TLObject bind(TLObject port, TLObject datapoint) {
		TLObject binding = createObject(BINDING);
		binding.tUpdateByName("datapoint", datapoint);
		binding.tUpdateByName("boundId", datapointImageId(datapoint));
		List<Object> bindings = new ArrayList<>(RuleExport.objects(port.tValueByName("bindings")));
		bindings.add(binding);
		port.tUpdateByName("bindings", bindings);
		return binding;
	}

	/** The id of any datapoint of the project in the image, as the pages name them. */
	public static String datapointImageId(TLObject datapoint) {
		String id = imageId(datapoint);
		return id != null ? id : Pages.imageId(datapoint);
	}

	private static TLObject createObject(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	/** The keys of the block types (<code>&lt;type id&gt; &lt;major&gt;.&lt;minor&gt;</code>). */
	public static List<String> typeKeys() {
		List<String> result = new ArrayList<>();
		for (BlockType type : registry().types()) {
			result.add(typeKey(type.manifest()));
		}
		return result;
	}

	/** The key of a manifest. */
	public static String typeKey(Manifest m) {
		return m.type() + " " + m.version();
	}

	/** The type of a key of {@link #typeKeys()}, <code>null</code> for none. */
	public static BlockType typeOfKey(String key) {
		int space = key == null ? -1 : key.indexOf(' ');
		return space < 0 ? null : registry().resolve(key.substring(0, space), key.substring(space + 1));
	}

	/** The label of a type in the languages E-Home knows. */
	public static Map<String, String> typeLabel(Manifest m) {
		Map<String, String> result = new LinkedHashMap<>();
		result.put("en", m.label());
		if (RoomControllerTexts.TYPE.equals(m.type())) {
			result.put("de", RoomControllerTexts.LABEL_DE);
		}
		return result;
	}

	// ---- Export ------------------------------------------------------------------------------

	/**
	 * A problem of an instance the image cannot express (a binding whose datapoint was deleted), at
	 * the path of the image it concerns.
	 *
	 * @param path
	 *        The path (<code>blocks[0].inputs.temperature[1]</code>).
	 * @param reason
	 *        Why, naming the instance and the port.
	 */
	public record Problem(String path, String reason) {
		// A value.
	}

	/**
	 * The member <code>blocks</code> of the image (<code>spec/blocks.md</code> 4): per instance only
	 * the members it needs; a parameter only when it is set, a choice by its id, a duration in ms, a
	 * time of day as an object.
	 *
	 * @param ids
	 *        The id of a datapoint of the project in the image, <code>null</code> for none.
	 * @param problems
	 *        Receives what the image cannot express.
	 * @param sources
	 *        Receives the object of the project per path.
	 * @return <code>null</code> without instances.
	 */
	public static List<Object> json(TLObject project, Function<TLObject, String> ids, List<Problem> problems,
			Map<String, TLObject> sources) {
		List<TLObject> instances = of(project);
		if (instances.isEmpty()) {
			return null;
		}
		List<Object> result = new ArrayList<>();
		for (TLObject instance : instances) {
			String path = "blocks[" + result.size() + "]";
			sources.put(path, instance);
			Map<String, Object> block = new LinkedHashMap<>();
			String id = identifier(instance);
			block.put("id", id == null ? "" : id);
			block.put("type", String.valueOf(instance.tValueByName("blockType")));
			block.put("version", String.valueOf(instance.tValueByName("version")));
			Object label = RuntimeImage.labels(instance.tValueByName("name"));
			block.put("label", label != null ? label : block.get("id"));
			if (Boolean.FALSE.equals(instance.tValueByName("enabled"))) {
				block.put("enabled", Boolean.FALSE);
			}
			if (Boolean.FALSE.equals(instance.tValueByName("enabledAdjustable"))) {
				block.put("enabledAdjustable", Boolean.FALSE);
			}
			if (instance.tValueByName("description") instanceof String description && !description.isBlank()) {
				block.put("description", description.trim());
			}
			result.add(block);
			BlockType type = type(instance);
			if (type == null) {
				// The validator refuses the unknown type with the device's reason.
				continue;
			}
			Manifest m = type.manifest();
			String name = String.valueOf(com.top_logic.util.Resources.getInstance().getString(
				instance.tValueByName("name") instanceof ResKey key ? key : ResKey.text(String.valueOf(id))));
			Map<String, Object> inputs = new LinkedHashMap<>();
			Map<String, Object> aggregation = new LinkedHashMap<>();
			Map<String, Object> outputs = new LinkedHashMap<>();
			for (Port decl : m.ports()) {
				TLObject port = port(instance, decl.id());
				if (port == null) {
					continue;
				}
				String member = decl.isInput() ? "inputs" : "outputs";
				List<String> names = new ArrayList<>();
				List<TLObject> bindings = RuleExport.objects(port.tValueByName("bindings"));
				for (int k = 0; k < bindings.size(); k++) {
					TLObject binding = bindings.get(k);
					String at = path + "." + member + "." + decl.id() + (decl.isInput() && bindings.size() > 1 ? "[" + k + "]" : "");
					sources.put(at, port);
					Object datapoint = binding.tValueByName("datapoint");
					String datapointId = datapoint instanceof TLObject dp && dp.tValid() ? ids.apply(dp) : null;
					if (datapointId == null) {
						Object was = binding.tValueByName("boundId");
						problems.add(new Problem(at, message(I18NConstants.BLOCK_BINDING_DELETED__BLOCK_PORT_DATAPOINT
							.fill(name + " (" + id + ")", decl.id(), was == null ? "?" : was))));
						continue;
					}
					names.add(datapointId);
				}
				if (names.isEmpty()) {
					continue;
				}
				sources.put(path + "." + member + "." + decl.id(), port);
				if (decl.isInput()) {
					inputs.put(decl.id(), names.size() == 1 && bindings.size() == 1 ? names.get(0) : names);
					Object chosen = port.tValueByName("aggregation");
					if (names.size() > 1 && chosen instanceof String a && !a.isBlank()) {
						aggregation.put(decl.id(), a.trim());
						sources.put(path + ".aggregation." + decl.id(), port);
					}
				} else {
					outputs.put(decl.id(), names.get(0));
					if (names.size() > 1) {
						problems.add(new Problem(path + ".outputs." + decl.id(), message(
							I18NConstants.BLOCK_OUTPUT_SEVERAL__BLOCK_PORT.fill(name + " (" + id + ")", decl.id()))));
					}
				}
			}
			if (!inputs.isEmpty()) {
				block.put("inputs", inputs);
			}
			if (!aggregation.isEmpty()) {
				block.put("aggregation", aggregation);
			}
			if (!outputs.isEmpty()) {
				block.put("outputs", outputs);
			}
			Map<String, Object> parameters = new LinkedHashMap<>();
			for (Param decl : m.params()) {
				TLObject parameter = parameter(instance, decl.id());
				if (parameter != null && parameter.tValueByName("value") instanceof Number value) {
					parameters.put(decl.id(), parameterJson(decl, value.doubleValue()));
					sources.put(path + ".parameters." + decl.id(), parameter);
				}
			}
			if (!parameters.isEmpty()) {
				block.put("parameters", parameters);
			}
		}
		return result;
	}

	/**
	 * A parameter value in its image form (<code>spec/blocks.md</code> 4): a boolean, a choice by
	 * its id, a whole number for an integer, a duration in ms, a time of day as an object, a real.
	 */
	public static Object parameterJson(Param decl, double value) {
		switch (decl.type()) {
			case BOOLEAN:
				return Boolean.valueOf(value != 0);
			case TIME_OF_DAY: {
				long s = (long) value;
				Map<String, Object> time = new LinkedHashMap<>();
				time.put("hour", Long.valueOf(s / 3600));
				time.put("minute", Long.valueOf(s % 3600 / 60));
				time.put("second", Long.valueOf(s % 60));
				return time;
			}
			case REAL:
				return Double.valueOf(value);
			default: {
				Choice choice = decl.choice(value);
				if (choice != null) {
					return choice.id();
				}
				return value == Math.floor(value) && Math.abs(value) < 1e15 ? (Object) Long.valueOf((long) value)
					: Double.valueOf(value);
			}
		}
	}

	/**
	 * The parameter's value for the user: a choice by its label, a duration in minutes or seconds, a
	 * time of day as <code>hh:mm:ss</code>, a boolean as yes/no.
	 */
	public static String display(Param decl, double value) {
		switch (decl.type()) {
			case BOOLEAN:
				return value != 0 ? "true" : "false";
			case TIME_OF_DAY: {
				long s = (long) value;
				return String.format("%02d:%02d:%02d", Long.valueOf(s / 3600), Long.valueOf(s % 3600 / 60),
					Long.valueOf(s % 60));
			}
			case DURATION:
				return format(value) + " ms (" + durationText(value) + ")";
			default: {
				Choice choice = decl.choice(value);
				String unit = decl.unit().isEmpty() ? "" : " " + decl.unit();
				return choice != null ? format(value) + " = " + choice.id() : format(value) + unit;
			}
		}
	}

	private static String durationText(double ms) {
		if (ms % 60000 == 0) {
			return format(ms / 60000) + " min";
		}
		return format(ms / 1000) + " s";
	}

	private static String message(ResKey key) {
		return com.top_logic.util.Resources.getInstance().getString(key);
	}

	static String format(double value) {
		return value == Math.floor(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
	}

	/**
	 * The range of the parameter for the user: its bounds or choices, and its default.
	 */
	public static String range(Param decl) {
		StringBuilder result = new StringBuilder();
		if (!decl.choices().isEmpty()) {
			for (Choice choice : decl.choices()) {
				if (result.length() > 0) {
					result.append(", ");
				}
				result.append(format(choice.value())).append(" = ").append(choice.id());
			}
		} else if (decl.type() == ParamType.BOOLEAN) {
			result.append("0, 1");
		} else if (decl.min() != null || decl.max() != null) {
			result.append(decl.min() == null ? "" : display(decl, decl.min().doubleValue())).append(" .. ")
				.append(decl.max() == null ? "" : display(decl, decl.max().doubleValue()));
		}
		return result.toString();
	}

	/** The aggregations a port allows (its default first). */
	public static List<String> aggregations(TLObject port) {
		Port decl = portDecl(port);
		List<String> result = new ArrayList<>();
		if (decl != null) {
			for (Aggregation a : decl.aggregations()) {
				result.add(a.externalName());
			}
		}
		return result;
	}

}
