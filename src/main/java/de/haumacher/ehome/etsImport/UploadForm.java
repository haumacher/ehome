/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.io.binary.BinaryData;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public interface UploadForm extends ConfigurationItem {

	BinaryData getProjectData();

}
