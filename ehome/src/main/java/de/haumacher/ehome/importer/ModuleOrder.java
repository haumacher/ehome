/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.xio.importer.binding.ImportContext;
import com.top_logic.xio.importer.handlers.ConfiguredImportHandler;

/**
 * Sets the position of the current <code>Module</code> or <code>Repeat</code> element in its document
 * (ETS: XmlOrder, see {@link ModuleScan#getOrder(String)}) as the <code>order</code> of the placement
 * or repeat it creates (haumacher/ehome #119).
 *
 * <p>
 * It reads nothing of the element.
 * </p>
 */
public class ModuleOrder<C extends ModuleOrder.Config<?>> extends ConfiguredImportHandler<C> {

	private static final String ORDER = "order";

	/**
	 * Configuration of {@link ModuleOrder}.
	 */
	@TagName("module-order")
	public interface Config<I extends ModuleOrder<?>> extends ConfiguredImportHandler.Config<I> {
		// No options.
	}

	/**
	 * Creates a {@link ModuleOrder} from configuration.
	 */
	@CalledByReflection
	public ModuleOrder(InstantiationContext context, C config) {
		super(context, config);
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		String id = in.getAttributeValue(null, "Id");
		Integer order = id == null ? null : ModuleScan.get(context).getOrder(id);
		if (order != null) {
			context.setProperty(this, context.getVar(ImportContext.THIS_VAR), ORDER, order);
		}
		return null;
	}

}
