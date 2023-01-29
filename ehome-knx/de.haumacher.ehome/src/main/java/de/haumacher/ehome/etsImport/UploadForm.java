/*
 * Copyright (c) 2018 Business Operation Systems GmbH. All Rights Reserved.
 */
package de.haumacher.ehome.etsImport;

import com.top_logic.basic.config.ConfigurationItem;
import com.top_logic.basic.config.annotation.InstanceFormat;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.layout.form.values.edit.annotation.PropertyEditor;

/**
 * TODO
 *
 * @author <a href="mailto:bhu@top-logic.com">Bernhard Haumacher</a>
 */
public interface UploadForm extends ConfigurationItem {

	// TODO: Should be @Format
	@InstanceFormat
	@PropertyEditor(BinaryDataEditor.class)
	BinaryData getProjectData();

}
