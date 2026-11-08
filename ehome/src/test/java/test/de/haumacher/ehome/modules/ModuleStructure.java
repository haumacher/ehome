/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.modules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/**
 * A program file reduced to the module structure of its programs, for a test of that structure
 * (haumacher/ehome #119): the module definitions, the repeats and placements of the dynamic part,
 * the types, segments, tables and options, and of the program's own parameters and objects those the
 * module definitions and the repeats refer to. Left out: the translations (<code>Languages</code>),
 * of the program's dynamic part everything but its repeats (its channel independent part, its
 * switches), and the program's other parameters, parameter references, objects and object
 * references.
 *
 * <p>
 * The program keeps its ID: it is imported only by the test reducing it.
 * </p>
 */
final class ModuleStructure {

	private static final String ID = "Id";

	private static final String REF_ID = "RefId";

	private ModuleStructure() {
		// Static only.
	}

	/**
	 * The given program file reduced to its module structure.
	 */
	static byte[] of(byte[] xml) throws Exception {
		Set<String> referenced = new HashSet<>();
		Map<String, String> refs = new HashMap<>();
		collect(xml, referenced, refs);
		Set<String> keep = new HashSet<>(referenced);
		for (String ref : referenced) {
			String target = refs.get(ref);
			if (target != null) {
				keep.add(target);
			}
		}
		return filter(xml, keep);
	}

	/**
	 * Collects the IDs of the program's own parts the module definitions and the repeats of the
	 * program refer to, and the targets of the program's parameter and object references.
	 */
	private static void collect(byte[] xml, Set<String> referenced, Map<String, String> refs) throws Exception {
		XMLStreamReader in = XMLInputFactory.newInstance().createXMLStreamReader(new ByteArrayInputStream(xml));
		List<String> path = new ArrayList<>();
		String program = null;
		while (in.hasNext()) {
			int event = in.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				String name = in.getLocalName();
				path.add(name);
				if ("ApplicationProgram".equals(name)) {
					program = in.getAttributeValue(null, ID) + "_";
				}
				if (program == null) {
					continue;
				}
				boolean modules = path.contains("ModuleDefs");
				if (modules || "Repeat".equals(name)) {
					for (int n = 0, cnt = in.getAttributeCount(); n < cnt; n++) {
						String value = in.getAttributeValue(n);
						if (value.startsWith(program) && !value.startsWith(program + "MD-")) {
							referenced.add(value);
						}
					}
				}
				if (!modules && ("ParameterRef".equals(name) || "ComObjectRef".equals(name))) {
					refs.put(in.getAttributeValue(null, ID), in.getAttributeValue(null, REF_ID));
				}
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				path.remove(path.size() - 1);
			}
		}
		in.close();
	}

	private static byte[] filter(byte[] xml, Set<String> keep) throws Exception {
		XMLEventReader in = XMLInputFactory.newInstance().createXMLEventReader(new ByteArrayInputStream(xml));
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		XMLEventWriter out = XMLOutputFactory.newInstance().createXMLEventWriter(buffer, "UTF-8");
		List<String> path = new ArrayList<>();
		while (in.hasNext()) {
			XMLEvent event = in.nextEvent();
			if (event.isStartElement()) {
				StartElement start = event.asStartElement();
				String name = start.getName().getLocalPart();
				String parent = path.isEmpty() ? null : path.get(path.size() - 1);
				boolean program = !path.contains("ModuleDefs") && path.contains("ApplicationProgram");
				if ("Union".equals(name) && program) {
					List<XMLEvent> union = subtree(in, event);
					if (union.stream().anyMatch(e -> e.isStartElement() && keep.contains(id(e.asStartElement())))) {
						for (XMLEvent element : union) {
							out.add(element);
						}
					}
					continue;
				}
				if (skipped(name, parent, path, program, start, keep)) {
					subtree(in, event);
					continue;
				}
				path.add(name);
			} else if (event.isEndElement()) {
				path.remove(path.size() - 1);
			}
			out.add(event);
		}
		out.close();
		in.close();
		return buffer.toByteArray();
	}

	private static boolean skipped(String name, String parent, List<String> path, boolean program, StartElement start,
			Set<String> keep) {
		if ("Languages".equals(name)) {
			return true;
		}
		if (!program) {
			return false;
		}
		switch (name) {
			case "Parameter":
				return "Parameters".equals(parent) && !keep.contains(id(start));
			case "ParameterRef":
				return "ParameterRefs".equals(parent) && !keep.contains(id(start));
			case "ComObject":
				return "ComObjectTable".equals(parent) && !keep.contains(id(start));
			case "ComObjectRef":
				return "ComObjectRefs".equals(parent) && !keep.contains(id(start));
			default:
				// Of the program's dynamic part, the repeats only (placing the modules).
				return "Dynamic".equals(parent) && !"Repeat".equals(name);
		}
	}

	private static String id(StartElement start) {
		Attribute id = start.getAttributeByName(new QName(ID));
		return id == null ? null : id.getValue();
	}

	/**
	 * The events of the element begun by the given event up to its end, read from the reader.
	 */
	private static List<XMLEvent> subtree(XMLEventReader in, XMLEvent start) throws Exception {
		List<XMLEvent> result = new ArrayList<>();
		result.add(start);
		int depth = 1;
		while (depth > 0) {
			XMLEvent event = in.nextEvent();
			result.add(event);
			if (event.isStartElement()) {
				depth++;
			} else if (event.isEndElement()) {
				depth--;
			}
		}
		return result;
	}

}
