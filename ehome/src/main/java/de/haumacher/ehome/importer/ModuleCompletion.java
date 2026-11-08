/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.w3c.dom.Element;

import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

/**
 * Where the modules of an application program go that an earlier import left without them
 * (haumacher/ehome #119): the program in the database is marked <code>usesModules</code> and lacks
 * its module definitions, its allocators and the placements of its modules; the file read now has
 * modules the import can read (see {@link ModuleScan}).
 *
 * <p>
 * The structure of the program in the database is the structure of the file without the
 * <code>&lt;Module&gt;</code> and <code>&lt;Repeat&gt;</code> elements, which the earlier import
 * skipped: the plan walks both in parallel and finds for each of these elements (not nested in
 * another one), in the order of the document, the structure element and the position in its contents
 * where its placement belongs. The parts with an ID are checked against the file; if the two do not
 * fit, the program is not completed.
 * </p>
 *
 * @see CompleteModules
 */
final class ModuleCompletion {

	private static final String CONTENTS = "contents";

	private static final String ALTERNATIVES = "alternatives";

	private static final String EXT_ID = "extId";

	private static final String TYPE_PREFIX = "ehome.operational:";

	/** The elements creating a structure element in the program or a case of a switch in it. */
	private static final Set<String> TOP_LEVEL = Set.of("ParameterBlock", "ChannelIndependentBlock", "Channel", "choose");

	/** The elements creating a structure element in a channel, block or a case of a switch in them. */
	private static final Set<String> CHANNEL_LEVEL = Set.of("ParameterBlock", "choose", "ParameterRefRef", "ComObjectRefRef");

	/**
	 * Where the structure of one placement of a module goes.
	 *
	 * @param fragment
	 *        The structure element (<code>ehome.operational:ConfigFragment</code>) receiving it.
	 * @param index
	 *        The position in the contents of the fragment before the completion.
	 * @param topLevel
	 *        Whether the fragment is the program or a case of a switch directly in it (its contents are
	 *        read as those of the dynamic part), not a channel or block.
	 */
	record Target(TLObject fragment, int index, boolean topLevel) {
		// Plain data.
	}

	/**
	 * The structure of the file and of the program do not fit.
	 */
	static final class Mismatch extends Exception {

		Mismatch(String message) {
			super(message, null, false, false);
		}

	}

	private final TLObject _program;

	private final List<Target> _targets = new ArrayList<>();

	private ModuleCompletion(TLObject program) {
		_program = program;
	}

	/**
	 * Plans the completion of the given program from the dynamic part of its file: the targets of the
	 * <code>Module</code> and <code>Repeat</code> elements (not nested in another one), in the order
	 * of the document ({@link #target(int)}).
	 */
	static ModuleCompletion plan(Element dynamic, TLObject program) throws Mismatch {
		ModuleCompletion result = new ModuleCompletion(program);
		if (dynamic != null) {
			result.walk(dynamic, program, true);
		}
		return result;
	}

	/**
	 * The program to complete.
	 */
	TLObject getProgram() {
		return _program;
	}

	/**
	 * The target of the <code>Module</code> or <code>Repeat</code> element with the given index in the
	 * order of the document, <code>null</code> if there is none.
	 */
	Target target(int sequence) {
		return sequence < _targets.size() ? _targets.get(sequence) : null;
	}

	/**
	 * Whether the element of the given name is a placement the earlier import skipped.
	 */
	static boolean isPlacement(String name) {
		return "Module".equals(name) || "Repeat".equals(name);
	}

	private void walk(Element xml, TLObject fragment, boolean topLevel) throws Mismatch {
		// A rename (haumacher/ehome #122) is no structure element the plan places anything relative to.
		List<TLObject> contents = list(fragment, CONTENTS).stream()
			.filter(option -> !(TYPE_PREFIX + "Rename").equals(TLModelUtil.qualifiedName(option.tType()))).toList();
		Set<String> options = topLevel ? TOP_LEVEL : CHANNEL_LEVEL;
		int index = 0;
		for (Element child : ModuleScan.childElements(xml)) {
			String name = child.getLocalName();
			if (isPlacement(name)) {
				_targets.add(new Target(fragment, index, topLevel));
				continue;
			}
			if (!options.contains(name)) {
				// Creates no structure element.
				continue;
			}
			if (index >= contents.size()) {
				throw new Mismatch("Missing structure element for " + name + ".");
			}
			TLObject option = contents.get(index++);
			switch (name) {
				case "ParameterBlock":
					expect(option, "ParameterGroup", child.getAttribute("Id"));
					walk(child, option, false);
					break;
				case "ChannelIndependentBlock":
					expect(option, "ChannelIndependent", null);
					walk(child, option, false);
					break;
				case "Channel":
					expect(option, "Channel", child.getAttribute("Id"));
					walk(child, option, false);
					break;
				case "choose":
					expect(option, "Switch", null);
					List<Element> cases = ModuleScan.children(child, "when");
					List<TLObject> alternatives = list(option, ALTERNATIVES);
					if (cases.size() != alternatives.size()) {
						throw new Mismatch("Cases of a switch differ.");
					}
					for (int n = 0; n < cases.size(); n++) {
						walk(cases.get(n), alternatives.get(n), topLevel);
					}
					break;
				default:
					expectPlaced(option, child.getAttribute("RefId"));
					break;
			}
		}
		if (index != contents.size()) {
			throw new Mismatch("Further structure elements in the database.");
		}
	}

	private static void expect(TLObject option, String type, String id) throws Mismatch {
		if (!(TYPE_PREFIX + type).equals(TLModelUtil.qualifiedName(option.tType()))) {
			throw new Mismatch("Expected " + type + ", found " + TLModelUtil.qualifiedName(option.tType()) + ".");
		}
		if (id != null && !id.isEmpty() && !id.equals(option.tValueByName(EXT_ID))) {
			throw new Mismatch("Expected " + id + ", found " + option.tValueByName(EXT_ID) + ".");
		}
	}

	private static void expectPlaced(TLObject option, String refId) throws Mismatch {
		TLObject placed = option;
		if ((TYPE_PREFIX + "SharedPlacement").equals(TLModelUtil.qualifiedName(option.tType()))) {
			placed = (TLObject) option.tValueByName("placed");
		}
		if (placed == null || !Objects.equals(refId, placed.tValueByName(EXT_ID))) {
			throw new Mismatch("Expected the placement of " + refId + ".");
		}
	}

	private static List<TLObject> list(TLObject obj, String attribute) {
		List<TLObject> result = new ArrayList<>();
		Object value = obj.tValueByName(attribute);
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				result.add((TLObject) element);
			}
		}
		return result;
	}

}
