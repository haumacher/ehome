/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.StringDefault;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Imports the group addresses a communication object of a device is linked to, as ETS 5.7 and later
 * write them: the attribute <code>Links</code> of a <code>ComObjectInstanceRef</code>, a list of
 * group address IDs separated by spaces, relative to the installation (see {@link DeviceScope}).
 *
 * <p>
 * The first address is the one the object sends to, the others are those it receives from; each
 * becomes a connection (<code>ehome.physical:Connection</code>) of the object, as the
 * <code>Connectors</code> of ETS 4 do.
 * </p>
 */
public class GroupLinks<C extends GroupLinks.Config<?>> extends ConfiguredImportHandler<C> {

	private static final String PROJECT_PREFIX = "P-";

	private static final String CONNECTION = "ehome.physical:Connection";

	private static final String CONNECTION_TYPE = "ehome.physical:ConnectionType";

	private static final String CONNECTIONS = "connections";

	private static final String TARGET = "target";

	private static final String KIND = "kind";

	/**
	 * Configuration of {@link GroupLinks}.
	 */
	@TagName("group-links")
	public interface Config<I extends GroupLinks<?>> extends ConfiguredImportHandler.Config<I> {

		/**
		 * The XML attribute holding the links.
		 */
		@Name("xml-attribute")
		@StringDefault("Links")
		String getXmlAttribute();

	}

	/**
	 * Creates a {@link GroupLinks} from configuration.
	 */
	@CalledByReflection
	public GroupLinks(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String links = in.getAttributeValue(null, getConfig().getXmlAttribute());
		if (links == null || links.isBlank()) {
			return null;
		}
		Object connector = context.getVar(ImportContext.THIS_VAR);
		Location location = in.getLocation();
		TLEnumeration kinds = (TLEnumeration) TLModelUtil.findType(CONNECTION_TYPE);
		boolean first = true;
		for (String link : links.trim().split("\\s+")) {
			String id = DeviceScope.absolute(context, DeviceScope.INSTALLATION_VAR, PROJECT_PREFIX, link);
			Object connection = context.createObject(this, location, CONNECTION, null);
			context.setReference(this, connection, TARGET, context.resolveObject(this, location, id));
			context.setReference(this, connection, KIND, kinds.getClassifier(first ? "send" : "receive"));
			context.addValue(this, connector, CONNECTIONS, connection);
			first = false;
		}
		return null;
	}

}
