/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.exporter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamException;

/**
 * Writes one XML file of a <code>.knxproj</code> archive: UTF-8, the namespace of the project schema
 * 11, indented by two spaces as ETS does.
 *
 * <p>
 * Attributes with a <code>null</code> value are left out, so that a value the model does not hold is
 * not written.
 * </p>
 */
final class XmlOut {

	/**
	 * The namespace of the KNX project schema 11 (ETS 4), the version of the files E-Home imports.
	 */
	static final String NAMESPACE = "http://knx.org/xml/project/11";

	private static final String INDENT = "  ";

	private final Writer _out;

	/** The names of the open elements. */
	private final List<String> _open = new ArrayList<>();

	/** Per open element, whether it has content. */
	private final List<Boolean> _hasContent = new ArrayList<>();

	/** Whether the start tag of the innermost element is still open for attributes. */
	private boolean _inStartTag;

	/**
	 * Starts a file with its root element <code>KNX</code>.
	 */
	XmlOut(OutputStream out) throws XMLStreamException {
		_out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
		write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
		startTag("KNX");
		attr("xmlns", NAMESPACE);
		attr("CreatedBy", "E-Home");
		attr("ToolVersion", "E-Home .knxproj export (schema 11, unsigned)");
	}

	/**
	 * Opens an element; its attributes follow, then its content, then {@link #close()}.
	 */
	XmlOut open(String name) throws XMLStreamException {
		closeStartTag();
		_hasContent.set(_hasContent.size() - 1, Boolean.TRUE);
		write("\n" + INDENT.repeat(_open.size()));
		startTag(name);
		return this;
	}

	private void startTag(String name) throws XMLStreamException {
		write("<" + name);
		_open.add(name);
		_hasContent.add(Boolean.FALSE);
		_inStartTag = true;
	}

	/**
	 * Writes an attribute of the element just opened, if the value is not <code>null</code>.
	 */
	XmlOut attr(String name, Object value) throws XMLStreamException {
		if (value != null) {
			if (!_inStartTag) {
				throw new IllegalStateException("Attribute '" + name + "' after the content of an element.");
			}
			write(" " + name + "=\"" + escape(value.toString(), true) + "\"");
		}
		return this;
	}

	/**
	 * Writes the text content of the element just opened.
	 */
	XmlOut text(String text) throws XMLStreamException {
		if (text != null) {
			closeStartTag();
			write(escape(text, false));
		}
		return this;
	}

	/**
	 * Closes the innermost open element.
	 */
	XmlOut close() throws XMLStreamException {
		int last = _open.size() - 1;
		String name = _open.remove(last);
		boolean hasContent = _hasContent.remove(last).booleanValue();
		if (_inStartTag) {
			write(" />");
			_inStartTag = false;
		} else {
			if (hasContent) {
				write("\n" + INDENT.repeat(last));
			}
			write("</" + name + ">");
		}
		return this;
	}

	/**
	 * Closes the root element and the document, and flushes the output (which stays open).
	 */
	void finish() throws XMLStreamException {
		while (!_open.isEmpty()) {
			close();
		}
		write("\n");
		try {
			_out.flush();
		} catch (IOException ex) {
			throw new XMLStreamException(ex);
		}
	}

	private void closeStartTag() throws XMLStreamException {
		if (_inStartTag) {
			write(">");
			_inStartTag = false;
		}
	}

	private void write(String text) throws XMLStreamException {
		try {
			_out.write(text);
		} catch (IOException ex) {
			throw new XMLStreamException(ex);
		}
	}

	/**
	 * Escapes the markup characters; in an attribute also quotes and line breaks and tabs, which a
	 * parser would otherwise normalize to spaces.
	 */
	private static String escape(String text, boolean attribute) {
		StringBuilder result = new StringBuilder(text.length());
		for (int n = 0; n < text.length(); n++) {
			char ch = text.charAt(n);
			switch (ch) {
				case '&' -> result.append("&amp;");
				case '<' -> result.append("&lt;");
				case '>' -> result.append("&gt;");
				case '"' -> result.append(attribute ? "&quot;" : "\"");
				case '\n' -> result.append(attribute ? "&#xA;" : "\n");
				case '\r' -> result.append("&#xD;");
				case '\t' -> result.append(attribute ? "&#x9;" : "\t");
				default -> result.append(ch);
			}
		}
		return result.toString();
	}

}
