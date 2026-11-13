/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.DefaultUrlSanitizer;
import org.commonmark.renderer.html.HtmlRenderer;

import com.top_logic.basic.Logger;
import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * The icons and help texts of an application program (ETS: <code>IconFile</code>,
 * <code>Icon</code>, <code>ContextHelpFile</code>, <code>HelpContext</code>, haumacher/ehome #156,
 * #157): what the parameter editor of a device and the catalog show of them.
 *
 * <p>
 * A program names an archive of its manufacturer's baggage holding a picture per icon
 * (<code>Program#iconFile</code>) and an archive per language holding a text per help topic
 * (<code>Program#helpFiles</code>); the import unpacks them into the files of the archives
 * (<code>ehome.catalog:Baggage#entries</code>). An entry of an enumeration, a channel, a parameter
 * block and a parameter at its place name their icon (<code>icon</code>), a parameter block and a
 * parameter at its place their help topic (<code>helpTopic</code>), both by the name of the file
 * in the archive without its extension.
 * </p>
 *
 * <p>
 * An icon is shown if its file is a picture every browser shows (the formats of
 * {@link ParameterPictures#SHOWN}; an SVG is a document that can carry scripts and is not shown);
 * an icon the archive does not hold is not shown, and its element shows its text alone. A help
 * text is the one of the reader's language, else the one of the program's default language; the
 * manufacturers write them in Markdown, which is shown as HTML ({@link #html(String)}: raw HTML is
 * escaped, pictures are left out, links only to the web); a topic without a text shows nothing.
 * </p>
 *
 * <p>
 * The TL-Script functions of this class are registered in <code>ehomeConf.config.xml</code> under
 * the names of the {@link Function} constants. Each takes an element of a program (also a part of
 * a module instance); for an element without icon or help it answers <code>null</code>.
 * </p>
 */
public class ProgramResources extends GenericMethod {

	/** The attribute of an element naming its icon. */
	public static final String ICON = "icon";

	/** The attribute of an element naming its help topic. */
	public static final String HELP_TOPIC = "helpTopic";

	private static final String ICON_FILE = "iconFile";

	private static final String HELP_FILES = "helpFiles";

	private static final String ENTRIES = "entries";

	private static final String NAME = "name";

	private static final String CONTENT = "content";

	private static final Parser MARKDOWN = Parser.builder().build();

	private static final HtmlRenderer HTML = HtmlRenderer.builder()
		.escapeHtml(true)
		.sanitizeUrls(true)
		// The default also lets data: through; a help text links to the web or a mail address only.
		.urlSanitizer(new DefaultUrlSanitizer(List.of("http", "https", "mailto")))
		.softbreak("<br>\n")
		.build();

	private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>parameterIcon(element)</code>: the picture of the icon of an element, see
		 * {@link ProgramResources#icon(Object)}.
		 */
		parameterIcon,

		/**
		 * <code>parameterHelp(element)</code>: the help of an element in the reader's language as
		 * HTML, see {@link ProgramResources#help(Object, Locale)}.
		 */
		parameterHelp;
	}

	private final Function _function;

	/**
	 * Creates a {@link ProgramResources}.
	 */
	protected ProgramResources(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	/**
	 * The application program the given element belongs to, <code>null</code> if it belongs to
	 * none.
	 */
	public static TLObject program(Object element) {
		Object template = ModuleParts.template(element);
		for (TLObject obj = template instanceof TLObject e ? e : null; obj != null; obj = obj.tContainer()) {
			if (obj instanceof Program) {
				return obj;
			}
		}
		return null;
	}

	/**
	 * The name of the icon of the given element, <code>null</code> if it has none.
	 */
	public static String iconName(Object element) {
		return text(element, ICON);
	}

	/**
	 * The name of the help topic of the given element, <code>null</code> if it has none.
	 */
	public static String helpTopic(Object element) {
		return text(element, HELP_TOPIC);
	}

	/**
	 * The file of the icon of the given element in the icon archive of its program,
	 * <code>null</code> if the element names no icon or the archive does not hold it.
	 */
	public static TLObject iconEntry(Object element) {
		String name = iconName(element);
		TLObject program = program(element);
		if (name == null || program == null) {
			return null;
		}
		return entry((TLObject) program.tValueByName(ICON_FILE), name);
	}

	/**
	 * The picture of the icon of the given element, <code>null</code> if it has none to show: the
	 * element names no icon, the archive does not hold it, or it is no picture a browser shows.
	 */
	public static BinaryData icon(Object element) {
		TLObject entry = iconEntry(element);
		if (entry == null) {
			return null;
		}
		BinaryData content = (BinaryData) entry.tValueByName(CONTENT);
		String contentType = content == null ? null : content.getContentType();
		if (contentType == null || !contentType.startsWith("image/")
			|| !ParameterPictures.SHOWN.contains(extension((String) entry.tValueByName(NAME)))) {
			return null;
		}
		return content;
	}

	/**
	 * The help text of the given element in the given language, else in the default language of
	 * its program, as the manufacturer wrote it (Markdown); <code>null</code> if it has none.
	 */
	public static String helpText(Object element, Locale locale) {
		String topic = helpTopic(element);
		TLObject program = program(element);
		if (topic == null || program == null) {
			return null;
		}
		return helpText(program, topic, locale);
	}

	/**
	 * The text of the given help topic of the given program in the given language, else in the
	 * program's default language; <code>null</code> if there is none.
	 */
	public static String helpText(TLObject program, String topic, Locale locale) {
		List<TLObject> files = list(program, HELP_FILES);
		String language = locale == null ? null : locale.getLanguage();
		for (TLObject file : files) {
			if (language != null && language.equals(file.tValueByName("language"))) {
				String text = topicText(file, topic);
				if (text != null) {
					return text;
				}
			}
		}
		for (TLObject file : files) {
			if (Boolean.TRUE.equals(file.tValueByName("fallback"))) {
				return topicText(file, topic);
			}
		}
		return null;
	}

	/**
	 * The help of the given element in the given language (else in the default language of its
	 * program) as HTML, <code>null</code> if it has none.
	 */
	public static String help(Object element, Locale locale) {
		String text = helpText(element, locale);
		return text == null ? null : html(text);
	}

	/**
	 * The given help text, written in Markdown, as HTML: raw HTML in it is escaped, a picture is
	 * replaced by its description (it would load a file from anywhere), a link leads to the web or
	 * a mail address only, and every line break is kept, as the manufacturers write a list of
	 * values line by line.
	 */
	public static String html(String markdown) {
		Node document = MARKDOWN.parse(markdown);
		document.accept(new AbstractVisitor() {
			@Override
			public void visit(Image image) {
				StringBuilder description = new StringBuilder();
				for (Node child = image.getFirstChild(); child != null; child = child.getNext()) {
					if (child instanceof Text text) {
						description.append(text.getLiteral());
					}
				}
				image.insertBefore(new Text(description.toString()));
				image.unlink();
			}
		});
		return HTML.render(document).trim();
	}

	/**
	 * The text of the given help topic in the given help file of a program, <code>null</code> if
	 * its archive holds none (or only white space).
	 */
	public static String topicText(TLObject helpFile, String topic) {
		TLObject entry = entry((TLObject) helpFile.tValueByName("file"), topic);
		if (entry == null) {
			return null;
		}
		BinaryData content = (BinaryData) entry.tValueByName(CONTENT);
		if (content == null) {
			return null;
		}
		try (InputStream in = content.getStream()) {
			String text = decode(in.readAllBytes());
			return text.isBlank() ? null : text;
		} catch (IOException ex) {
			Logger.warn("Cannot read the help text '" + topic + "': " + ex.getMessage(), ex, ProgramResources.class);
			return null;
		}
	}

	/**
	 * The file of the given archive named by the given name without extension (as an icon or a
	 * help topic names it): the one of exactly that name, else the one of that name ignoring case;
	 * <code>null</code> if there is none.
	 */
	public static TLObject entry(TLObject archive, String name) {
		if (archive == null || name == null) {
			return null;
		}
		List<TLObject> entries = list(archive, ENTRIES);
		for (TLObject entry : entries) {
			if (name.equals(baseName((String) entry.tValueByName(NAME)))) {
				return entry;
			}
		}
		for (TLObject entry : entries) {
			if (name.equalsIgnoreCase(baseName((String) entry.tValueByName(NAME)))) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * The given bytes of a text as the manufacturer wrote them: UTF-8 (with or without a byte
	 * order mark), else Windows-1252, the encoding of texts written on Windows without one.
	 */
	public static String decode(byte[] bytes) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException ex) {
			text = new String(bytes, WINDOWS_1252);
		}
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	/**
	 * The name of the file of the given name in an archive (behind its last folder) without its
	 * extension.
	 */
	public static String baseName(String entryName) {
		if (entryName == null) {
			return null;
		}
		String file = entryName.substring(entryName.lastIndexOf('/') + 1);
		int dot = file.lastIndexOf('.');
		return dot < 0 ? file : file.substring(0, dot);
	}

	private static String extension(String name) {
		if (name == null) {
			return "";
		}
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static String text(Object element, String attribute) {
		Object template = ModuleParts.template(element);
		if (!(template instanceof TLObject obj) || obj.tType().getPart(attribute) == null) {
			return null;
		}
		Object value = obj.tValueByName(attribute);
		return value == null || value.toString().isBlank() ? null : value.toString().trim();
	}

	@SuppressWarnings("unchecked")
	private static List<TLObject> list(TLObject obj, String reference) {
		Object value = obj.tValueByName(reference);
		return value == null ? List.of() : List.copyOf((Collection<TLObject>) value);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ProgramResources(getName(), _function, arguments);
	}

	@Override
	public Object getId() {
		return _function;
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return true;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		switch (_function) {
			case parameterIcon:
				return icon(arguments[0]);
			case parameterHelp:
				return help(arguments[0], Resources.getInstance().getLocale());
		}
		return null;
	}

	/**
	 * Builder of a {@link ProgramResources} function: the {@link Function} named as the function
	 * is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ProgramResources> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ProgramResources build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkSingleArg(expr, args);
			return new ProgramResources(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
