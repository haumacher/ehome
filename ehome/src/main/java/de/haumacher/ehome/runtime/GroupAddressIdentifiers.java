/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.module.ConfiguredManagedClass;
import com.top_logic.basic.module.ServiceDependencies;
import com.top_logic.basic.module.TypedRuntimeModule;
import com.top_logic.dob.identifier.ObjectKey;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.CommitActions;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.event.Modification;
import com.top_logic.knowledge.service.event.ModificationListener;
import com.top_logic.knowledge.service.event.PendingChanges;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.logical.GroupAddress;

/**
 * Gives every group address its identifier (haumacher/ehome #178): an address created without one
 * gets the one {@link GroupAddressIds#proposal(TLObject, java.util.Collection, Set)} gives it, in the
 * commit creating it, whichever code created it (the ETS import and an update from ETS, the form
 * "New group address", the derivation of the installation, a project file, an agent through the
 * model). An address whose identifier is removed other than by its form (which refuses that) gets
 * one again the same way.
 *
 * <p>
 * A {@link ModificationListener} of the knowledge base, as {@link StateDatapoints} gives every rule
 * its state datapoint: one mechanism for every path that creates an address, instead of a call in
 * each. It runs after {@link IdRenames} (it depends on it, so it is registered after it): when an
 * identifier is removed, {@link IdRenames} rewrites the rules naming it to the address
 * (<code>@"1/2/3"</code>) and this listener on to the new identifier.
 * </p>
 *
 * <p>
 * Registered in <code>WEB-INF/autoconf/ehome.runtime.config.xml</code>.
 * </p>
 */
@ServiceDependencies({ PersistencyLayer.Module.class, ModelService.Module.class, IdRenames.Module.class })
public class GroupAddressIdentifiers extends ConfiguredManagedClass<GroupAddressIdentifiers.Config<?>>
		implements ModificationListener {

	/**
	 * Configuration of {@link GroupAddressIdentifiers}.
	 */
	public interface Config<I extends GroupAddressIdentifiers> extends ConfiguredManagedClass.Config<I> {
		// No properties.
	}

	private KnowledgeBase _kb;

	/**
	 * Creates a {@link GroupAddressIdentifiers} from configuration.
	 */
	public GroupAddressIdentifiers(InstantiationContext context, Config<?> config) {
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
		Map<TLObject, List<TLObject>> missing = null;
		for (Map<ObjectKey, ? extends KnowledgeItem> objects : List.of(changes.getCreated(), changes.getUpdated())) {
			for (KnowledgeItem item : objects.values()) {
				if (!(item.getWrapper() instanceof TLObject obj) || !obj.tValid() || obj.tTransient()
					|| !Pages.isGroupAddress(obj) || GroupAddressIds.identifier(obj) != null) {
					continue;
				}
				TLObject project = RuntimeFunctions.project(obj);
				if (project == null) {
					continue;
				}
				if (missing == null) {
					missing = new LinkedHashMap<>();
				}
				missing.computeIfAbsent(project, x -> new ArrayList<>()).add(obj);
			}
		}
		if (missing == null) {
			return Modification.NONE;
		}
		Map<TLObject, List<TLObject>> all = missing;
		return () -> all.forEach(GroupAddressIdentifiers::assign);
	}

	/**
	 * Gives the addresses of the project that have no identifier one, unique in the project; the
	 * rules naming them by their address follow. The caller provides the transaction.
	 */
	public static void assign(TLObject project, List<TLObject> addresses) {
		List<TLObject> lacking = new ArrayList<>(addresses);
		lacking.removeIf(address -> !address.tValid() || GroupAddressIds.identifier(address) != null);
		lacking.sort(RuntimeFunctions.BY_ADDRESS);
		Map<TLObject, String> identifiers = GroupAddressIds.proposal(project, lacking, GroupAddressIds.taken(project));
		ExpressionRenames renames = new ExpressionRenames();
		for (Map.Entry<TLObject, String> entry : identifiers.entrySet()) {
			TLObject address = entry.getKey();
			String before = GroupAddressIds.address(address);
			((GroupAddress) address).setIdentifier(entry.getValue());

			if (before != null) {
				renames.datapoint(before, entry.getValue());
			}
		}
		if (!renames.isEmpty()) {
			renames.apply(project);
		}
	}

	@Override
	public Modification notifyUpcomingDeletion(KnowledgeBase kb, CommitActions actions, KnowledgeItem item) {
		return Modification.NONE;
	}

	/**
	 * The module of {@link GroupAddressIdentifiers}.
	 */
	public static final class Module extends TypedRuntimeModule<GroupAddressIdentifiers> {

		/** The module. */
		public static final Module INSTANCE = new Module();

		private Module() {
			// Singleton.
		}

		@Override
		public Class<GroupAddressIdentifiers> getImplementation() {
			return GroupAddressIdentifiers.class;
		}
	}

}
