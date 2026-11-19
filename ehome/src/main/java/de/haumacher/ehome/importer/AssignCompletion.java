/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.w3c.dom.Element;

import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.core.Externalized;
import de.haumacher.ehome.model.operational.Assign;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.ModuleDefinition;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;

/**
 * Adds the assignments (ETS: <code>Assign</code>, haumacher/ehome #142) to an application program an
 * earlier import read without them (<code>assignsRead</code> not set), from the program's file.
 *
 * <p>
 * The structure of the program in the database is the structure of the file without its
 * <code>Assign</code> elements: the completion walks both in parallel (the dynamic part of the program
 * and those of its module definitions) and inserts an assignment for each <code>Assign</code> at its
 * position among the structure elements of its container. Elements of the file without a structure
 * element (<code>ParameterSeparator</code>) and the renames (haumacher/ehome #122) on both sides do not
 * count; <code>Module</code> and <code>Repeat</code> count only if the program's modules were read. The
 * parts with an ID are checked against the file; if the two do not fit, nothing is added
 * ({@link ModuleCompletion.Mismatch}).
 * </p>
 */
final class AssignCompletion {

	private static final String TYPE_PREFIX = "ehome.operational:";

	private static final String ASSIGN = "Assign";

	/** An assignment to add: its element and where it goes. */
	private record Insertion(Element xml, TLObject fragment, int index) {
		// Plain data.
	}

	private final TLObject _program;

	private final boolean _modules;

	private final Map<String, ParameterView> _views = new HashMap<>();

	private final Map<String, TLObject> _definitions = new HashMap<>();

	private final List<Insertion> _insertions = new ArrayList<>();

	private AssignCompletion(Program program) {
		_program = program;
		_modules = !Boolean.TRUE.equals(program.getUsesModulesOrNull());
		collect(program.getParameters(), program.getModules());
	}

	private void collect(Collection<? extends Parameter> parameters, Collection<? extends ModuleDefinition> modules) {
		for (Parameter parameter : parameters) {
			for (ParameterView view : parameter.getViews()) {
				_views.put(view.getExtId(), view);
			}
		}
		for (ModuleDefinition definition : modules) {
			_definitions.put(definition.getExtId(), definition);
			collect(definition.getParameters(), definition.getModules());
		}
	}

	/**
	 * Adds the assignments of the given element of the file (<code>ApplicationProgram</code>) to the
	 * given program.
	 *
	 * @return The number of assignments added.
	 * @throws ModuleCompletion.Mismatch
	 *         If the structure of the program does not fit the file; nothing is added then.
	 */
	static int complete(Element xml, Program program) throws ModuleCompletion.Mismatch {
		AssignCompletion completion = new AssignCompletion(program);
		Element dynamic = ModuleScan.child(xml, "Dynamic");
		if (dynamic != null) {
			completion.walk(dynamic, program);
		}
		if (completion._modules) {
			completion.walkDefinitions(ModuleScan.child(xml, "ModuleDefs"));
		}
		return completion.apply();
	}

	private void walkDefinitions(Element definitions) throws ModuleCompletion.Mismatch {
		if (definitions == null) {
			return;
		}
		for (Element definition : ModuleScan.children(definitions, "ModuleDef")) {
			TLObject stored = _definitions.get(definition.getAttribute("Id"));
			if (stored == null) {
				throw new ModuleCompletion.Mismatch("Missing module definition " + definition.getAttribute("Id") + ".");
			}
			Element dynamic = ModuleScan.child(definition, "Dynamic");
			if (dynamic != null) {
				walk(dynamic, stored);
			}
			walkDefinitions(ModuleScan.child(definition, "SubModuleDefs"));
		}
	}

	private void walk(Element xml, TLObject fragment) throws ModuleCompletion.Mismatch {
		List<TLObject> contents = fragment instanceof ConfigFragment container ? list(container.getContents()) : list(null);
		int index = 0;
		for (Element child : ModuleScan.childElements(xml)) {
			String name = child.getLocalName();
			if (ASSIGN.equals(name)) {
				_insertions.add(new Insertion(child, fragment, index));
				continue;
			}
			String type = typeOf(name);
			if (type == null) {
				// Creates no structure element.
				continue;
			}
			index = skipUncounted(contents, index);
			if (index >= contents.size()) {
				throw new ModuleCompletion.Mismatch("Missing structure element for " + name + ".");
			}
			TLObject option = contents.get(index++);
			switch (name) {
				case "ParameterRefRef":
				case "ComObjectRefRef":
					expectPlaced(option, child.getAttribute("RefId"));
					break;
				case "choose":
					expect(option, type, null);
					List<Element> cases = ModuleScan.children(child, "when");
					List<TLObject> alternatives = list(((Switch) option).getAlternatives());
					if (cases.size() != alternatives.size()) {
						throw new ModuleCompletion.Mismatch("Cases of a switch differ.");
					}
					for (int n = 0; n < cases.size(); n++) {
						walk(cases.get(n), alternatives.get(n));
					}
					break;
				case "Module":
					expect(option, type, child.getAttribute("Id"));
					break;
				default:
					expect(option, type, child.getAttribute("Id"));
					walk(child, option);
					break;
			}
		}
		if (skipUncounted(contents, index) != contents.size()) {
			throw new ModuleCompletion.Mismatch("Further structure elements in the database.");
		}
	}

	/**
	 * The type of the structure element the element of the given name creates, <code>null</code> for
	 * none.
	 */
	private String typeOf(String name) {
		switch (name) {
			case "ParameterBlock":
				return "ParameterGroup";
			case "ChannelIndependentBlock":
				return "ChannelIndependent";
			case "Channel":
				return "Channel";
			case "choose":
				return "Switch";
			case "ParameterRefRef":
			case "ComObjectRefRef":
				return "";
			case "Module":
				return _modules ? "ModulePlacement" : null;
			case "Repeat":
				return _modules ? "Repeat" : null;
			default:
				return null;
		}
	}

	/**
	 * The first position from the given one whose element the walk counts (no rename, no assignment, no
	 * button).
	 */
	private static int skipUncounted(List<TLObject> contents, int index) {
		int result = index;
		while (result < contents.size() && isUncounted(contents.get(result))) {
			result++;
		}
		return result;
	}

	private static boolean isUncounted(TLObject option) {
		String type = TLModelUtil.qualifiedName(option.tType());
		return (TYPE_PREFIX + "Rename").equals(type) || (TYPE_PREFIX + ASSIGN).equals(type)
			|| (TYPE_PREFIX + "Button").equals(type);
	}

	private int apply() throws ModuleCompletion.Mismatch {
		List<Object[]> created = new ArrayList<>();
		for (Insertion insertion : _insertions) {
			ParameterView target = view(insertion.xml().getAttribute("TargetParamRefRef"));
			String sourceId = insertion.xml().getAttribute("SourceParamRefRef");
			ParameterView source = sourceId.isEmpty() ? null : view(sourceId);
			created.add(new Object[] { insertion, target, source });
		}
		Map<TLObject, Integer> inserted = new HashMap<>();
		TLClass type = (TLClass) TLModelUtil.findType(TYPE_PREFIX + ASSIGN);
		for (Object[] entry : created) {
			Insertion insertion = (Insertion) entry[0];
			Assign assign = (Assign) ModelService.getInstance().getFactory().createObject(type);
			assign.setParameter((ParameterView) entry[1]);
			assign.setSource((ParameterView) entry[2]);
			String value = insertion.xml().getAttribute("Value");
			if (entry[2] == null && !value.isEmpty()) {
				ParameterValues.decode(assign, value);
			}
			TLObject fragment = insertion.fragment();
			int offset = inserted.getOrDefault(fragment, Integer.valueOf(0)).intValue();
			ConfigFragment container = (ConfigFragment) fragment;
			List<Option> contents = new ArrayList<>(container.getContents());
			contents.add(Math.min(insertion.index() + offset, contents.size()), assign);
			container.setContents(contents);
			inserted.put(fragment, Integer.valueOf(offset + 1));
		}
		return created.size();
	}

	private ParameterView view(String id) throws ModuleCompletion.Mismatch {
		ParameterView result = _views.get(id);
		if (result == null) {
			throw new ModuleCompletion.Mismatch("Unknown parameter reference " + id + ".");
		}
		return result;
	}

	private static void expect(TLObject option, String type, String id) throws ModuleCompletion.Mismatch {
		if (!(TYPE_PREFIX + type).equals(TLModelUtil.qualifiedName(option.tType()))) {
			throw new ModuleCompletion.Mismatch(
				"Expected " + type + ", found " + TLModelUtil.qualifiedName(option.tType()) + ".");
		}
		if (id != null && !id.isEmpty() && !id.equals(((Externalized) option).getExtId())) {
			throw new ModuleCompletion.Mismatch("Expected " + id + ", found " + ((Externalized) option).getExtId() + ".");
		}
	}

	private static void expectPlaced(TLObject option, String refId) throws ModuleCompletion.Mismatch {
		TLObject placed = option;
		if ((TYPE_PREFIX + "SharedPlacement").equals(TLModelUtil.qualifiedName(option.tType()))) {
			placed = ((SharedPlacement) option).getPlaced();
		}
		if (placed == null || !Objects.equals(refId, ((Externalized) placed).getExtId())) {
			throw new ModuleCompletion.Mismatch("Expected the placement of " + refId + ".");
		}
	}

	private static List<TLObject> list(Collection<? extends TLObject> value) {
		return value == null ? new ArrayList<>() : new ArrayList<>(value);
	}

}
