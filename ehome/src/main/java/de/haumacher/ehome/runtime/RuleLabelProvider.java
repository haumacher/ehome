/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.LabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * {@link LabelProvider} for the parts of a time program (haumacher/ehome #140) that the default
 * labels name badly: the datapoint of a rule's state as every datapoint is named
 * ({@link DatapointNames#name(TLObject)}: <code>Heizprogramm: Zustand (rule/heating/state)</code>),
 * a state of a rule and a day plan by their label and id (<code>Komfort (comfort)</code>), the id
 * alone without a label.
 *
 * <p>
 * Registered for <code>ehome.runtime:RuleStateDatapoint</code>, <code>ehome.runtime:RuleState</code>
 * and <code>ehome.runtime:TimelinePlan</code> in the <code>LabelProviderService</code>, so choices,
 * table cells and the detail panels show the same text.
 * </p>
 */
public class RuleLabelProvider implements LabelProvider {

	@Override
	public String getLabel(Object object) {
		if (!(object instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		if (obj.tType().getPart("identifier") == null) {
			return DatapointNames.name(obj);
		}
		Object id = obj.tValueByName("identifier");
		Object label = obj.tValueByName("label");
		String text = label instanceof ResKey key ? Resources.getInstance().getString(key, null) : null;
		return DatapointNames.format(text == null || text.isBlank() ? null : text.trim(),
			id == null ? null : id.toString());
	}

}
