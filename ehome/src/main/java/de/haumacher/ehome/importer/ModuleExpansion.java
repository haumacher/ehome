/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Expands the dynamic modules of the application programs of a program file (ETS 5.6 and later:
 * <code>ModuleDefs</code>) into ordinary program parts (haumacher/ehome #119), before the import
 * reads the file.
 *
 * <p>
 * A module definition is a template of parameters, parameter references, communication objects,
 * object references and structure (channels, blocks, switches), which the dynamic part of the
 * program places with <code>&lt;Module&gt;</code>, each time with its own arguments. Every placement
 * becomes a copy of the template:
 * </p>
 * <ul>
 * <li>its parts get the IDs ETS gives the instance in a device:
 * <code>&lt;program&gt;_MD-1_P-3</code> of the placement <code>&lt;program&gt;_MD-1_M-2</code> becomes
 * <code>&lt;program&gt;_MD-1_M-2_MI-1_P-3</code>, so that the settings and objects of a device
 * (<code>ParameterInstanceRef</code>, <code>ComObjectInstanceRef</code>) resolve to them,</li>
 * <li>the number of a communication object is its <code>Number</code> plus the value of the argument
 * its <code>BaseNumber</code> names, the offset of a parameter's memory its <code>Offset</code> plus
 * the value of the argument its <code>BaseOffset</code> names (the bit offset is kept),</li>
 * <li><code>{{name}}</code> in a text (in every language) is replaced by the value of the argument
 * of that name,</li>
 * <li>the static parts are added to the sections of the program's <code>Static</code>, the structure
 * replaces the <code>&lt;Module&gt;</code> element, inside <code>choose</code>/<code>when</code> as
 * well.</li>
 * </ul>
 *
 * <p>
 * Each copy is wrapped in an element <code>ExpandedModule</code> (a name the KNX schema does not use),
 * which the import reads as transparent: its content counts as content of the enclosing element. The
 * wrappers mark the parts a completion of a program imported earlier without its modules adds (see
 * {@link ModuleCompletion}).
 * </p>
 *
 * <p>
 * A program using a construct that cannot be expanded (<code>Repeat</code>, an argument computed by
 * an <code>Allocator</code>, <code>SubModuleDefs</code>) is left as it is: its
 * <code>ModuleDefs</code> are skipped by {@link ModulesNotImported}, which names the construct.
 * </p>
 */
public final class ModuleExpansion {

	/**
	 * Name of the element wrapping the parts of one placement of a module.
	 */
	public static final String WRAPPER = "ExpandedModule";

	/**
	 * Attribute of a wrapper in the dynamic part: its index among the wrappers of its program, set by
	 * {@link ModuleCompletion}.
	 */
	static final String SEQUENCE = "Sequence";

	/**
	 * The infix of the IDs of the parts of a module instance, after the ID of the placement.
	 */
	private static final String INSTANCE = "_MI-1_";

	private static final String MODULE_DEFS = "ModuleDefs";

	private static final String MODULE_DEF = "ModuleDef";

	private static final String MODULE = "Module";

	private static final String STATIC = "Static";

	private static final String DYNAMIC = "Dynamic";

	private static final String ID = "Id";

	private static final String REF_ID = "RefId";

	private static final String VALUE = "Value";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}:]+)\\}\\}");

	private static final byte[] MARKER = MODULE_DEFS.getBytes(StandardCharsets.US_ASCII);

	/**
	 * What the expansion did with the modules of one application program.
	 */
	public static final class Result {

		private final String _program;

		private final String _construct;

		private final Element _dynamic;

		private final List<Element> _wrappers;

		Result(String program, String construct, Element dynamic, List<Element> wrappers) {
			_program = program;
			_construct = construct;
			_dynamic = dynamic;
			_wrappers = wrappers;
		}

		/**
		 * The ID of the application program.
		 */
		public String getProgram() {
			return _program;
		}

		/**
		 * The construct that prevented the expansion, <code>null</code> if the modules were expanded.
		 */
		public String getConstruct() {
			return _construct;
		}

		/**
		 * Whether the modules were expanded.
		 */
		public boolean isExpanded() {
			return _construct == null;
		}

		/**
		 * The dynamic part of the expanded program, <code>null</code> if it has none.
		 */
		public Element getDynamic() {
			return _dynamic;
		}

		/**
		 * The wrappers of the expanded structure, in document order.
		 */
		public List<Element> getWrappers() {
			return _wrappers;
		}

	}

	/**
	 * A construct that prevents the expansion.
	 */
	static final class Refusal extends Exception {

		private final String _construct;

		Refusal(String construct) {
			super(construct, null, false, false);
			_construct = construct;
		}

		public String getConstruct() {
			return _construct;
		}

	}

	private ModuleExpansion() {
		// Static helpers only.
	}

	/**
	 * Whether the given content of a program file may hold module definitions; a file without the
	 * name of their element needs no expansion and is read as it is.
	 */
	public static boolean mentionsModules(byte[] content) {
		outer:
		for (int n = 0, last = content.length - MARKER.length; n <= last; n++) {
			for (int k = 0; k < MARKER.length; k++) {
				if (content[n + k] != MARKER[k]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * Parses a program file.
	 */
	public static Document parse(byte[] content) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(new ByteArrayInputStream(content));
		} catch (ParserConfigurationException | SAXException ex) {
			throw new IOException("Cannot read the program file: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Writes the given document as UTF-8.
	 */
	public static byte[] serialize(Document document) throws IOException {
		try {
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.INDENT, "no");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			transformer.transform(new DOMSource(document), new StreamResult(out));
			return out.toByteArray();
		} catch (TransformerException ex) {
			throw new IOException("Cannot write the expanded program file: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Expands the modules of every application program of the given document in place.
	 *
	 * @return What was done per program with modules; programs without modules are left out.
	 */
	public static List<Result> expand(Document document) {
		List<Result> result = new ArrayList<>();
		for (Element program : descendants(document.getDocumentElement(), "ApplicationProgram")) {
			Element defs = child(program, MODULE_DEFS);
			if (defs == null) {
				continue;
			}
			String id = program.getAttribute(ID);
			try {
				result.add(expandProgram(document, program, defs));
			} catch (Refusal ex) {
				result.add(new Result(id, ex.getConstruct(), null, List.of()));
			}
		}
		return result;
	}

	private static Result expandProgram(Document document, Element program, Element defs) throws Refusal {
		String programId = program.getAttribute(ID);
		checkConstructs(program);

		Map<String, Element> definitions = new HashMap<>();
		for (Element def : children(defs, MODULE_DEF)) {
			definitions.put(def.getAttribute(ID), def);
		}
		Element dynamic = child(program, DYNAMIC);
		List<Element> placements = dynamic == null ? List.of() : descendants(dynamic, MODULE);

		// Everything is copied before the document changes: a refusal leaves it as it is.
		List<Instance> instances = new ArrayList<>();
		for (Element placement : placements) {
			Element def = definitions.get(placement.getAttribute(REF_ID));
			if (def == null) {
				throw new Refusal(MODULE + " " + placement.getAttribute(REF_ID));
			}
			Instance instance = new Instance(def, placement);
			instance.copy();
			instances.add(instance);
		}
		List<Element> units = translationUnits(document, programId);
		Map<Element, List<Element>> translations = new LinkedHashMap<>();
		List<Element> templates = new ArrayList<>();
		for (Element unit : units) {
			List<Element> copies = new ArrayList<>();
			for (Element element : children(unit, "TranslationElement")) {
				String ref = element.getAttribute(REF_ID);
				boolean template = false;
				for (String defId : definitions.keySet()) {
					if (ref.startsWith(defId + "_")) {
						template = true;
					}
				}
				if (!template) {
					continue;
				}
				templates.add(element);
				for (Instance instance : instances) {
					if (ref.startsWith(instance._prefix)) {
						copies.add(instance.transformed(element));
					}
				}
			}
			translations.put(unit, copies);
		}

		// Apply.
		Element statics = child(program, STATIC);
		if (statics == null) {
			statics = document.createElementNS(program.getNamespaceURI(), STATIC);
			program.insertBefore(statics, dynamic);
		}
		List<Element> wrappers = new ArrayList<>();
		for (Instance instance : instances) {
			for (Map.Entry<String, List<Element>> section : instance._static.entrySet()) {
				Element target = child(statics, section.getKey());
				if (target == null) {
					target = document.createElementNS(statics.getNamespaceURI(), section.getKey());
					statics.appendChild(target);
				}
				target.appendChild(wrapper(document, statics, section.getValue()));
			}
			Element wrapper = wrapper(document, instance._placement, instance._dynamic);
			instance._placement.getParentNode().replaceChild(wrapper, instance._placement);
			wrappers.add(wrapper);
		}
		for (Map.Entry<Element, List<Element>> entry : translations.entrySet()) {
			for (Element copy : entry.getValue()) {
				entry.getKey().appendChild(copy);
			}
		}
		for (Element template : templates) {
			template.getParentNode().removeChild(template);
		}
		program.removeChild(defs);
		return new Result(programId, null, dynamic, wrappers);
	}

	/**
	 * Refuses a program using a construct that cannot be expanded.
	 */
	private static void checkConstructs(Element program) throws Refusal {
		if (!descendants(program, "Repeat").isEmpty()) {
			throw new Refusal("Repeat");
		}
		if (!descendants(program, "SubModuleDefs").isEmpty()) {
			throw new Refusal("SubModuleDefs");
		}
		Element defs = child(program, MODULE_DEFS);
		if (defs != null && !descendants(defs, MODULE).isEmpty()) {
			// A module placed by a module: a sub-module.
			throw new Refusal("SubModuleDefs");
		}
		for (String argument : List.of("NumericArg", "TextArg")) {
			for (Element arg : descendants(program, argument)) {
				if (arg.hasAttribute("AllocatorRefId")) {
					throw new Refusal("Allocator");
				}
				if (!arg.hasAttribute(VALUE)) {
					throw new Refusal(argument + " " + arg.getAttribute(REF_ID));
				}
			}
		}
	}

	private static Element wrapper(Document document, Element context, List<Element> content) {
		Element result = document.createElementNS(context.getNamespaceURI(), WRAPPER);
		for (Element element : content) {
			result.appendChild(element);
		}
		return result;
	}

	private static List<Element> translationUnits(Document document, String program) {
		List<Element> result = new ArrayList<>();
		for (Element unit : descendants(document.getDocumentElement(), "TranslationUnit")) {
			if (program.equals(unit.getAttribute(REF_ID))) {
				result.add(unit);
			}
		}
		return result;
	}

	/**
	 * One placement of a module definition.
	 */
	private static final class Instance {

		private final Element _def;

		private final Element _placement;

		/** The beginning of the IDs of the parts of the definition. */
		private final String _prefix;

		/** The beginning of the IDs of the parts of the instance. */
		private final String _instancePrefix;

		/** The values of the arguments by their IDs. */
		private final Map<String, String> _values = new HashMap<>();

		/** The values of the arguments by their names. */
		private final Map<String, String> _named = new HashMap<>();

		/** The copied static parts by the section of the program they belong to. */
		private final Map<String, List<Element>> _static = new LinkedHashMap<>();

		/** The copied structure. */
		private final List<Element> _dynamic = new ArrayList<>();

		Instance(Element def, Element placement) {
			_def = def;
			_placement = placement;
			_prefix = def.getAttribute(ID) + "_";
			_instancePrefix = placement.getAttribute(ID) + INSTANCE;
			for (Element arg : childElements(placement)) {
				if (arg.hasAttribute(REF_ID)) {
					_values.put(arg.getAttribute(REF_ID), arg.getAttribute(VALUE));
				}
			}
			Element arguments = child(def, "Arguments");
			if (arguments != null) {
				for (Element argument : children(arguments, "Argument")) {
					String value = _values.get(argument.getAttribute(ID));
					if (value != null && argument.hasAttribute("Name")) {
						_named.put(argument.getAttribute("Name"), value);
					}
				}
			}
		}

		void copy() throws Refusal {
			Element statics = child(_def, STATIC);
			if (statics != null) {
				for (Element section : childElements(statics)) {
					String name = section.getLocalName();
					// A module names its object table ComObjects; the program ComObjectTable.
					String target = "ComObjects".equals(name) ? "ComObjectTable" : name;
					List<Element> parts = _static.computeIfAbsent(target, x -> new ArrayList<>());
					for (Element part : childElements(section)) {
						parts.add(transformed(part));
					}
				}
			}
			Element dynamic = child(_def, DYNAMIC);
			if (dynamic != null) {
				for (Element part : childElements(dynamic)) {
					_dynamic.add(transformed(part));
				}
			}
		}

		Element transformed(Element element) throws Refusal {
			Element copy = (Element) element.cloneNode(true);
			transform(copy);
			return copy;
		}

		private void transform(Element element) throws Refusal {
			addBase(element, "BaseNumber", "Number");
			addBase(element, "BaseOffset", "Offset");
			NamedNodeMap attributes = element.getAttributes();
			for (int n = 0, cnt = attributes.getLength(); n < cnt; n++) {
				Attr attribute = (Attr) attributes.item(n);
				String value = attribute.getValue();
				String changed = substitute(rewrite(value));
				if (!changed.equals(value)) {
					attribute.setValue(changed);
				}
			}
			for (Element child : childElements(element)) {
				transform(child);
			}
		}

		private void addBase(Element element, String baseAttribute, String attribute) throws Refusal {
			if (!element.hasAttribute(baseAttribute)) {
				return;
			}
			String argument = element.getAttribute(baseAttribute);
			String base = _values.get(argument);
			if (base == null) {
				throw new Refusal(baseAttribute + " " + argument);
			}
			String own = element.hasAttribute(attribute) ? element.getAttribute(attribute) : "0";
			try {
				long value = Long.parseLong(own.trim()) + Long.parseLong(base.trim());
				element.setAttribute(attribute, Long.toString(value));
			} catch (NumberFormatException ex) {
				throw new Refusal(baseAttribute + " " + argument);
			}
			element.removeAttribute(baseAttribute);
		}

		/**
		 * The given value with every ID of a part of the definition replaced by the ID of the
		 * part of this instance (values may list several IDs separated by spaces).
		 */
		private String rewrite(String value) {
			if (!value.contains(_prefix)) {
				return value;
			}
			String[] tokens = value.split(" ", -1);
			for (int n = 0; n < tokens.length; n++) {
				if (tokens[n].startsWith(_prefix)) {
					tokens[n] = _instancePrefix + tokens[n].substring(_prefix.length());
				}
			}
			return String.join(" ", tokens);
		}

		/**
		 * The given text with <code>{{name}}</code> replaced by the value of the argument of that
		 * name; other placeholders (<code>{{0}}</code> of a text parameter) are kept.
		 */
		private String substitute(String text) {
			if (!text.contains("{{")) {
				return text;
			}
			Matcher matcher = PLACEHOLDER.matcher(text);
			StringBuilder result = new StringBuilder();
			while (matcher.find()) {
				String value = _named.get(matcher.group(1));
				matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? matcher.group() : value));
			}
			matcher.appendTail(result);
			return result.toString();
		}

	}

	/**
	 * The first child element with the given local name, <code>null</code> if there is none.
	 */
	static Element child(Element parent, String name) {
		for (Element child : childElements(parent)) {
			if (name.equals(child.getLocalName())) {
				return child;
			}
		}
		return null;
	}

	/**
	 * The child elements with the given local name.
	 */
	static List<Element> children(Element parent, String name) {
		List<Element> result = new ArrayList<>();
		for (Element child : childElements(parent)) {
			if (name.equals(child.getLocalName())) {
				result.add(child);
			}
		}
		return result;
	}

	/**
	 * The child elements of the given element.
	 */
	static List<Element> childElements(Element parent) {
		List<Element> result = new ArrayList<>();
		for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Element element) {
				result.add(element);
			}
		}
		return result;
	}

	private static List<Element> descendants(Element root, String name) {
		List<Element> result = new ArrayList<>();
		NodeList all = root.getElementsByTagNameNS("*", name);
		for (int n = 0, cnt = all.getLength(); n < cnt; n++) {
			result.add((Element) all.item(n));
		}
		return result;
	}

}
