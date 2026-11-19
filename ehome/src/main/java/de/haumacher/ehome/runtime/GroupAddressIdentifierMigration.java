/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import com.top_logic.basic.Logger;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;

/**
 * The migration <code>Issue_178_Group_address_identifiers</code> (haumacher/ehome #178): every group
 * address of a database from before that has no identifier gets the one
 * {@link GroupAddressIds#propose(TLObject)} proposes, unique in its project, so that afterwards no
 * datapoint has its address (<code>1/2/3</code>) as its id. The rules naming such an address by its
 * address follow in the same transaction ({@link IdRenames}, a listener of the knowledge base, which
 * runs in the started application). Logs the count per project.
 *
 * <p>
 * Runs once, in the started application after the migration, in a transaction of the migration
 * service. It reads every project once; afterwards each address gets its identifier when it is
 * created ({@link GroupAddressIdentifiers}).
 * </p>
 */
public class GroupAddressIdentifierMigration implements StartupAction {

	@Override
	public void perform() {
		int total = 0;
		TLClass type = (TLClass) TLModelUtil.findType("ehome.project:Project");
		for (TLObject project : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			total += migrate(project);
		}
		Logger.info("Group addresses given an identifier (haumacher/ehome #178): " + total + ".",
			GroupAddressIdentifierMigration.class);
	}

	/**
	 * Gives the group addresses of the project without an identifier the proposed ones; the caller
	 * provides the transaction.
	 *
	 * @return The number of addresses that got an identifier.
	 */
	public static int migrate(TLObject project) {
		Map<TLObject, String> proposal = GroupAddressIds.propose(project);
		for (Map.Entry<TLObject, String> entry : proposal.entrySet()) {
			((GroupAddress) entry.getKey()).setIdentifier(entry.getValue());
		}
		if (!proposal.isEmpty()) {
			Logger.info("Project '" + ((Project) project).getName() + "': " + proposal.size()

				+ " group addresses given an identifier (haumacher/ehome #178).", GroupAddressIdentifierMigration.class);
		}
		return proposal.size();
	}

}
