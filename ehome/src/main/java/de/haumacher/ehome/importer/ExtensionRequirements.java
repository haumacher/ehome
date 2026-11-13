/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

import de.haumacher.ehome.programming.PluginRequirements;

/**
 * Reads what an application program needs of ETS from the attributes of its
 * <code>Static/Extension</code> element (haumacher/ehome #159) into
 * <code>ehome.operational:Program#requirements</code> of the program being imported (the
 * <code>this</code> of the element), and reports it: a user interface plug-in only as a note, any
 * other requirement as the warning that E-Home cannot download the program (see
 * {@link PluginRequirements}).
 *
 * <p>
 * It reads only the attributes; the <code>Baggage</code> children are read by the handlers after it.
 * A program an earlier import read without its requirements is completed by the next import of its
 * file ({@link #complete(TLObject, Set, BufferingI18NLog)}, from the import's scan of the program file).
 * </p>
 */
public class ExtensionRequirements<C extends ExtensionRequirements.Config<?>> extends ConfiguredImportHandler<C> {

	/** The mark of a program whose requirements are read. */
	static final String REQUIREMENTS_READ = "requirementsRead";

	private static final String EXT_ID = "extId";

	/**
	 * Configuration of {@link ExtensionRequirements}.
	 */
	@TagName("program-requirements")
	public interface Config<I extends ExtensionRequirements<?>> extends ConfiguredImportHandler.Config<I> {
		// No options.
	}

	/**
	 * Creates a {@link ExtensionRequirements} from configuration.
	 */
	@CalledByReflection
	public ExtensionRequirements(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Set<String> names = PluginRequirements.fromExtension(name -> in.getAttributeValue(null, name));
		if (names.isEmpty() || !(context.getVarOrNull(ImportContext.THIS_VAR) instanceof TLObject program)
			|| program.tType().getPart(PluginRequirements.REQUIREMENTS) == null) {
			return null;
		}
		Set<TLClassifier> requirements =
			PluginRequirements.union((Collection<?>) program.tValueByName(PluginRequirements.REQUIREMENTS), names);
		context.setProperty(this, program, PluginRequirements.REQUIREMENTS, requirements);
		Location location = MasterDataUpdates.position(in.getLocation());
		context.log(level(requirements), location, message(program, requirements), null);
		return null;
	}

	/**
	 * Completes a known program an earlier import read without its requirements, with the
	 * requirements its file names, and marks it read.
	 *
	 * @param names
	 *        The names of the requirements of the program in its file (see
	 *        {@link PluginRequirements#fromExtension(java.util.function.UnaryOperator)}).
	 */
	static void complete(TLObject program, Set<String> names, BufferingI18NLog messages) {
		if (Boolean.TRUE.equals(program.tValueByName(REQUIREMENTS_READ))) {
			return;
		}
		if (!names.isEmpty()) {
			Set<TLClassifier> requirements =
				PluginRequirements.union((Collection<?>) program.tValueByName(PluginRequirements.REQUIREMENTS), names);
			program.tUpdateByName(PluginRequirements.REQUIREMENTS, requirements);
			messages.log(level(requirements), message(program, requirements));
		}
		program.tUpdateByName(REQUIREMENTS_READ, Boolean.TRUE);
	}

	private static Level level(Collection<TLClassifier> requirements) {
		return PluginRequirements.refusesDownload(requirements) ? Level.WARN : Level.INFO;
	}

	private static ResKey message(TLObject program, Collection<TLClassifier> requirements) {
		List<TLClassifier> ordered = new ArrayList<>(requirements);
		Object id = program.tValueByName(EXT_ID);
		String name = id == null ? "" : id.toString();
		ResKey names = PluginRequirements.names(ordered);
		return PluginRequirements.refusesDownload(ordered)
			? I18NConstants.WARN_PROGRAM_NOT_DOWNLOADABLE__PROGRAM_REQUIREMENTS.fill(name, names)
			: I18NConstants.INFO_PROGRAM_USES_PLUGIN__PROGRAM_REQUIREMENTS.fill(name, names);
	}

}
