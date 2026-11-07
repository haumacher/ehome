/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.Logger;
import com.top_logic.dob.ex.NoSuchAttributeException;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.model.ModelKind;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;

/**
 * The second step of the migration <code>Issue_90_Rule_references</code> (haumacher/ehome #90): the
 * id texts of the references of rules and schedule entries, which the migration's SQL moved to the
 * flex attributes <code>issue90.&lt;attribute&gt;</code>, become the references to the objects of
 * the project they name ({@link RuleReferences#resolve(TLObject, TLObject, String, String, Map)}).
 * An id that names nothing of the project leaves the reference empty and is logged with its rule or
 * entry; the check of the field then shows the controller's refusal. The flex attribute is removed
 * either way.
 *
 * <p>
 * Runs once, in the started application after the migration, in a transaction of the migration
 * service.
 * </p>
 */
public class RuleReferenceMigration implements StartupAction {

	/** The prefix of the flex attributes the migration's SQL moved the id texts to. */
	public static final String PREFIX = "issue90.";

	@Override
	public void perform() {
		for (String unresolved : migrate()) {
			Logger.warn(unresolved, RuleReferenceMigration.class);
		}
	}

	/**
	 * Resolves the moved id texts of every project.
	 *
	 * @return The references that stay empty, one line each.
	 */
	public static List<String> migrate() {
		List<String> unresolved = new ArrayList<>();
		TLClass type = (TLClass) TLModelUtil.findType("ehome.project:Project");
		for (TLObject project : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			migrate(project, unresolved);
		}
		return unresolved;
	}

	/**
	 * Resolves the moved id texts of the rules and schedule entries of the given project.
	 *
	 * @param unresolved
	 *        Where to add the references that stay empty.
	 */
	public static void migrate(TLObject project, List<String> unresolved) {
		Map<String, TLObject> datapoints = RuleReferences.datapoints(project);
		for (TLObject rule : RuleExport.objects(project.tValueByName("rules"))) {
			migrateTree(project, rule, rule, datapoints, unresolved);
		}
		for (TLObject entry : RuleExport.objects(project.tValueByName("schedules"))) {
			migrateTree(project, null, entry, datapoints, unresolved);
		}
	}

	private static void migrateTree(TLObject project, TLObject rule, TLObject obj, Map<String, TLObject> datapoints,
			List<String> unresolved) {
		for (String attribute : RuleReferences.ATTRIBUTES) {
			if (obj.tType().getPart(attribute) instanceof TLReference) {
				migrate(project, rule, obj, attribute, datapoints, unresolved);
			}
		}
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part.getModelKind() == ModelKind.REFERENCE && ((TLReference) part).isComposite()) {
				for (TLObject child : RuleExport.objects(obj.tValue(part))) {
					migrateTree(project, rule, child, datapoints, unresolved);
				}
			}
		}
	}

	private static void migrate(TLObject project, TLObject rule, TLObject obj, String attribute,
			Map<String, TLObject> datapoints, List<String> unresolved) {
		KnowledgeItem item = obj.tHandle();
		Object text;
		try {
			text = item.getAttributeValue(PREFIX + attribute);
		} catch (NoSuchAttributeException ex) {
			return;
		}
		if (text == null) {
			return;
		}
		String id = text.toString();
		TLObject target = RuleReferences.resolve(project, rule, attribute, id, datapoints);
		if (target != null) {
			obj.tUpdateByName(attribute, target);
		} else if (!id.isBlank()) {
			Object owner = rule != null ? rule.tValueByName("identifier") : obj.tValueByName("identifier");
			unresolved.add("Project '" + project.tValueByName("name") + "', "
				+ (rule != null ? "rule '" : "schedule entry '") + owner + "', " + obj.tType().getName() + "#"
				+ attribute + ": '" + id + "' names nothing of the project, the reference stays empty (haumacher/ehome #90).");
		}
		item.setAttributeValue(PREFIX + attribute, null);
	}

}
