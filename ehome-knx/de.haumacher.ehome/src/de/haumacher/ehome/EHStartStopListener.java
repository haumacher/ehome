/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved
 */
package de.haumacher.ehome;

import com.top_logic.basic.Version;
import com.top_logic.element.ElementStartStop;

/**
 * Handler for application server to start and stop the Top-Logic context.
 * 
 * @author    <a href="mailto:support@top-logic.com">Top-Logic Support</a>
 */
public class EHStartStopListener extends ElementStartStop {

    /**
     * Creates a {@link EHStartStopListener}.
     */
    public EHStartStopListener() {
        /* needed for ServletContextListener */
    }

    @Override
    protected Version getVersion() {
        return EHVersion.INSTANCE;
    }
}
