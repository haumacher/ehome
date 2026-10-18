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
import com.top_logic.basic.config.annotation.Mandatory;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.TLObject;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;
import com.top_logic.xio.importer.handlers.Handler;

/**
 * Imports an object of the master data that exists only once and records the version of the master
 * data it comes from (see {@link MasterDataVersion}).
 *
 * <p>
 * An object not known yet is created by the contents and gets the ID the element names. For an
 * object already known (see {@link Importer}), the contents create a transient candidate without an
 * ID, which {@link MasterDataUpdates} compares with the known object at the end of the import: master data of
 * a higher version than the known object's update it in place, other master data keep it and report
 * a warning if they define it differently. References to the ID always resolve to the known object.
 * </p>
 *
 * <p>
 * The contents must create the object anonymously (without <code>id-attribute</code>), and nothing
 * outside the object may refer to its parts: an update replaces them.
 * </p>
 */
public class VersionedMasterData<C extends VersionedMasterData.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration of {@link VersionedMasterData}.
	 */
	@TagName("versioned-master-data")
	public interface Config<I extends VersionedMasterData<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the ID of the element.
		 */
		@Name("id-attribute")
		@Mandatory
		String getIdAttribute();

		/**
		 * The attribute of the object recording the version of the master data it comes from.
		 */
		@Name("version-attribute")
		@Mandatory
		String getVersionAttribute();

		/**
		 * The handlers creating the object.
		 */
		@DefaultContainer
		List<PolymorphicConfiguration<? extends Handler>> getContents();

	}

	private final List<Handler> _contents;

	/**
	 * Creates a {@link VersionedMasterData} from configuration.
	 */
	@CalledByReflection
	public VersionedMasterData(InstantiationContext context, C config) {
		super(context, config);
		_contents = TypedConfiguration.getInstanceList(context, config.getContents());
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, getConfig().getIdAttribute());
		Integer version = (Integer) context.getVarOrNull(MasterDataVersion.VERSION_VAR);
		MasterDataUpdates.Position position = MasterDataUpdates.position(in.getLocation());
		Object known = id == null ? null : context.resolveObjectBackwards(this, in.getLocation(), id);

		Object result = known == null ? importContents(context, in)
			: MasterDataUpdates.get(context).importCandidate(() -> importContents(context, in));
		if (!(result instanceof TLObject imported)) {
			return known != null ? known : result;
		}
		String versionAttribute = getConfig().getVersionAttribute();
		if (known instanceof TLObject knownObject) {
			MasterDataUpdates.get(context).add(knownObject, imported, id, versionAttribute, version, position);
			return knownObject;
		}
		imported.tUpdateByName(versionAttribute, version);
		if (id != null) {
			context.assignId(imported, id);
		}
		return imported;
	}

	private Object importContents(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object result = null;
		for (Handler handler : _contents) {
			result = handler.importXml(context, in);
		}
		return result;
	}

}
