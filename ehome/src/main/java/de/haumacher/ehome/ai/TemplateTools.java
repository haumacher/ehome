/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import static de.haumacher.ehome.ai.ToolSupport.answer;
import static de.haumacher.ehome.ai.ToolSupport.failure;
import static de.haumacher.ehome.ai.ToolSupport.id;
import static de.haumacher.ehome.ai.ToolSupport.label;
import static de.haumacher.ehome.ai.ToolSupport.project;
import static de.haumacher.ehome.ai.ToolSupport.ref;
import static de.haumacher.ehome.ai.ToolSupport.refused;
import static de.haumacher.ehome.ai.ToolSupport.saved;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tl.ai.core.agent.ToolContext;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.catalog.ProgramAssignment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.template.Preset;
import de.haumacher.ehome.model.template.PresetObject;
import de.haumacher.ehome.model.template.PresetValue;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceTexts;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.ObjectKinds;
import de.haumacher.ehome.template.Presets;

/**
 * The assistant's tools for the device templates (haumacher/ehome #174, page "Device templates",
 * <code>device-templates.view.xml</code>): the application programs with their templates, the kinds
 * of their communication objects with the datapoint types the devices inherit, typing a kind, and
 * the presets of a template, saved from a channel of a device and deleted.
 *
 * <p>
 * <b>Kinds.</b> The tools speak in the kinds of objects of {@link ObjectKinds} (haumacher/ehome
 * #166), as the tab "Communication objects" lists them, with their texts in the program's base
 * language ({@link ObjectKinds#baseLanguage(TLObject)}), in which the kinds are compared. A kind has
 * no persistent identity: its ID is the identifier of its first communication object (placement), and
 * a tool takes the identifier of any of its placements back for it - every placement belongs to
 * exactly one kind, whatever the reader's language or the project.
 * </p>
 *
 * <p>
 * <b>Cost.</b> Without a project, no tool reads project data: the kinds are computed from the
 * program alone. With a project, the marks of the objects a device connects without a datapoint type
 * are computed from that project's devices only ({@link DeviceTemplates#untypedConnected(TLObject, TLObject)}).
 * </p>
 *
 * <p>
 * <b>Rights.</b> The templates and presets are master data: every account reads them, the E-Home
 * administrators write them (haumacher/ehome #165). The writing tools check what the commands of the
 * page check ({@link Presets#templateWriteVeto()}, <code>Write</code> on the template, <code>Delete</code>
 * on a preset) and answer a refusal with the application's reason. No tool declares a type of project
 * data.
 * </p>
 */
public class TemplateTools {

	private static final String CATALOG_ROOT = "ehome.catalog:ManufacturerRoot";

	private static final String TEMPLATE_ROOT = "ehome.template:DeviceTemplates";

	private static final String DEVICE = "ehome.physical:Device";

	private static final String PRESET = "ehome.template:Preset";

	/** How many programs a search answers at most by default. */
	private static final int DEFAULT_LIMIT = 20;

	/** How many kinds one answer of {@link #objectKinds} lists. */
	private static final int KIND_PAGE = 80;

	/**
	 * Searches the application programs and tells which have a template.
	 */
	@ToolMethod(description = "Searches the application programs of E-Home's catalog for their device templates"
		+ " (page Device templates): a template is E-Home's own repair of an imported program - datapoint types"
		+ " for its communication objects and presets of its channels - and applies to every device running"
		+ " the program, in every project. Every word of 'text' must occur in the manufacturer, the program's"
		+ " name, its version or its ETS id (e.g. \"Gira Tastsensor\", \"MDT AKK\"). Each program comes with its"
		+ " products and whether it has a template.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = CATALOG_ROOT)
	public ToolResult programTemplates(
			@P("Words to search for; empty lists the first programs") String text,
			@P(value = "true for the programs with a template only", required = false) Boolean withTemplate,
			@P(value = "How many programs to answer at most, default " + DEFAULT_LIMIT, required = false) Integer limit) {
		List<String> words = new ArrayList<>();
		for (String word : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("\\s+")) {
			if (!word.isBlank()) {
				words.add(word);
			}
		}
		int max = limit == null || limit.intValue() <= 0 ? DEFAULT_LIMIT : limit.intValue();
		boolean templatesOnly = Boolean.TRUE.equals(withTemplate);
		List<Object> programs = new ArrayList<>();
		int found = 0;
		for (TLObject manufacturer : ExtIds.list(catalogRoot(), "manufacturers")) {
			String manufacturerName = label(manufacturer);
			for (TLObject program : ExtIds.list(manufacturer, "programs")) {
				String haystack = (Objects.toString(manufacturerName, "") + " " + Objects.toString(label(program), "")
					+ " " + Objects.toString(version(program), "") + " " + Objects.toString(ExtIds.extId(program), ""))
					.toLowerCase(Locale.ROOT);
				if (!words.stream().allMatch(haystack::contains)) {
					continue;
				}
				TLObject template = DeviceTemplates.template(program);
				if (templatesOnly && template == null) {
					continue;
				}
				found++;
				if (programs.size() >= max) {
					continue;
				}
				Map<String, Object> entry = programEntry((Program) program);
				entry.put("manufacturer", manufacturerName);
				List<String> products = new ArrayList<>();
				for (TLObject product : DeviceTemplates.products(program)) {
					products.add(label(product));
				}
				entry.put("products", products);
				entry.put("template", Boolean.valueOf(template != null));
				if (template != null) {
					entry.put("presets", Integer.valueOf(ExtIds.list(template, "presets").size()));
				}
				programs.add(entry);
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("found", Integer.valueOf(found));
		result.put("programs", programs);
		String sentence = found == 0 ? "No application program matches."
			: found > programs.size()
				? "The first " + programs.size() + " of " + found + " programs; narrow the search for others:"
				: "Programs:";
		return answer(sentence, result);
	}

	/**
	 * The kinds of communication objects of a program.
	 */
	@ToolMethod(description = "Lists the kinds of communication objects of an application program, as the tab"
		+ " Communication objects of its device template does: a kind is what the objects placed in the"
		+ " program's channels share - the object's text relative to its channel and its function text"
		+ " ('object'), its size in bits and the setting within the channel that switches it on ('setting',"
		+ " e.g. \"Funktion: Wertgeber 2Byte › Funktionsweise: Temperaturwertgeber\"; empty: always) - with"
		+ " the channels it occurs in. Texts are in the program's base language. Each kind has the"
		+ " datapoint type its devices inherit ('datapointType': the template's, else the manufacturer's;"
		+ " null: none, E-Home cannot tell the meaning of an object larger than 4 bits; 'mixed': its objects"
		+ " have different types) and 'templateType', the type the template gives it. The 'id' of a kind is"
		+ " the identifier of its first object; setObjectKindType takes it back. With 'project' (a project"
		+ " of the user or any object of it), 'untyped' marks the kinds a device of that project connects to"
		+ " a group address without a datapoint type - the work list - and lists them first; only that"
		+ " project is read. Page with 'offset' while the answer gives 'nextOffset'.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = CATALOG_ROOT)
	public ToolResult objectKinds(
			@P("The application program (from programTemplates, or a program id of searchCatalog), its template,"
				+ " or a device running it") TLObject program,
			@P(value = "A project whose devices' use of the program to mark; omit to read no project",
				required = false) TLObject project,
			@P(value = "true for the kinds the project's devices connect without a datapoint type only (needs"
				+ " 'project')", required = false) Boolean untypedOnly,
			@P(value = "How many kinds to skip, from 'nextOffset' of the previous answer; default 0",
				required = false) Integer offset) {
		Program resolved = program(program);
		if (resolved == null) {
			return failure("'" + id(program) + "' is no application program, template or device running one.");
		}
		TLObject owner = null;
		if (project != null) {
			owner = project(project);
			if (owner == null) {
				return failure("'" + id(project) + "' belongs to no project.");
			}
		}
		boolean onlyUntyped = Boolean.TRUE.equals(untypedOnly);
		if (onlyUntyped && owner == null) {
			return failure("'untypedOnly' needs a project: which objects are connected without a type is a"
				+ " question about the devices of one project.");
		}
		TLObject template = DeviceTemplates.template(resolved);
		List<Map<String, Object>> kinds = ObjectKinds.kinds(resolved, owner, ObjectKinds.baseLanguage(resolved));
		List<Object> entries = new ArrayList<>();
		int skip = offset == null || offset.intValue() < 0 ? 0 : offset.intValue();
		int matching = 0;
		Integer next = null;
		for (Map<String, Object> kind : kinds) {
			Boolean untyped = owner == null ? null : Boolean.valueOf(ObjectKinds.untyped(kind, owner));
			if (onlyUntyped && !untyped.booleanValue()) {
				continue;
			}
			matching++;
			if (matching <= skip) {
				continue;
			}
			if (entries.size() >= KIND_PAGE) {
				if (next == null) {
					next = Integer.valueOf(matching - 1);
				}
				continue;
			}
			Map<String, Object> entry = kind(template, kind);
			if (untyped != null) {
				entry.put("untyped", untyped);
			}
			entries.add(entry);
		}
		Map<String, Object> result = programEntry(resolved);
		result.put("template", template == null ? null : id(template));
		if (owner != null) {
			result.put("project", ref(owner));
		}
		result.put("kinds", Integer.valueOf(matching));
		result.put("objects", Integer.valueOf(DeviceTemplates.placements(resolved).size()));
		result.put("list", entries);
		if (next != null) {
			result.put("nextOffset", next);
		}
		String sentence;
		if (matching == 0) {
			sentence = onlyUntyped ? "No device of the project connects an object of the program without a datapoint type."
				: "The program has no communication objects.";
		} else {
			sentence = (next != null ? "Kinds " + (skip + 1) + " to " + (skip + entries.size()) + " of " + matching + ":"
				: "The kinds of the program's communication objects:")
				+ (template == null ? " The program has no template yet; setObjectKindType creates it." : "");
		}
		return answer(sentence, result);
	}

	private static Map<String, Object> kind(TLObject template, Map<String, Object> kind) {
		List<Interface> placements = ObjectKinds.placements(kind);
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("id", id(placements.get(0)));
		entry.put("object", kind.get(ObjectKinds.OBJECT));
		entry.put("size", kind.get(ObjectKinds.SIZE));
		entry.put("setting", kind.get(ObjectKinds.SETTING));
		entry.put("channels", kind.get(ObjectKinds.CHANNELS));
		entry.put("objects", Integer.valueOf(placements.size()));
		List<TLObject> types = ObjectKinds.types(kind);
		if (types.size() > 1) {
			entry.put("datapointType", null);
			entry.put("mixed", types(types));
		} else {
			entry.put("datapointType", types.isEmpty() ? null : type(types.get(0)));
		}
		TLObject own = ObjectKinds.type(template, kind);
		entry.put("templateType", own == null ? null : type(own));
		if (ObjectKinds.mixed(template, kind)) {
			entry.put("templateMixed", Boolean.TRUE);
		}
		return entry;
	}

	/**
	 * The commit message of {@link #setObjectKindType(ToolContext, TLObject, TLObject, String)}.
	 */
	@CalledByReflection
	ResKey setObjectKindTypeMessage(ToolContext context, TLObject program, TLObject kind, String datapointType) {
		Program resolved = program(program);
		String kindName = kind instanceof Interface placed && placed.tValid()
			? GroupAddressConnections.objectName(placed) : CommitMessages.label(kind);
		return datapointType == null || datapointType.isBlank()
			? I18NConstants.COMMIT_KIND_TYPE_REMOVED__PROGRAM_KIND.fill(CommitMessages.label(resolved), kindName)
			: I18NConstants.COMMIT_KIND_TYPE_SET__PROGRAM_KIND_TYPE.fill(CommitMessages.label(resolved), kindName,
				datapointType.trim());
	}

	/**
	 * Gives a kind of communication objects a datapoint type in the template of its program.
	 */
	@ToolMethod(description = "Gives a kind of communication objects of an application program (objectKinds) a"
		+ " datapoint type in the program's device template, as the field below the table of the tab"
		+ " Communication objects does: every object of the kind gets it, in every device running the"
		+ " program, in every project (a type entered at a device in a project still wins); the other kinds"
		+ " stay as they are. Type at the template what the program means for every device (the"
		+ " Temperaturwert of a key set to Temperaturwertgeber is 9.001 everywhere); a connector whose"
		+ " meaning depends on the installation is typed in the project. Omit 'datapointType' to remove the"
		+ " template's type of the kind: then the manufacturer's applies. The type must have the kind's"
		+ " size. A program without a template gets one. Only E-Home administrators write the device"
		+ " templates. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, commitMessage = "setObjectKindTypeMessage")
	public ToolResult setObjectKindType(ToolContext context,
			@P("The application program, its template, or a device running it") TLObject program,
			@P("The kind: its id from objectKinds (the identifier of one of its objects)") TLObject kind,
			@P(value = "The datapoint type, e.g. \"9.001\"; omit to remove the template's type", required = false)
			String datapointType) {
		Program resolved = program(program);
		if (resolved == null) {
			return failure("'" + id(program) + "' is no application program, template or device running one."
				+ " Nothing was changed.");
		}
		if (!(kind instanceof Interface placed) || DeviceTemplates.program(placed) != resolved) {
			return failure("'" + id(kind) + "' is no kind of the program " + label(resolved) + ": pass the 'id' of a"
				+ " kind from objectKinds of this program. Nothing was changed.");
		}
		Map<String, Object> row = null;
		for (Map<String, Object> candidate : ObjectKinds.kinds(resolved, null, ObjectKinds.baseLanguage(resolved))) {
			if (ObjectKinds.placements(candidate).contains(placed)) {
				row = candidate;
				break;
			}
		}
		if (row == null) {
			return failure("The object '" + id(kind) + "' is placed in no channel of the program. Nothing was changed.");
		}
		TLObject type = null;
		if (datapointType != null && !datapointType.isBlank()) {
			Dpt dpt = Dpt.parse(datapointType.trim());
			if (dpt != null) {
				for (TLObject candidate : GroupAddressTypes.allTypes()) {
					if (dpt.equals(GroupAddressTypes.dpt(candidate))) {
						type = candidate;
						break;
					}
				}
			}
			if (type == null) {
				return failure("The datapoint type '" + datapointType + "' is not in E-Home's master data. Nothing was"
					+ " changed.");
			}
			if (!ObjectKinds.typeChoices(row).contains(type)) {
				return failure("The datapoint type " + datapointType.trim() + " does not have the size of the kind ("
					+ row.get(ObjectKinds.SIZE) + " bits). Nothing was changed.");
			}
		}
		TLObject template = DeviceTemplates.template(resolved);
		ToolResult denied = templateWriteDenied(context, template == null ? DeviceTemplates.root() : template, false);
		if (denied != null) {
			return denied;
		}
		if (template == null && type == null) {
			Map<String, Object> result = kind(null, row);
			return answer("The program has no template, so the kind has no type of the template to remove. Nothing"
				+ " was changed.", result);
		}
		try {
			boolean created = template == null;
			if (created) {
				template = DeviceTemplates.create(resolved);
			}
			ObjectKinds.setType(template, row, type);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("program", programEntry(resolved));
			result.put("template", id(template));
			result.put("kind", kind(template, row));
			return answer((created ? "Template created and type set. " : type == null ? "Type removed. " : "Set. ")
				+ saved(context), result);
		} catch (TopLogicException ex) {
			return refused(template, ex.getErrorKey());
		}
	}

	/**
	 * The presets of a template.
	 */
	@ToolMethod(description = "Lists the presets of an application program's device template (tab Presets):"
		+ " named configurations of a channel, saved from a configured channel of a device, that every"
		+ " device of the program can take. Each with its channel, its purpose ('actuator' with the kind of"
		+ " function it makes the channel do, 'source' with the operations of a key, LED or sensor) and the"
		+ " numbers of its values and objects. Read one with preset.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = TEMPLATE_ROOT)
	public ToolResult presets(
			@P("The application program, its template, or a device running it") TLObject program) {
		Program resolved = program(program);
		if (resolved == null) {
			return failure("'" + id(program) + "' is no application program, template or device running one.");
		}
		TLObject template = DeviceTemplates.template(resolved);
		Map<String, Object> result = programEntry(resolved);
		result.put("template", template == null ? null : id(template));
		List<Object> presets = new ArrayList<>();
		if (template != null) {
			for (TLObject preset : ExtIds.list(template, "presets")) {
				Map<String, Object> entry = presetHeader(preset);
				entry.put("values", Integer.valueOf(ExtIds.list(preset, "values").size()));
				entry.put("objects", Integer.valueOf(ExtIds.list(preset, "objects").size()));
				presets.add(entry);
			}
		}
		result.put("presets", presets);
		return answer(template == null ? "The program has no template and therefore no presets."
			: presets.isEmpty() ? "The template has no presets." : "The presets of the template:", result);
	}

	/**
	 * A preset with its values and objects.
	 */
	@ToolMethod(description = "Reads a preset of a device template (presets): its channel, purpose, kind or"
		+ " operations, the parameter values it sets (parameter id and title, value) and the communication"
		+ " objects of the channel with their roles.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ, types = TEMPLATE_ROOT)
	public ToolResult preset(
			@P("The preset from presets") TLObject preset) {
		if (!ExtIds.isA(preset, PRESET)) {
			return failure("'" + id(preset) + "' is no preset.");
		}
		Map<String, Object> result = presetHeader(preset);
		result.put("program", programEntry(Presets.program(preset.tContainer())));
		List<Object> values = new ArrayList<>();
		for (TLObject value : ExtIds.list(preset, "values")) {
			Map<String, Object> entry = new LinkedHashMap<>();
			if (((PresetValue) value).getParameter() instanceof ParameterView view) {
				entry.put("parameter", ModuleParts.partId(view));
				entry.put("title", ChannelShape.parameterLabel(view));
				entry.put("value", DeviceTools.display(ParameterValues.valueType(view), ParameterValues.value(value)));
			}
			values.add(entry);
		}
		result.put("values", values);
		List<Object> objects = new ArrayList<>();
		for (TLObject object : ExtIds.list(preset, "objects")) {
			Map<String, Object> entry = new LinkedHashMap<>();
			if (((PresetObject) object).getPlacement() instanceof Interface placed) {
				entry.put("object", ChannelShape.objectLabel(null, placed));
			}
			entry.put("role", name(((PresetObject) object).getRole()));
			objects.add(entry);
		}
		result.put("objects", objects);
		return answer("The preset:", result);
	}

	private static Map<String, Object> presetHeader(TLObject preset) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("id", id(preset));
		Preset typed = (Preset) preset;
		entry.put("name", typed.getName());
		entry.put("channel", ToolSupport.named(typed.getChannel(), Presets.presetChannelLabel(preset)));
		entry.put("purpose", name(typed.getPurpose()));
		if (Presets.isSourcePreset(preset)) {
			entry.put("operations", Presets.operations(preset));
		} else {
			entry.put("kind", name(typed.getKind()));
		}
		return entry;
	}

	/**
	 * What a preset of a channel of a device would hold.
	 */
	@ToolMethod(description = "Prepares saving a channel of a device as a preset of its program's template, as"
		+ " the dialog Save as preset (tab Presets of a device) does. Without 'channel': the channels of the"
		+ " device that matter (objects in effect, a preset offered or applied) with their keys. With"
		+ " 'channel': the parameter values the preset may hold (id, title, value; 'proposed' are those the"
		+ " dialog selects: the channel's values differing from their default and the inputs that switch its"
		+ " objects and parameters on), the communication objects of the channel in effect (id, label) and"
		+ " the purposes with the roles their objects may take. savePreset takes these ids.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult presetCandidates(
			@P("The device, configured as the preset should be") TLObject device,
			@P(value = "The key of the channel; omit for the list of channels", required = false) String channel) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device.");
		}
		Program program = DeviceConfiguration.program(device);
		if (program == null) {
			return failure("The device " + GroupAddressConnections.deviceLabel(device)
				+ " runs no application program of the catalog.");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("device", ref(device, GroupAddressConnections.deviceLabel(device)));
		result.put("program", programEntry(program));
		if (channel == null || channel.isBlank()) {
			List<Object> channels = new ArrayList<>();
			for (String key : Presets.activeChannels(device)) {
				channels.add(ToolSupport.named(key, Presets.channelLabel(device, key)));
			}
			result.put("channels", channels);
			return answer(channels.isEmpty() ? "The device has no channel with objects in effect." : "The channels:",
				result);
		}
		String key = channel.trim();
		if (!Presets.channels(device).contains(key)) {
			return failure("The device has no channel '" + key + "'. Call presetCandidates without 'channel' for the"
				+ " keys.");
		}
		result.put("channel", ToolSupport.named(key, Presets.channelLabel(device, key)));
		List<Object> values = new ArrayList<>();
		for (Presets.Candidate candidate : Presets.candidates(device, key)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", ModuleParts.partId(candidate.view()));
			entry.put("title", DeviceTexts.label(device, candidate.view()));
			entry.put("value", DeviceTools.display(ParameterValues.valueType(candidate.view()), candidate.value()));
			entry.put("inChannel", Boolean.valueOf(candidate.inChannel()));
			entry.put("notDefault", Boolean.valueOf(candidate.differs()));
			entry.put("proposed", Boolean.valueOf(candidate.proposed()));
			values.add(entry);
		}
		result.put("values", values);
		List<Object> objects = new ArrayList<>();
		for (Interface placed : Presets.channelObjects(device, key)) {
			objects.add(ToolSupport.named(objectId(placed), ChannelShape.objectLabel(device, placed)));
		}
		result.put("objects", objects);
		Map<String, Object> purposes = new LinkedHashMap<>();
		Map<String, Object> actuator = new LinkedHashMap<>();
		for (TLClassifier kind : classifiers("ehome.runtime:FunctionKind")) {
			actuator.put(kind.getName(), FunctionKinds.roles(kind.getName()));
		}
		purposes.put("actuator", Map.of("rolesByKind", actuator));
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("operations", classifiers("ehome.template:SourceOperation").stream().map(TLClassifier::getName).toList());
		source.put("roles", Presets.SOURCE_ROLES);
		purposes.put("source", source);
		result.put("purposes", purposes);
		return answer("What a preset of the channel may hold:", result);
	}

	/**
	 * The commit message of {@link #savePreset}.
	 */
	@CalledByReflection
	ResKey savePresetMessage(ToolContext context, TLObject device, String channel, String name, String purpose,
			String kind, List<String> operations, List<String> values, List<Map<String, Object>> objects) {
		Program program = ExtIds.isA(device, DEVICE) && device.tValid() ? DeviceConfiguration.program(device) : null;
		return I18NConstants.COMMIT_PRESET_SAVED__PROGRAM_NAME.fill(CommitMessages.label(program),
			CommitMessages.text(name));
	}

	/**
	 * Saves a channel of a device as a preset.
	 */
	@ToolMethod(description = "Saves a channel of a device as a preset of the device template of its program,"
		+ " as the dialog Save as preset does (the template is created where the program has none): the"
		+ " current values of the device for the parameters named in 'values' (ids from presetCandidates;"
		+ " omit for the proposed ones), a name unique on the channel, the purpose - 'actuator' with the"
		+ " 'kind' of function (light, dimmer, blind, switch, sensor, climate, scene, button) or 'source' with"
		+ " its 'operations' (toggle, on, off, onOff, showStatus, dim) - and the roles of the channel's"
		+ " objects (an object without a role is not part of the preset). Every device of the program can"
		+ " then take it. Only E-Home administrators write the device templates. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, commitMessage = "savePresetMessage")
	public ToolResult savePreset(ToolContext context,
			@P("The device, configured as the preset should be") TLObject device,
			@P("The key of the channel from presetCandidates") String channel,
			@P("The name of the preset, e.g. \"Schalten\", \"Taste: Umschalten\"") String name,
			@P("'actuator' or 'source'") String purpose,
			@P(value = "The kind of function of an actuator preset", required = false) String kind,
			@P(value = "The operations of a source preset", required = false) List<String> operations,
			@P(value = "The ids of the parameter values the preset holds; omit for the proposed ones",
				required = false) List<String> values,
			@P(value = "The roles of objects of the channel, each {\"object\": id from presetCandidates, \"role\":"
				+ " \"command\"}", required = false) List<Map<String, Object>> objects) {
		if (!ExtIds.isA(device, DEVICE)) {
			return failure("'" + id(device) + "' is no device. Nothing was saved.");
		}
		String key = channel == null ? "" : channel.trim();
		if (!Presets.channels(device).contains(key)) {
			return failure("The device has no channel '" + key + "'. Call presetCandidates for the keys. Nothing was"
				+ " saved.");
		}
		TLClassifier purposeValue = classifier("ehome.template:PresetPurpose", purpose);
		if (purposeValue == null) {
			return failure("The purpose must be 'actuator' or 'source'. Nothing was saved.");
		}
		TLClassifier kindValue = null;
		if (kind != null && !kind.isBlank()) {
			kindValue = classifier("ehome.runtime:FunctionKind", kind);
			if (kindValue == null) {
				return failure("There is no kind of function '" + kind + "'. Nothing was saved.");
			}
		}
		List<TLClassifier> operationValues = new ArrayList<>();
		for (String operation : operations == null ? List.<String> of() : operations) {
			TLClassifier value = classifier("ehome.template:SourceOperation", operation);
			if (value == null) {
				return failure("There is no operation '" + operation + "'. Nothing was saved.");
			}
			operationValues.add(value);
		}
		List<Presets.Candidate> candidates = Presets.candidates(device, key);
		List<ParameterView> views = new ArrayList<>();
		if (values == null) {
			for (Presets.Candidate candidate : candidates) {
				if (candidate.proposed()) {
					views.add(candidate.view());
				}
			}
		} else {
			for (String value : values) {
				ParameterView found = null;
				for (Presets.Candidate candidate : candidates) {
					if (Objects.equals(value == null ? null : value.trim(), ModuleParts.partId(candidate.view()))) {
						found = candidate.view();
						break;
					}
				}
				if (found == null) {
					return failure("The channel offers no parameter value '" + value + "' for a preset. Read"
						+ " presetCandidates for the ids. Nothing was saved.");
				}
				views.add(found);
			}
		}
		List<Interface> channelObjects = Presets.channelObjects(device, key);
		Map<Interface, TLObject> roles = new LinkedHashMap<>();
		for (Map<String, Object> object : objects == null ? List.<Map<String, Object>> of() : objects) {
			String objectId = Objects.toString(object.get("object"), "").trim();
			Interface found = null;
			for (Interface placed : channelObjects) {
				if (objectId.equals(objectId(placed))) {
					found = placed;
					break;
				}
			}
			if (found == null) {
				return failure("The channel has no object '" + objectId + "' in effect. Read presetCandidates for the"
					+ " ids. Nothing was saved.");
			}
			TLClassifier role = classifier("ehome.template:PresetRole", Objects.toString(object.get("role"), null));
			if (role == null) {
				return failure("There is no role '" + object.get("role") + "'. Nothing was saved.");
			}
			roles.put(found, role);
		}
		ToolResult denied = templateWriteDenied(context, DeviceTemplates.root(), false);
		if (denied != null) {
			return denied;
		}
		try {
			TLObject preset = Presets.save(device, key, name, purposeValue, kindValue, operationValues, views, roles);
			Map<String, Object> result = presetHeader(preset);
			result.put("values", Integer.valueOf(ExtIds.list(preset, "values").size()));
			result.put("objects", Integer.valueOf(ExtIds.list(preset, "objects").size()));
			return answer("Saved. " + saved(context), result);
		} catch (TopLogicException ex) {
			return refused(device, ex.getErrorKey());
		}
	}

	/**
	 * The commit message of {@link #deletePreset(ToolContext, TLObject)}.
	 */
	@CalledByReflection
	ResKey deletePresetMessage(ToolContext context, TLObject preset) {
		String name = ExtIds.isA(preset, PRESET) && preset.tValid()
			? CommitMessages.text(((Preset) preset).getName())
			: CommitMessages.label(preset);
		return I18NConstants.COMMIT_PRESET_DELETED__PRESET.fill(name);
	}

	/**
	 * Deletes a preset.
	 */
	@ToolMethod(description = "Deletes a preset of a device template, as the command Delete preset of the tab"
		+ " Presets does. The devices it was applied to keep its values. Only E-Home administrators write the"
		+ " device templates. " + ToolSupport.REFUSALS,
		mode = ToolMode.DATA_WRITE, group = ToolGroups.DATA_WRITE, commitMessage = "deletePresetMessage")
	public ToolResult deletePreset(ToolContext context,
			@P("The preset from presets") TLObject preset) {
		if (!ExtIds.isA(preset, PRESET)) {
			return failure("'" + id(preset) + "' is no preset. Nothing was deleted.");
		}
		ToolResult denied = templateWriteDenied(context, preset, true);
		if (denied != null) {
			return denied;
		}
		Map<String, Object> result = presetHeader(preset);
		preset.tDelete();
		return answer("Deleted. " + saved(context), result);
	}

	/**
	 * Why the run may not write the device templates, <code>null</code> if it may: the checks of the
	 * page's commands - who writes the templates ({@link Presets#templateWriteVeto()}), and the right
	 * on the object the command names (<code>Write</code>, <code>Delete</code> for a preset).
	 */
	private static ToolResult templateWriteDenied(ToolContext context, TLObject target, boolean delete) {
		ToolResult system = ToolSupport.withoutUser();
		if (system != null) {
			return system;
		}
		ResKey veto = Presets.templateWriteVeto();
		if (veto == null && target != null
			&& !(delete ? context.modelSecurity().canDelete(target) : context.modelSecurity().canWrite(target))) {
			veto = de.haumacher.ehome.template.I18NConstants.ERROR_TEMPLATES_NOT_ALLOWED;
		}
		return veto == null ? null : refused(target, veto);
	}

	/**
	 * The application program the given object names: a program, a program assignment of a product
	 * (searchCatalog), a template, a device running one; <code>null</code> for anything else.
	 */
	private static Program program(TLObject obj) {
		if (obj instanceof Program program) {
			return program;
		}
		if (ExtIds.isA(obj, "ehome.catalog:ProgramAssignment") && ((ProgramAssignment) obj).getProgram() instanceof Program program) {
			return program;
		}
		if (ExtIds.isA(obj, "ehome.template:DeviceTemplate")) {
			return Presets.program(obj);
		}
		if (ExtIds.isA(obj, DEVICE)) {
			return DeviceConfiguration.program(obj);
		}
		return null;
	}

	private static Map<String, Object> programEntry(Program program) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (program == null) {
			return result;
		}
		result.put("id", id(program));
		result.put("name", label(program));
		result.put("version", version(program));
		result.put("extId", program.getExtId());
		return result;
	}

	private static String version(TLObject program) {
		Integer version = program instanceof Program valid ? valid.getApplicationVersion() : null;
		return version == null ? null : (version >>> 4) + "." + (version & 0x0F);
	}

	/** The ID of a communication object of a channel in a device (of a module instance: its instance's). */
	private static String objectId(Interface placed) {
		String id = ModuleParts.partId(placed);
		return id != null ? id : Integer.toString(placed.getPort().getIndex());
	}

	private static Map<String, Object> type(TLObject type) {
		Map<String, Object> result = new LinkedHashMap<>();
		Dpt dpt = GroupAddressTypes.dpt(type);
		result.put("code", dpt == null ? null : dpt.code());
		result.put("name", label(type));
		return result;
	}

	private static List<Object> types(Collection<TLObject> types) {
		List<Object> result = new ArrayList<>();
		for (TLObject type : types) {
			result.add(type(type));
		}
		return result;
	}

	private static String name(Object classifier) {
		return classifier instanceof TLClassifier value ? value.getName() : null;
	}

	private static List<TLClassifier> classifiers(String enumeration) {
		return new ArrayList<>(((TLEnumeration) TLModelUtil.findType(enumeration)).getClassifiers());
	}

	private static TLClassifier classifier(String enumeration, String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		for (TLClassifier classifier : classifiers(enumeration)) {
			if (classifier.getName().equals(name.trim())) {
				return classifier;
			}
		}
		return null;
	}

	private static TLObject catalogRoot() {
		return TLModelUtil.findType(CATALOG_ROOT).getModule().getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
	}

}
