/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.calculation.DeviceButtons;
import de.haumacher.ehome.model.operational.Button;
import de.haumacher.ehome.model.operational.Program;

/**
 * Adds the buttons of the parameter dialog (ETS: <code>Button</code>, haumacher/ehome #158) to an
 * application program an earlier import read without them (<code>buttonsRead</code> not set), from the
 * program's file.
 *
 * <p>
 * The structure of the program in the database is the structure of the file without its buttons: the
 * completion walks both in parallel (the dynamic part of the program and those of its module
 * definitions) and inserts a button for each <code>Button</code> of the file at its position among the
 * elements of its container (a block, a channel, the channel independent part, a case of a switch, a
 * repeat), as {@link AssignCompletion} does for the assignments: a case is the case of the same position
 * in the switch of the same position in its container, which the walk checks against the file by the
 * IDs of the blocks and channels and the placements of the parameters on the way. A button whose ID the
 * program has already is left as it is, so the completion adds each button once. Elements of the file
 * without a structure element (<code>ParameterSeparator</code>) do not count, a rename or an assignment
 * of the file counts where the database has it; <code>Module</code> and <code>Repeat</code> count only if
 * the program's modules were read. If the two do not fit, nothing is added
 * ({@link ModuleCompletion.Mismatch}).
 * </p>
 *
 * <p>
 * A button added gets its text in the default language of the program and in the languages the file
 * translates it into (<code>Languages</code>), as the import of a new program gives it.
 * </p>
 */
final class ButtonCompletion {

	private static final String TYPE_PREFIX = "ehome.operational:";

	private static final String CONTENTS = "contents";

	private static final String EXT_ID = "extId";

	private static final String BUTTON = "Button";

	private static final String RENAME = "Rename";

	private static final String ASSIGN = "Assign";

	private static final String TEXT = "Text";

	/** A button to add: its element and where it goes. */
	private record Insertion(Element xml, TLObject fragment, int index) {
		// Plain data.
	}

	private final boolean _modules;

	private final Set<String> _known = new HashSet<>();

	private final Map<String, TLObject> _definitions = new HashMap<>();

	private final List<Insertion> _insertions = new ArrayList<>();

	private ButtonCompletion(Program program) {
		_modules = !Boolean.TRUE.equals(program.tValueByName("usesModules"));
		for (Button button : DeviceButtons.all(program)) {
			_known.add(button.getExtId());
		}
		collect(program);
	}

	private void collect(TLObject container) {
		for (TLObject definition : list(container, "modules")) {
			_definitions.put((String) definition.tValueByName(EXT_ID), definition);
			collect(definition);
		}
	}

	/**
	 * Adds the buttons of the given element of the file (<code>ApplicationProgram</code>) the given
	 * program lacks.
	 *
	 * @return The buttons added, in the order of the file.
	 * @throws ModuleCompletion.Mismatch
	 *         If the structure of the program does not fit the file; nothing is added then.
	 */
	static List<Button> complete(Element xml, Program program) throws ModuleCompletion.Mismatch {
		ButtonCompletion completion = new ButtonCompletion(program);
		Element dynamic = ModuleScan.child(xml, "Dynamic");
		if (dynamic != null) {
			completion.walk(dynamic, program);
		}
		if (completion._modules) {
			completion.walkDefinitions(ModuleScan.child(xml, "ModuleDefs"));
		}
		return completion.apply(xml);
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
		List<TLObject> contents = list(fragment, CONTENTS);
		int index = 0;
		for (Element child : ModuleScan.childElements(xml)) {
			String name = child.getLocalName();
			if (BUTTON.equals(name)) {
				String id = child.getAttribute("Id");
				if (_known.contains(id)) {
					// Read already: it stays at its place.
					if (index < contents.size() && is(contents.get(index), BUTTON)
						&& id.equals(contents.get(index).tValueByName(EXT_ID))) {
						index++;
					}
				} else {
					_known.add(id);
					_insertions.add(new Insertion(child, fragment, index));
				}
				continue;
			}
			if (RENAME.equals(name) || ASSIGN.equals(name)) {
				// Counts where the database has it at this place.
				if (index < contents.size() && is(contents.get(index), name)) {
					index++;
				}
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
					List<TLObject> alternatives = list(option, "alternatives");
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
	 * none (or one the walk does not count).
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
	 * The first position from the given one whose element the walk counts (no rename, assignment or
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
		return is(option, RENAME) || is(option, ASSIGN) || is(option, BUTTON);
	}

	private static boolean is(TLObject option, String type) {
		return (TYPE_PREFIX + type).equals(TLModelUtil.qualifiedName(option.tType()));
	}

	private List<Button> apply(Element program) {
		List<Button> result = new ArrayList<>();
		if (_insertions.isEmpty()) {
			return result;
		}
		Map<String, Map<Locale, String>> translations = translations(program);
		String defaultLanguage = program.getAttribute("DefaultLanguage");
		Locale locale = defaultLanguage.isEmpty() ? Locale.ENGLISH : I18NTexts.locale(defaultLanguage);
		Map<TLObject, Integer> inserted = new HashMap<>();
		TLClass type = (TLClass) TLModelUtil.findType(TYPE_PREFIX + BUTTON);
		for (Insertion insertion : _insertions) {
			Element xml = insertion.xml();
			Button button = (Button) ModelService.getInstance().getFactory().createObject(type);
			String id = xml.getAttribute("Id");
			button.setExtId(id);
			button.setName(value(xml, "Name"));
			button.setHandler(value(xml, "EventHandler"));
			button.setHandlerParameters(value(xml, "EventHandlerParameters"));
			I18NTexts.set(button, "text", locale, value(xml, TEXT), true);
			for (Map.Entry<Locale, String> translation : translations.getOrDefault(id, Map.of()).entrySet()) {
				I18NTexts.set(button, "text", translation.getKey(), translation.getValue(), false);
			}
			TLObject fragment = insertion.fragment();
			int offset = inserted.getOrDefault(fragment, Integer.valueOf(0)).intValue();
			List<Object> contents = new ArrayList<>((Collection<?>) fragment.tValueByName(CONTENTS));
			contents.add(Math.min(insertion.index() + offset, contents.size()), button);
			fragment.tUpdateByName(CONTENTS, contents);
			inserted.put(fragment, Integer.valueOf(offset + 1));
			result.add(button);
		}
		return result;
	}

	/**
	 * The texts of the buttons to add in further languages (ETS: <code>Languages/Language/
	 * TranslationUnit/TranslationElement/Translation</code> with the attribute <code>Text</code>), by the
	 * IDs of the buttons.
	 */
	private Map<String, Map<Locale, String>> translations(Element program) {
		Set<String> ids = new HashSet<>();
		for (Insertion insertion : _insertions) {
			ids.add(insertion.xml().getAttribute("Id"));
		}
		Map<String, Map<Locale, String>> result = new HashMap<>();
		NodeList languages = program.getOwnerDocument().getElementsByTagNameNS("*", "Language");
		for (int n = 0; n < languages.getLength(); n++) {
			Element language = (Element) languages.item(n);
			String identifier = language.getAttribute("Identifier");
			if (identifier.isEmpty()) {
				continue;
			}
			Locale locale = I18NTexts.locale(identifier);
			NodeList elements = language.getElementsByTagNameNS("*", "TranslationElement");
			for (int k = 0; k < elements.getLength(); k++) {
				Element element = (Element) elements.item(k);
				String id = element.getAttribute("RefId");
				if (!ids.contains(id)) {
					continue;
				}
				for (Element translation : ModuleScan.children(element, "Translation")) {
					if (TEXT.equals(translation.getAttribute("AttributeName"))) {
						result.computeIfAbsent(id, x -> new LinkedHashMap<>()).put(locale,
							translation.getAttribute(TEXT));
					}
				}
			}
		}
		return result;
	}

	private static String value(Element xml, String attribute) {
		String result = xml.getAttribute(attribute);
		return result.isEmpty() ? null : result;
	}

	private static void expect(TLObject option, String type, String id) throws ModuleCompletion.Mismatch {
		if (!is(option, type)) {
			throw new ModuleCompletion.Mismatch(
				"Expected " + type + ", found " + TLModelUtil.qualifiedName(option.tType()) + ".");
		}
		if (id != null && !id.isEmpty() && !id.equals(option.tValueByName(EXT_ID))) {
			throw new ModuleCompletion.Mismatch("Expected " + id + ", found " + option.tValueByName(EXT_ID) + ".");
		}
	}

	private static void expectPlaced(TLObject option, String refId) throws ModuleCompletion.Mismatch {
		TLObject placed = option;
		if (is(option, "SharedPlacement")) {
			placed = (TLObject) option.tValueByName("placed");
		}
		if (placed == null || !Objects.equals(refId, placed.tValueByName(EXT_ID))) {
			throw new ModuleCompletion.Mismatch("Expected the placement of " + refId + ".");
		}
	}

	private static List<TLObject> list(TLObject obj, String attribute) {
		List<TLObject> result = new ArrayList<>();
		if (obj.tType().getPart(attribute) == null) {
			return result;
		}
		Object value = obj.tValueByName(attribute);
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				result.add((TLObject) element);
			}
		}
		return result;
	}

}
