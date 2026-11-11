/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.top_logic.basic.col.TypedAnnotatable;
import com.top_logic.basic.col.TypedAnnotatable.Property;
import com.top_logic.basic.xml.XMLStreamUtil;
import com.top_logic.xio.importer.binding.ImportContext;

/**
 * What an import needs to know about the dynamic modules of the application programs of a program
 * file (ETS 5.6 and later: <code>ModuleDefs</code>, haumacher/ehome #119) before it reads the file,
 * found by reading it once as a stream ({@link #scan(byte[])}), and kept in the import's
 * {@link ImportContext} ({@link #get(ImportContext)}) for the handlers reading the programs.
 *
 * <ul>
 * <li>A construct the import cannot interpret ({@link #getRefusal(String)}): the program's modules are
 * not read (none of them), the program is marked (<code>usesModules</code>) and the import warns
 * naming the program and the construct (see {@link ReadModules}).</li>
 * <li>The position of each <code>Module</code> and <code>Repeat</code> in the document (ETS:
 * XmlOrder, {@link #getOrder(String)}): the place of the element among all elements of the dynamic
 * part of the program or of its module definition, the dynamic part itself counting 0. A module
 * instance's repeat index is made of these positions.</li>
 * <li>The completion of a program an earlier import left without its modules (see
 * {@link ModuleCompletion}).</li>
 * </ul>
 */
final class ModuleScan {

	/**
	 * The {@link ModuleScan} of an import in its {@link ImportContext}.
	 */
	static final Property<ModuleScan> PROPERTY = TypedAnnotatable.property(ModuleScan.class, "moduleScan");

	private static final String MODULE_DEFS = "ModuleDefs";

	private static final String MODULE_DEF = "ModuleDef";

	private static final String MODULE = "Module";

	private static final String REPEAT = "Repeat";

	private static final String DYNAMIC = "Dynamic";

	private static final String ID = "Id";

	private static final String REF_ID = "RefId";

	private static final byte[] MARKER = MODULE_DEFS.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

	private final Map<String, String> _refused = new HashMap<>();

	private final Map<String, Integer> _orders = new HashMap<>();

	private final Map<String, ModuleCompletion> _completions = new HashMap<>();

	/**
	 * The {@link ModuleScan} of the given import, created on first use.
	 */
	static ModuleScan get(ImportContext context) {
		ModuleScan result = context.get(PROPERTY);
		if (result == null) {
			result = new ModuleScan();
			context.set(PROPERTY, result);
		}
		return result;
	}

	/**
	 * Whether the given content of a program file may hold module definitions; a file without the
	 * name of their element is read without a scan.
	 */
	static boolean mentionsModules(byte[] content) {
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
	static Document parse(byte[] content) throws IOException {
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
	 * Scans the application programs of the given program file, records what it found and returns the
	 * IDs of the programs with module definitions.
	 *
	 * <p>
	 * The file is read as a stream: a program file may be large (tens of megabytes for a program
	 * with many module definitions).
	 * </p>
	 */
	List<String> scan(byte[] content) throws IOException {
		try {
			XMLStreamReader in = XMLStreamUtil.getDefaultInputFactory()
				.createXMLStreamReader(new ByteArrayInputStream(content));
			try {
				return scan(in);
			} finally {
				in.close();
			}
		} catch (XMLStreamException ex) {
			throw new IOException("Cannot read the program file: " + ex.getMessage(), ex);
		}
	}

	private List<String> scan(XMLStreamReader in) throws XMLStreamException {
		List<String> result = new ArrayList<>();
		ProgramScan program = null;
		List<String> path = new ArrayList<>();
		// The number of elements read in the current dynamic part, -1 outside of one.
		int dynamic = -1;
		int dynamicDepth = -1;
		while (in.hasNext()) {
			int event = in.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				String name = in.getLocalName();
				String parent = path.isEmpty() ? null : path.get(path.size() - 1);
				path.add(name);
				if ("ApplicationProgram".equals(name)) {
					program = new ProgramScan(in.getAttributeValue(null, ID));
					continue;
				}
				if (program == null) {
					continue;
				}
				if (dynamic >= 0) {
					dynamic++;
					if ((MODULE.equals(name) || REPEAT.equals(name)) && in.getAttributeValue(null, ID) != null) {
						_orders.put(in.getAttributeValue(null, ID), Integer.valueOf(dynamic));
					}
				} else if (DYNAMIC.equals(name)) {
					dynamic = 0;
					dynamicDepth = path.size();
				}
				program.element(in, name, parent, path);
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				String name = path.remove(path.size() - 1);
				if (dynamic >= 0 && path.size() + 1 == dynamicDepth) {
					dynamic = -1;
				}
				if ("ApplicationProgram".equals(name) && program != null) {
					if (program._modules) {
						String construct = program.refusal();
						if (construct != null) {
							_refused.put(program._id, construct);
						}
						result.add(program._id);
					}
					program = null;
				}
			}
		}
		return result;
	}

	/**
	 * What the scan collects of one application program.
	 */
	private static final class ProgramScan {

		final String _id;

		boolean _modules;

		String _construct;

		final Set<String> _definitions = new HashSet<>();

		final Set<String> _allocators = new HashSet<>();

		final List<String[]> _placements = new ArrayList<>();

		ProgramScan(String id) {
			_id = id;
		}

		void element(XMLStreamReader in, String name, String parent, List<String> path) {
			boolean inModules = path.contains(MODULE_DEFS);
			switch (name) {
				case MODULE_DEFS:
					_modules = true;
					break;
				case MODULE_DEF:
					_definitions.add(in.getAttributeValue(null, ID));
					break;
				case "Allocator":
					_allocators.add(in.getAttributeValue(null, ID));
					break;
				case "Argument": {
					String type = attribute(in, "Type");
					if (inModules && !type.isEmpty() && !"Numeric".equals(type) && !"Text".equals(type)) {
						refuse("Argument Type=" + type);
					}
					String alignment = attribute(in, "Alignment");
					if (inModules && !alignment.isEmpty() && !"1".equals(alignment)) {
						refuse("Argument Alignment=" + alignment);
					}
					break;
				}
				case "Parameter":
					if (inModules && in.getAttributeValue(null, "BaseValue") != null) {
						refuse("Parameter BaseValue");
					}
					break;
				case "Property":
					if (inModules && (in.getAttributeValue(null, "BaseIndex") != null
						|| in.getAttributeValue(null, "BaseOccurrence") != null)) {
						refuse("Property BaseIndex/BaseOccurrence");
					}
					break;
				case "LoadProcedures":
					if (inModules && "Static".equals(parent)) {
						refuse("ModuleDef LoadProcedures");
					}
					break;
				case "NumericArg":
					if (MODULE.equals(parent)) {
						String allocator = in.getAttributeValue(null, "AllocatorRefId");
						if (allocator != null) {
							_placements.add(new String[] { "Allocator", allocator });
						} else if (in.getAttributeValue(null, "Value") == null) {
							refuse("NumericArg " + attribute(in, REF_ID));
						}
					}
					break;
				case "TextArg":
					break;
				case MODULE:
					_placements.add(new String[] { MODULE, attribute(in, REF_ID) });
					break;
				default:
					if (MODULE.equals(parent)) {
						refuse(MODULE + "/" + name);
					}
					break;
			}
		}

		private void refuse(String construct) {
			if (_construct == null) {
				_construct = construct;
			}
		}

		String refusal() {
			if (_construct != null) {
				return _construct;
			}
			for (String[] placement : _placements) {
				Set<String> known = MODULE.equals(placement[0]) ? _definitions : _allocators;
				if (!known.contains(placement[1])) {
					return placement[0] + " " + placement[1];
				}
			}
			return null;
		}

		private static String attribute(XMLStreamReader in, String name) {
			String value = in.getAttributeValue(null, name);
			return value == null ? "" : value;
		}

	}

	/**
	 * The dynamic part of the given program in a parsed program file, <code>null</code> if it has
	 * none.
	 */
	static Element dynamicOf(Document document, String program) {
		Element element = program(document, program);
		return element == null ? null : child(element, DYNAMIC);
	}

	/**
	 * The element of the given program in a parsed program file, <code>null</code> if it has none.
	 */
	static Element program(Document document, String program) {
		for (Element element : descendants(document.getDocumentElement(), "ApplicationProgram")) {
			if (program.equals(element.getAttribute(ID))) {
				return element;
			}
		}
		return null;
	}

	/**
	 * The construct that prevents reading the modules of the given program, <code>null</code> if the
	 * import reads them.
	 */
	String getRefusal(String program) {
		return _refused.get(program);
	}

	/**
	 * The position of the <code>Module</code> or <code>Repeat</code> with the given ID in the document
	 * (see the class comment), <code>null</code> if it is not known.
	 */
	Integer getOrder(String id) {
		return _orders.get(id);
	}

	/**
	 * Records the completion of a program in the database.
	 */
	void complete(String program, ModuleCompletion completion) {
		_completions.put(program, completion);
	}

	/**
	 * The completion of the given program, <code>null</code> if it is not to be completed.
	 */
	ModuleCompletion getCompletion(String program) {
		return _completions.get(program);
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
