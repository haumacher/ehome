/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.discover;

import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.event.infoservice.InfoService;
import com.top_logic.layout.DisplayContext;
import com.top_logic.layout.basic.fragments.Fragments;
import com.top_logic.layout.form.FormHandler;
import com.top_logic.layout.form.values.edit.EditorFactory;
import com.top_logic.mig.html.layout.LayoutComponent;
import com.top_logic.tool.boundsec.AbstractCommandHandler;
import com.top_logic.tool.boundsec.HandlerResult;
import com.top_logic.util.error.TopLogicException;

import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.knxnetip.Discoverer;
import tuwien.auto.calimero.knxnetip.servicetype.SearchResponse;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public class StartDiscovery extends AbstractCommandHandler {

	/**
	 * Creates a {@link StartDiscovery} from configuration.
	 * 
	 * @param context
	 *        The context for instantiating sub configurations.
	 * @param config
	 *        The configuration.
	 */
	@CalledByReflection
	public StartDiscovery(InstantiationContext context, Config config) {
		super(context, config);
	}

	@Override
	public HandlerResult handleCommand(DisplayContext aContext, LayoutComponent aComponent, Object model,
			Map<String, Object> someArguments) {
		DiscoverSettings formModel = (DiscoverSettings) EditorFactory.getModel((FormHandler) aComponent);
		try {
			Discoverer discoverer =
				new Discoverer(formModel.getLocalhost(), 0, formModel.getUseNat(), formModel.getUseMulticast());
			NetworkInterface networkInterface = formModel.getNetworkInterface();
			if (networkInterface == null) {
				discoverer.startSearch(10, true);
			} else {
				discoverer.startSearch(networkInterface, 10, true);
			}
			SearchResponse[] searchResponses = discoverer.getSearchResponses();
			if (searchResponses.length == 0) {
				InfoService.showWarning(I18NConstants.NO_ENDPOINTS_FOUND);
			}

			Arrays.stream(searchResponses).forEach(r -> InfoService
				.showInfo(Fragments.text("Endpoint: " + r.getControlEndpoint())));
		} catch (KNXException ex) {
			throw new TopLogicException(I18NConstants.DISCOVERY_FAILED__DETAIL.fill(ex.getMessage()));
		} catch (InterruptedException ex) {
			// Ignore.
		}
		return HandlerResult.DEFAULT_RESULT;
	}

}
