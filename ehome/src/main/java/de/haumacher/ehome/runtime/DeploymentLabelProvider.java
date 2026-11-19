/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.layout.LabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.runtime.Deployment;

/**
 * The label of a deployment (<code>ehome.runtime:Deployment</code>): its revision and time,
 * "Deployment 3 of 18 Nov 2026, 09:00:00", as the field "Deployed again from" names the deployment
 * an entry went back to (haumacher/ehome #192).
 */
public class DeploymentLabelProvider implements LabelProvider {

	@Override
	public String getLabel(Object object) {
		if (!(object instanceof TLObject deployment) || !deployment.tValid()) {
			return null;
		}
		Object revision = ((Deployment) deployment).getRevision();
		return Resources.getInstance().getString(I18NConstants.DEPLOYMENT_LABEL__REVISION_TIME
			.fill(revision == null ? "?" : revision, Deployer.time(((Deployment) deployment).getTime())));

	}

}
