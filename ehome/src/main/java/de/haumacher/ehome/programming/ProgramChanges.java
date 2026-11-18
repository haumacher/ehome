/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.element.meta.kbbased.WrapperMetaAttributeUtil;
import com.top_logic.knowledge.objects.KnowledgeAssociation;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.knowledge.service.event.PendingChanges;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.calculation.DeviceCalculations;
import de.haumacher.ehome.modules.ModuleInstances;

/**
 * Brings a device whose program changed in line with the new program, whichever code changed it (the
 * device form, haumacher/ehome #121): the instances of the new program's dynamic modules are
 * computed, and the R values of its parameter calculations from its L values (haumacher/ehome #123),
 * as for a device created in E-Home.
 *
 * <p>
 * A {@link ModificationListener} of the knowledge base: a device whose program reference the
 * transaction sets (a new link of <code>Device#program</code>) is updated in the
 * {@link Modification} it returns, which runs within the commit. A calculation that cannot be
 * evaluated or fails refuses the commit with its reason, so the program change is rolled back with it.
 * A device created in the transaction is computed by its creator.
 * </p>
 *
 * <p>
 * Registered in <code>WEB-INF/autoconf/ehome.physical.config.xml</code>.
 * </p>
 */
@ServiceDependencies({ PersistencyLayer.Module.class, ModelService.Module.class })
public class ProgramChanges extends ConfiguredManagedClass<ProgramChanges.Config<?>> implements ModificationListener {

	private static final String DEVICE = "ehome.physical:Device";

	private static final String PROGRAM = "program";

	/**
	 * Configuration of {@link ProgramChanges}.
	 */
	public interface Config<I extends ProgramChanges> extends ConfiguredManagedClass.Config<I> {
		// No properties.
	}

	private KnowledgeBase _kb;

	/**
	 * Creates a {@link ProgramChanges} from configuration.
	 */
	public ProgramChanges(InstantiationContext context, Config<?> config) {
		super(context, config);
	}

	@Override
	protected void startUp() {
		super.startUp();
		_kb = PersistencyLayer.getKnowledgeBase();
		_kb.addModificationListener(this);
	}

	@Override
	protected void shutDown() {
		_kb.removeModificationListener(this);
		_kb = null;
		super.shutDown();
	}

	@Override
	public Modification createModification(KnowledgeBase kb, CommitActions actions, PendingChanges changes) {
		// The program of a device changed in this transaction exactly when the transaction creates a
		// link of the reference Device#program (setting the reference replaces its link). A device
		// created in the transaction is computed by its creator.
		KnowledgeItem programPart = TLModelUtil.findPart(DEVICE + "#" + PROGRAM).tHandle();
		List<TLObject> changed = new ArrayList<>();
		for (KnowledgeItem item : changes.getCreated().values()) {
			if (item instanceof KnowledgeAssociation link
				&& programPart.equals(link.getAttributeValue(WrapperMetaAttributeUtil.META_ATTRIBUTE_ATTR))) {
				KnowledgeItem source = link.getSourceObject();
				if (source != null && !changes.getCreated().containsKey(source.tId())
					&& source.getWrapper() instanceof TLObject device && !changed.contains(device)) {
					changed.add(device);
				}
			}
		}
		if (changed.isEmpty()) {
			return Modification.NONE;
		}
		return () -> {
			for (TLObject device : changed) {
				if (device.tValid()) {
					ModuleInstances.update(device);
					DeviceCalculations.recompute(device);
				}
			}
		};
	}

	@Override
	public Modification notifyUpcomingDeletion(KnowledgeBase kb, CommitActions actions, KnowledgeItem item) {
		return Modification.NONE;
	}

	/**
	 * The module of {@link ProgramChanges}.
	 */
	public static final class Module extends TypedRuntimeModule<ProgramChanges> {

		/** The module. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton.
		}

		@Override
		public Class<ProgramChanges> getImplementation() {
			return ProgramChanges.class;
		}
	}

}
