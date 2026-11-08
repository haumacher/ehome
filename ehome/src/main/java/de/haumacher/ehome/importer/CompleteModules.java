/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.logging.Level;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.Handler;
import com.top_logic.xio.importer.handlers.NestedImportHandler;

/**
 * Completes a known application program (the <code>this</code> of an <code>&lt;update&gt;</code> of
 * <code>&lt;unless-known&gt;</code>) that an earlier import left without its dynamic modules
 * (haumacher/ehome #119), if the expansion of the file planned it (see {@link ModuleCompletion});
 * otherwise applies its content handlers (the update of a known program).
 *
 * <p>
 * The completion reads the parts of the expanded modules only, the wrappers
 * {@link ModuleExpansion#WRAPPER} of the file; the rest of the program exists. A wrapper in a
 * section of <code>Static</code> is read with the {@link Config#getStaticParts() static parts}
 * handlers, which create its parameters, references and communication objects in the program. A
 * wrapper in <code>Dynamic</code> is read with the handlers of the contents of the program
 * ({@link Config#getTopLevel()}) or of a channel ({@link Config#getChannelLevel()}) in the structure
 * element its plan names, and its new elements are moved to the position of the module. Then the
 * mark <code>usesModules</code> is removed, and the import reports the completion.
 * </p>
 */
public class CompleteModules<C extends CompleteModules.Config<?>> extends NestedImportHandler<C> {

	private static final String CONTENTS = "contents";

	private static final String STATIC = "Static";

	private static final String USES_MODULES = "usesModules";

	private static final String UNPLACED = "unplaced";

	/**
	 * Configuration of {@link CompleteModules}.
	 */
	@TagName("complete-modules")
	public interface Config<I extends CompleteModules<?>> extends NestedImportHandler.Config<I> {

		/**
		 * The handlers reading a wrapper of static parts (its parameters, unions, parameter
		 * references, communication objects and their references).
		 */
		@Name("static-parts")
		List<PolymorphicConfiguration<? extends Handler>> getStaticParts();

		/**
		 * The handlers reading a wrapper of structure in the program or a case of a switch in it.
		 */
		@Name("top-level")
		List<PolymorphicConfiguration<? extends Handler>> getTopLevel();

		/**
		 * The handlers reading a wrapper of structure in a channel, a block, or a case of a switch in
		 * them.
		 */
		@Name("channel-level")
		List<PolymorphicConfiguration<? extends Handler>> getChannelLevel();

	}

	private final Handler _staticParts;

	private final Handler _topLevel;

	private final Handler _channelLevel;

	/**
	 * Creates a {@link CompleteModules} from configuration.
	 */
	@CalledByReflection
	public CompleteModules(InstantiationContext context, C config) {
		super(context, config);
		_staticParts = buildHandlers(context, config.getStaticParts());
		_topLevel = buildHandlers(context, config.getTopLevel());
		_channelLevel = buildHandlers(context, config.getChannelLevel());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object known = context.getVarOrNull(ImportContext.THIS_VAR);
		ModuleCompletion completion = null;
		if (known instanceof TLObject program && Boolean.TRUE.equals(program.tValueByName(USES_MODULES))) {
			completion = ExpandedModules.get(context).getCompletion(ProgramImported.id(program));
		}
		if (completion == null || completion.getProgram() != known) {
			return importXmlInScope(context, in);
		}
		complete(context, in, completion);
		return null;
	}

	private void complete(ImportContext context, XMLStreamReader in, ModuleCompletion completion)
			throws XMLStreamException {
		TLObject program = completion.getProgram();
		String id = ProgramImported.id(program);
		ProgramLanguages languages = ProgramLanguages.get(context);
		if (languages != null) {
			// The translations of the new parts are set in every language (see TranslationsImport).
			languages.known(id, program);
		}
		Map<TLObject, Integer> inserted = new HashMap<>();
		String section = null;
		int depth = 0;
		while (true) {
			int event = in.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				depth++;
				if (depth == 1) {
					section = in.getLocalName();
				}
				if (ModuleExpansion.WRAPPER.equals(in.getLocalName())) {
					if (STATIC.equals(section)) {
						_staticParts.importXml(context, in);
					} else {
						String sequence = in.getAttributeValue(null, ModuleExpansion.SEQUENCE);
						insert(context, in, completion.target(Integer.parseInt(sequence)), inserted);
					}
					// The handlers consumed the wrapper up to its end tag.
					depth--;
				}
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				if (depth == 0) {
					break;
				}
				depth--;
			} else if (event == XMLStreamConstants.END_DOCUMENT) {
				break;
			}
		}
		addUnplaced(program);
		context.setProperty(this, program, USES_MODULES, Boolean.FALSE);
		HiddenProgramValues.reportPending(context);
		context.log(Level.INFO, in.getLocation(), I18NConstants.INFO_MODULES_COMPLETED__PROGRAM.fill(id));
	}

	/**
	 * Reads the structure of a module into the structure element of its target, and moves it to the
	 * position of the module.
	 */
	private void insert(ImportContext context, XMLStreamReader in, ModuleCompletion.Target target,
			Map<TLObject, Integer> inserted) throws XMLStreamException {
		TLObject fragment = target.fragment();
		int before = contents(fragment).size();
		context.withVar(ImportContext.THIS_VAR, fragment, in, target.topLevel() ? _topLevel : _channelLevel);
		List<Object> contents = contents(fragment);
		int added = contents.size() - before;
		if (added <= 0) {
			return;
		}
		int offset = inserted.getOrDefault(fragment, Integer.valueOf(0)).intValue();
		List<Object> created = new ArrayList<>(contents.subList(before, contents.size()));
		contents.subList(before, contents.size()).clear();
		contents.addAll(target.index() + offset, created);
		fragment.tUpdateByName(CONTENTS, contents);
		inserted.put(fragment, Integer.valueOf(offset + added));
	}

	/**
	 * Adds the new parameter and object references no structure element places to the program's
	 * unplaced references.
	 */
	private static void addUnplaced(TLObject program) {
		List<Object> unplaced = new ArrayList<>((Collection<?>) program.tValueByName(UNPLACED));
		boolean changed = false;
		for (TLObject parameter : objects(program.tValueByName("parameters"))) {
			changed |= addUnplaced(unplaced, parameter.tValueByName("views"));
		}
		for (TLObject port : objects(program.tValueByName("ports"))) {
			changed |= addUnplaced(unplaced, port.tValueByName("interfaces"));
		}
		if (changed) {
			program.tUpdateByName(UNPLACED, unplaced);
		}
	}

	private static boolean addUnplaced(List<Object> unplaced, Object references) {
		boolean result = false;
		for (TLObject reference : objects(references)) {
			if (reference.tContainer() == null) {
				unplaced.add(reference);
				result = true;
			}
		}
		return result;
	}

	private static List<Object> contents(TLObject fragment) {
		return new ArrayList<>((Collection<?>) fragment.tValueByName(CONTENTS));
	}

	private static List<TLObject> objects(Object value) {
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				result.add((TLObject) element);
			}
		}
		return result;
	}

}
