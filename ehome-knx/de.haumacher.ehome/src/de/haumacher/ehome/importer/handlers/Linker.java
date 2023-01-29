/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.importer.handlers;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.config.annotation.DefaultContainer;
import com.top_logic.basic.config.annotation.Nullable;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.StringDefault;

import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.binding.ImportContext;
import de.haumacher.ehome.importer.binding.ObjectLinking;
import de.haumacher.ehome.importer.linkings.NoLinking;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class Linker<C extends Linker.Config<?>> extends ConfiguredImportHandler<C> {

	/**
	 * Configuration options for {@link Linker}.
	 */
	@TagName("linking")
	public interface Config<I extends Linker<?>> extends PolymorphicConfiguration<I> {

		@StringDefault(ImportContext.SCOPE_VAR)
		@Nullable
		String getTargetVar();

		@StringDefault(ImportContext.THIS_VAR)
		@Nullable
		String getValueVar();

		@DefaultContainer
		List<PolymorphicConfiguration<ObjectLinking>> getLinkings();

	}

	private List<ObjectLinking> _linkings;

	/**
	 * Creates a {@link Linker} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public Linker(InstantiationContext context, C config) {
		super(context, config);

		List<PolymorphicConfiguration<ObjectLinking>> linkingConfigs = config.getLinkings();
		if (linkingConfigs.isEmpty()) {
			_linkings = Collections.singletonList(NoLinking.INSTANCE);
		} else {
			_linkings = TypedConfiguration.getInstanceList(context, linkingConfigs);
		}
	}

	@Override
	public Object importXml(ImportContext context, XMLStreamReader in) throws XMLStreamException {
		Object target = context.getVar(getConfig().getTargetVar());
		if (target == null) {
			context.error(in.getLocation(), I18NConstants.ERROR_LINKING_NULL_TARGET);
			return null;
		}

		Object value = context.getVar(getConfig().getValueVar());
		if (value == null) {
			context.error(in.getLocation(), I18NConstants.ERROR_LINKING_NULL_VALUE);
			return null;
		}

		Iterator<ObjectLinking> linkings = _linkings.iterator();
		new Runnable() {
			@Override
			public void run() {
				if (linkings.hasNext()) {
					linkings.next().linkOrElse(context, target, value, this);
				} else {
					context.error(in.getLocation(), I18NConstants.ERROR_NO_LINKING);
				}
			}
		}.run();
		return null;
	}

}
