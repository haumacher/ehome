/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai;

import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.tl.ai.core.agent.SecurityContext;
import com.top_logic.tl.ai.core.security.DefaultSecurityContextFactory;
import com.top_logic.tl.ai.core.security.ModelAccessRightsSecurity;
import com.top_logic.tl.ai.core.security.ModelSecurity;
import com.top_logic.tl.ai.core.security.RunPrincipal;
import com.top_logic.tl.ai.core.security.SecurityContextFactory;
import com.top_logic.tl.ai.core.security.SecurityContextProxy;

/**
 * The security context of the assistant's runs: the shipped one, except that a part created in an
 * object the same turn created is not refused by the pre-commit guard (doc/tl-todo.md, "AI modules").
 *
 * <p>
 * <b>The engine gap.</b> The guard of a turn (<code>ModelSecurityPreCommitGuard.checkCreation</code>)
 * asks <code>canCreate(type, container)</code> in the committed view. A container created in the same
 * turn is invalid there, and <code>SecurityConfigurationService.isAllowedCreate</code> decides an
 * invalid context as deleted ("nobody may create an object in a deleted context") before it reaches its
 * own rule for a context built in the current transaction (allowed: its own creation is checked). So a
 * turn that creates an object with parts is rolled back: a floor with rooms, a device's connector with
 * its connection (the first connection of a communication object), a duplicate device with its
 * settings.
 * </p>
 *
 * <p>
 * <b>The workaround.</b> The two creation questions are answered as the engine's rule intends for a
 * container in state <code>NEW</code> (created in the current, uncommitted transaction): allowed, since
 * the container's own creation is a creation of the same change set, which the guard checks against
 * its container up to the first committed one. Every other question, and every question about a
 * committed or deleted container, is the shipped answer. When the guard is fixed, this factory and its
 * configuration in <code>ehome.ai.config.xml</code> are removed.
 * </p>
 */
public class NewPartsSecurityContextFactory implements SecurityContextFactory {

	@Override
	public SecurityContext createSecurityContext(RunPrincipal run) {
		return new Context(DefaultSecurityContextFactory.defaultContext(run), new Rights(run.account()));
	}

	/**
	 * Whether the given object was created in the current, uncommitted transaction.
	 */
	static boolean isNew(TLObject obj) {
		return obj != null && !obj.tTransient() && obj.tHandle() != null
			&& obj.tHandle().getState() == KnowledgeItem.State.NEW;
	}

	private static final class Context extends SecurityContextProxy {

		private final ModelSecurity _rights;

		Context(SecurityContext impl, ModelSecurity rights) {
			super(impl);
			_rights = rights;
		}

		@Override
		protected SecurityContext copy(SecurityContext narrowed) {
			return new Context(narrowed, _rights);
		}

		@Override
		public ModelSecurity modelSecurity() {
			return _rights;
		}
	}

	private static final class Rights extends ModelAccessRightsSecurity {

		Rights(Person person) {
			super(person);
		}

		@Override
		public boolean canCreate(TLClass type, TLObject context) {
			return isNew(context) || super.canCreate(type, context);
		}

		@Override
		public boolean canCreate(TLObject parent, TLStructuredTypePart compositionAttribute, TLClass type) {
			return isNew(parent) || super.canCreate(parent, compositionAttribute, type);
		}
	}

}
