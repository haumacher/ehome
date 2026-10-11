/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.roundtrip;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Structural comparison of an ETS project archive (<code>.knxproj</code>) with its export by E-Home.
 *
 * <p>
 * Both archives are compared XML file by XML file as element trees: element names, attributes as a
 * map, text content (without whitespace) and the order of the children. Whitespace between
 * elements, the order of attributes, namespace declarations and the XML declaration do not count.
 * What the model does not hold is described by the rules of <code>doc/knx/not-modeled.txt</code>
 * (see {@link Rules}): skipped files, elements and attributes, containers whose child order is not
 * kept, master data lists of which the export writes only what the project uses, and the
 * translatable texts, which are compared as the text in effect per application language instead of
 * as attributes and <code>Languages</code> sections. An empty attribute value counts as no value,
 * as for the import.
 * </p>
 */
public class KnxprojDiff {

	/**
	 * The application languages: the languages texts are compared in.
	 */
	public static final List<String> LANGUAGES = List.of("de", "en");

	private static final String LANGUAGES_ELEMENT = "Languages";

	/**
	 * One difference between the original and the exported file.
	 *
	 * @param file
	 *        The entry of the archive.
	 * @param path
	 *        The element (with its ID) where the difference is.
	 * @param kind
	 *        What differs, independent of the element instance, for a summary.
	 * @param detail
	 *        The values.
	 */
	public record Difference(String file, String path, String kind, String detail) {
		@Override
		public String toString() {
			return file + " " + path + ": " + kind + (detail.isEmpty() ? "" : " (" + detail + ")");
		}
	}

	/**
	 * The rules saying what the comparison skips, read from <code>doc/knx/not-modeled.txt</code>.
	 *
	 * <p>
	 * One rule per line, <code>#</code> starts a comment:
	 * </p>
	 * <ul>
	 * <li><code>file &lt;glob&gt;</code>: an archive entry that is not compared,</li>
	 * <li><code>skip &lt;path&gt;</code>: an element (with its content) that is not compared,</li>
	 * <li><code>skip &lt;path&gt;@&lt;attribute&gt;</code>: an attribute that is not compared,</li>
	 * <li><code>unordered &lt;path&gt;</code>: an element whose children are compared without their
	 * order,</li>
	 * <li><code>subset &lt;path&gt;</code>: an element of whose children the export writes only those the
	 * project uses (matched by ID); children of the original without a counterpart are ignored,</li>
	 * <li><code>merge &lt;path&gt;</code>: an element that may occur several times among its siblings,
	 * whose occurrences the model merges into one: compared as one element whose children of the same
	 * name are merged in turn (their children concatenated),</li>
	 * <li><code>default &lt;path&gt;@&lt;attribute&gt; &lt;values&gt;</code>: the values (separated by
	 * commas) that mean the schema's default of an optional attribute and count as no value,</li>
	 * <li><code>text &lt;path&gt;@&lt;attribute&gt;</code>: a translatable text, compared as its text in
	 * effect per application language (the translation of the language, else the attribute's
	 * text).</li>
	 * </ul>
	 * <p>
	 * A path is a suffix of the element path: element names separated by <code>/</code>, where
	 * <code>*</code> stands for any one element.
	 * </p>
	 */
	public static class Rules {

		final List<Pattern> _files = new ArrayList<>();

		final List<String[]> _skipElements = new ArrayList<>();

		final List<PathAttribute> _skipAttributes = new ArrayList<>();

		final List<String[]> _unordered = new ArrayList<>();

		final List<String[]> _subset = new ArrayList<>();

		final List<String[]> _merge = new ArrayList<>();

		final List<PathAttribute> _texts = new ArrayList<>();

		final List<DefaultValue> _defaults = new ArrayList<>();

		/**
		 * Reads the rules from the given file.
		 */
		public static Rules read(Path file) throws IOException {
			Rules rules = new Rules();
			int number = 0;
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				number++;
				int comment = line.indexOf('#');
				String rule = (comment >= 0 ? line.substring(0, comment) : line).trim();
				if (rule.isEmpty()) {
					continue;
				}
				String[] parts = rule.split("\\s+");
				if (parts[0].equals("default") && parts.length == 3) {
					rules._defaults.add(new DefaultValue(PathAttribute.parse(parts[1]), Set.of(parts[2].split(","))));
					continue;
				}
				if (parts.length != 2) {
					throw new IOException(file + ":" + number + ": Expected '<kind> <path>': " + line);
				}
				String path = parts[1];
				switch (parts[0]) {
					case "file" -> rules._files.add(glob(path));
					case "skip" -> {
						if (path.contains("@")) {
							rules._skipAttributes.add(PathAttribute.parse(path));
						} else {
							rules._skipElements.add(path.split("/"));
						}
					}
					case "unordered" -> rules._unordered.add(path.split("/"));
					case "subset" -> rules._subset.add(path.split("/"));
					case "merge" -> rules._merge.add(path.split("/"));
					case "text" -> rules._texts.add(PathAttribute.parse(path));
					default -> throw new IOException(file + ":" + number + ": Unknown rule: " + line);
				}
			}
			return rules;
		}

		private static Pattern glob(String glob) {
			StringBuilder regex = new StringBuilder();
			for (char ch : glob.toCharArray()) {
				switch (ch) {
					case '*' -> regex.append("[^/]*");
					case '?' -> regex.append("[^/]");
					default -> regex.append(Pattern.quote(String.valueOf(ch)));
				}
			}
			return Pattern.compile(regex.toString());
		}

		boolean skipsFile(String name) {
			return _files.stream().anyMatch(p -> p.matcher(name).matches());
		}

		boolean skipsElement(Node node) {
			return _skipElements.stream().anyMatch(p -> matches(p, node));
		}

		boolean skipsAttribute(Node node, String attribute) {
			return _skipAttributes.stream().anyMatch(p -> p.matches(node, attribute));
		}

		boolean isDefault(Node node, String attribute, String value) {
			return _defaults.stream().anyMatch(d -> d.attribute().matches(node, attribute) && d.values().contains(value));
		}

		boolean isUnordered(Node node) {
			return _unordered.stream().anyMatch(p -> matches(p, node));
		}

		boolean isSubset(Node node) {
			return _subset.stream().anyMatch(p -> matches(p, node));
		}

		boolean isMerged(Node node) {
			return _merge.stream().anyMatch(p -> matches(p, node));
		}

		boolean isText(Node node, String attribute) {
			return _texts.stream().anyMatch(p -> p.matches(node, attribute));
		}

		static boolean matches(String[] path, Node node) {
			Node current = node;
			for (int n = path.length - 1; n >= 0; n--) {
				if (current == null) {
					return false;
				}
				if (!path[n].equals("*") && !path[n].equals(current._name)) {
					return false;
				}
				current = current._parent;
			}
			return true;
		}
	}

	record DefaultValue(PathAttribute attribute, Set<String> values) {
		// Pure data.
	}

	record PathAttribute(String[] path, String attribute) {

		static PathAttribute parse(String spec) {
			int at = spec.indexOf('@');
			return new PathAttribute(spec.substring(0, at).split("/"), spec.substring(at + 1));
		}

		boolean matches(Node node, String name) {
			return (attribute.equals("*") || attribute.equals(name)) && Rules.matches(path, node);
		}
	}

	static final class Node {
		final String _name;

		final Node _parent;

		final Map<String, String> _attributes = new TreeMap<>();

		final StringBuilder _text = new StringBuilder();

		final List<Node> _children = new ArrayList<>();

		Node(String name, Node parent) {
			_name = name;
			_parent = parent;
		}

		String id() {
			String id = _attributes.get("Id");
			return id != null ? id : _attributes.get("RefId");
		}

		String key() {
			String id = id();
			return id == null ? _name : _name + "#" + id;
		}

		String text() {
			return _text.toString().replaceAll("\\s+", "");
		}

		String label() {
			String id = id();
			return id == null ? _name : _name + "[" + id + "]";
		}

		String path() {
			return (_parent == null ? "" : _parent.path()) + "/" + label();
		}

		String canonical() {
			StringBuilder out = new StringBuilder();
			canonical(out);
			return out.toString();
		}

		private void canonical(StringBuilder out) {
			out.append('<').append(_name);
			for (Map.Entry<String, String> entry : _attributes.entrySet()) {
				out.append(' ').append(entry.getKey()).append("=\"").append(entry.getValue()).append('"');
			}
			out.append('>').append(text());
			for (Node child : _children) {
				child.canonical(out);
			}
			out.append("</").append(_name).append('>');
		}
	}

	/**
	 * The texts of a file: per element ID and translatable attribute the text in effect per
	 * application language.
	 */
	static final class Texts {
		/** ID@attribute to language to text, from the attributes. */
		final Map<String, String> _defaults = new HashMap<>();

		/** ID@attribute@language to text, from the Languages sections. */
		final Map<String, String> _translations = new HashMap<>();

		final Map<String, Node> _ids = new HashMap<>();

		String effective(String key, String language) {
			String translation = _translations.get(key + "@" + language);
			if (translation != null && !translation.isEmpty()) {
				return translation;
			}
			String text = _defaults.get(key);
			if (text != null && !text.isEmpty()) {
				return text;
			}
			// Without a text of its own or in the default language, a reader sees another one.
			for (String other : LANGUAGES) {
				String alternative = _translations.get(key + "@" + other);
				if (alternative != null && !alternative.isEmpty()) {
					return alternative;
				}
			}
			return null;
		}

		Set<String> keys() {
			Set<String> result = new TreeSet<>(_defaults.keySet());
			for (String key : _translations.keySet()) {
				result.add(key.substring(0, key.lastIndexOf('@')));
			}
			return result;
		}
	}

	private final Rules _rules;

	private final List<Difference> _differences = new ArrayList<>();

	private String _file;

	/**
	 * Creates a {@link KnxprojDiff} with the given rules.
	 */
	public KnxprojDiff(Rules rules) {
		_rules = rules;
	}

	/**
	 * The XML entries of a <code>.knxproj</code> archive, by name.
	 */
	public static Map<String, byte[]> unzip(byte[] archive) throws IOException {
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					result.put(entry.getName(), zip.readAllBytes());
				}
			}
		}
		return result;
	}

	/**
	 * Compares the entries of an original archive with those of the export.
	 *
	 * @return The differences, empty if the export reproduces the original in everything the rules
	 *         do not skip.
	 */
	public List<Difference> compare(Map<String, byte[]> original, Map<String, byte[]> exported)
			throws IOException, XMLStreamException {
		_differences.clear();
		Set<String> names = new TreeSet<>(original.keySet());
		names.addAll(exported.keySet());
		for (String name : names) {
			if (_rules.skipsFile(name)) {
				continue;
			}
			_file = name;
			byte[] left = original.get(name);
			byte[] right = exported.get(name);
			if (left == null) {
				report("/", "extra file", "");
			} else if (right == null) {
				report("/", "missing file", "");
			} else {
				compareFile(left, right);
			}
		}
		return new ArrayList<>(_differences);
	}

	private void compareFile(byte[] left, byte[] right) throws XMLStreamException {
		Node original = parse(left);
		Node exported = parse(right);
		merge(original);
		merge(exported);
		Texts originalTexts = texts(original);
		Texts exportedTexts = texts(exported);
		compareNode(original, exported);
		compareTexts(originalTexts, exportedTexts);
	}

	private void compareTexts(Texts original, Texts exported) {
		Set<String> keys = new TreeSet<>(original.keys());
		keys.addAll(exported.keys());
		for (String key : keys) {
			String id = key.substring(0, key.indexOf('@'));
			if (!original._ids.containsKey(id) || !exported._ids.containsKey(id)) {
				// The element is missing on one side; reported by the element comparison, or not
				// exported because the project does not use it (subset).
				continue;
			}
			if (!_rules.isText(original._ids.get(id), key.substring(key.indexOf('@') + 1))) {
				// A translation of an attribute that is no text of the model.
				continue;
			}
			for (String language : LANGUAGES) {
				String left = original.effective(key, language);
				String right = exported.effective(key, language);
				if (!Objects.equals(left, right)) {
					report(key, "text " + key.substring(key.indexOf('@') + 1) + " (" + language + ") differs",
						left + " != " + right);
				}
			}
		}
	}

	private void merge(Node node) {
		Map<String, Node> first = new HashMap<>();
		for (var it = node._children.iterator(); it.hasNext();) {
			Node child = it.next();
			if (_rules.isMerged(child)) {
				Node target = first.get(child._name);
				if (target == null) {
					first.put(child._name, child);
				} else {
					mergeInto(target, child);
					it.remove();
				}
			}
		}
		for (Node child : node._children) {
			merge(child);
		}
	}

	/**
	 * Merges the given element into the target: its attributes, and its children into the target's
	 * child of the same name (their children concatenated), or as new children of the target.
	 */
	private static void mergeInto(Node target, Node other) {
		other._attributes.forEach(target._attributes::putIfAbsent);
		for (Node child : other._children) {
			Node same = target._children.stream().filter(c -> c._name.equals(child._name)).findFirst().orElse(null);
			if (same == null) {
				target._children.add(child);
			} else {
				same._children.addAll(child._children);
			}
		}
	}

	private Texts texts(Node root) {
		Texts texts = new Texts();
		collectTexts(root, texts);
		return texts;
	}

	private void collectTexts(Node node, Texts texts) {
		String id = node._attributes.get("Id");
		if (id != null) {
			texts._ids.put(id, node);
		}
		for (var it = node._attributes.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, String> entry = it.next();
			if (_rules.isText(node, entry.getKey())) {
				if (id != null) {
					texts._defaults.put(id + "@" + entry.getKey(), entry.getValue());
				}
				it.remove();
			}
		}
		for (var it = node._children.iterator(); it.hasNext();) {
			Node child = it.next();
			if (child._name.equals(LANGUAGES_ELEMENT)) {
				collectTranslations(child, texts);
				it.remove();
			} else {
				collectTexts(child, texts);
			}
		}
	}

	private void collectTranslations(Node languages, Texts texts) {
		for (Node language : languages._children) {
			String identifier = language._attributes.get("Identifier");
			String lang = identifier == null ? "" : identifier.split("-")[0];
			if (!LANGUAGES.contains(lang)) {
				// Languages the application does not offer are not modeled.
				continue;
			}
			for (Node unit : language._children) {
				for (Node element : unit._children) {
					String ref = element._attributes.get("RefId");
					for (Node translation : element._children) {
						texts._translations.put(ref + "@" + translation._attributes.get("AttributeName") + "@" + lang,
							translation._attributes.get("Text"));
					}
				}
			}
		}
	}

	private void compareNode(Node original, Node exported) {
		String path = original.path();
		Set<String> attributes = new TreeSet<>(original._attributes.keySet());
		attributes.addAll(exported._attributes.keySet());
		for (String attribute : attributes) {
			String left = original._attributes.get(attribute);
			String right = exported._attributes.get(attribute);
			if (left == null) {
				report(path, "extra attribute " + original._name + "@" + attribute, right);
			} else if (right == null) {
				report(path, "missing attribute " + original._name + "@" + attribute, left);
			} else if (!left.equals(right)) {
				report(path, "attribute " + original._name + "@" + attribute + " differs", left + " != " + right);
			}
		}
		if (!original.text().equals(exported.text())) {
			report(path, "text of " + original._name + " differs", abbreviate(original.text()) + " != "
				+ abbreviate(exported.text()));
		}

		List<Node> left = original._children;
		List<Node> right = exported._children;
		if (_rules.isSubset(original)) {
			Set<String> exportedKeys = new HashSet<>();
			for (Node child : right) {
				exportedKeys.add(child.key());
			}
			left = left.stream().filter(c -> exportedKeys.contains(c.key())).toList();
		}
		if (_rules.isUnordered(original)) {
			left = sorted(left);
			right = sorted(right);
		}
		compareChildren(original, left, right);
	}

	private static List<Node> sorted(List<Node> nodes) {
		Map<Node, String> keys = new HashMap<>();
		for (Node node : nodes) {
			keys.put(node, node.id() == null ? node._name + "~" + node.canonical() : node.key());
		}
		List<Node> result = new ArrayList<>(nodes);
		result.sort(Comparator.comparing(keys::get));
		return result;
	}

	private void compareChildren(Node parent, List<Node> left, List<Node> right) {
		int n = left.size();
		int m = right.size();
		boolean sameKeys = n == m;
		for (int i = 0; sameKeys && i < n; i++) {
			sameKeys = left.get(i).key().equals(right.get(i).key());
		}
		if (sameKeys) {
			for (int i = 0; i < n; i++) {
				compareNode(left.get(i), right.get(i));
			}
			return;
		}
		// Align by the longest common subsequence of the keys.
		if ((long) n * m > 50_000_000L) {
			report(parent.path(), "children of " + parent._name + " differ", n + " != " + m + " elements");
			return;
		}
		int[][] lcs = new int[n + 1][m + 1];
		for (int i = n - 1; i >= 0; i--) {
			for (int j = m - 1; j >= 0; j--) {
				lcs[i][j] = left.get(i).key().equals(right.get(j).key()) ? lcs[i + 1][j + 1] + 1
					: Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			}
		}
		int i = 0;
		int j = 0;
		while (i < n || j < m) {
			if (i < n && j < m && left.get(i).key().equals(right.get(j).key())) {
				compareNode(left.get(i++), right.get(j++));
			} else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
				Node extra = right.get(j++);
				report(parent.path() + "/" + extra.label(), "extra element " + parent._name + "/" + extra._name, "");
			} else {
				Node missing = left.get(i++);
				report(missing.path(), "missing element " + parent._name + "/" + missing._name, "");
			}
		}
	}

	private static String abbreviate(String text) {
		return text.length() > 40 ? text.substring(0, 40) + "…(" + text.length() + ")" : text;
	}

	private void report(String path, String kind, String detail) {
		_differences.add(new Difference(_file, path, kind, detail == null ? "" : detail));
	}

	private Node parse(byte[] content) throws XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
		factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
		InputStream in = new ByteArrayInputStream(content);
		XMLStreamReader reader = factory.createXMLStreamReader(in);
		Node root = null;
		Node current = null;
		// Depth below an element that is skipped, 0 if none.
		int skipping = 0;
		while (reader.hasNext()) {
			int event = reader.next();
			switch (event) {
				case XMLStreamConstants.START_ELEMENT -> {
					if (skipping > 0) {
						skipping++;
						break;
					}
					Node node = new Node(reader.getLocalName(), current);
					if (current != null && _rules.skipsElement(node)) {
						skipping = 1;
						break;
					}
					for (int n = 0; n < reader.getAttributeCount(); n++) {
						String namespace = reader.getAttributeNamespace(n);
						if (namespace != null && !namespace.isEmpty()
							&& !namespace.equals(XMLConstants.NULL_NS_URI)) {
							continue;
						}
						String name = reader.getAttributeLocalName(n);
						String value = reader.getAttributeValue(n);
						// An empty value is no value: the import reads both the same.
						if (!value.isEmpty() && !_rules.skipsAttribute(node, name)
							&& !_rules.isDefault(node, name, value)) {
							node._attributes.put(name, value);
						}
					}
					if (current == null) {
						root = node;
					} else {
						current._children.add(node);
					}
					current = node;
				}
				case XMLStreamConstants.END_ELEMENT -> {
					if (skipping > 0) {
						skipping--;
					} else {
						current = current._parent;
					}
				}
				case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
					if (skipping == 0 && current != null) {
						current._text.append(reader.getText());
					}
				}
				default -> {
					// Comments, processing instructions, whitespace.
				}
			}
		}
		reader.close();
		return root;
	}

	/**
	 * A summary of the differences: their number per file and kind, with the first examples of each.
	 */
	public static String summary(List<Difference> differences, int examples) {
		Map<String, List<Difference>> byKind = new TreeMap<>();
		for (Difference difference : differences) {
			byKind.computeIfAbsent(difference.file() + ": " + difference.kind(), k -> new ArrayList<>()).add(difference);
		}
		StringBuilder out = new StringBuilder();
		out.append(differences.size()).append(" differences\n");
		for (Map.Entry<String, List<Difference>> entry : byKind.entrySet()) {
			out.append(String.format("%6d  %s%n", entry.getValue().size(), entry.getKey()));
			for (Difference example : entry.getValue().subList(0, Math.min(examples, entry.getValue().size()))) {
				out.append("          ").append(example.path());
				if (!example.detail().isEmpty()) {
					out.append(": ").append(example.detail());
				}
				out.append('\n');
			}
		}
		return out.toString();
	}

}
