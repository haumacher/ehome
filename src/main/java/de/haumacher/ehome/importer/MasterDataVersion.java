/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports the master data of a project file (<code>MasterData</code>) with their version
 * (<code>MasterData@Version</code>, 92 in the ETS 4 samples, 198 in the ETS 6 sample), made
 * available to {@link VersionedMasterData} in the variable {@link #VERSION_VAR}.
 *
 * <p>
 * The variable is <code>null</code> if the master data name no version.
 * </p>
 */
public class MasterDataVersion<C extends MasterDataVersion.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * The variable holding the version of the master data being imported, an {@link Integer} or
	 * <code>null</code>.
	 */
	public static final String VERSION_VAR = "masterDataVersion";

	/**
	 * Configuration of {@link MasterDataVersion}.
	 */
	@TagName("master-data-version")
	public interface Config<I extends MasterDataVersion<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the version.
		 */
		@Name("xml-attribute")
		@StringDefault("Version")
		String getXmlAttribute();

		/**
		 * The handlers importing the master data.
		 */
		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContents();

	}

	private final List<Handler> _contents;

	/**
	 * Creates a {@link MasterDataVersion} from configuration.
	 */
	@CalledByReflection
	public MasterDataVersion(InstantiationContext context, C config) {
		super(context, config);
		_contents = TypedConfiguration.getInstanceList(context, config.getContents());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		return context.withVar(VERSION_VAR, version(in.getAttributeValue(null, getConfig().getXmlAttribute())), in,
			this::importContents);
	}

	private static Integer version(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException ex) {
			// Not a version number, no information.
			return null;
		}
	}

	private Object importContents(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object result = null;
		for (Handler handler : _contents) {
			result = handler.importXml(context, in);
		}
		return result;
	}

}
