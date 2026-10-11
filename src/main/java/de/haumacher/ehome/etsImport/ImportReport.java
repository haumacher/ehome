/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.etsImport;

import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.FILE;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.LEVEL;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.MESSAGE;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.MESSAGES;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.PROJECT;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.SEVERITY;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.SEVERITY_ERROR;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.SEVERITY_INFO;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.SEVERITY_WARNING;
import static de.haumacher.ehome.etsImport.ImportEtsProjectAction.SUMMARY;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

/**
 * The outcome of the import of uploaded files, see {@link #toMap()}.
 *
 * <p>
 * Shared by the import of project files ({@link ImportEtsProjectAction}) and of product files
 * ({@link ImportProductDataAction}).
 * </p>
 */
final class ImportReport {

	private final Resources _resources = Resources.getInstance();

	private final ResKey2 _summary;

	private TLObject _project;

	private TLObject _manufacturer;

	private final List<String> _imported = new ArrayList<>();

	private final List<Map<String, Object>> _messages = new ArrayList<>();

	/**
	 * Creates a {@link ImportReport}.
	 *
	 * @param summary
	 *        The sentence summarizing the import, filled with the descriptions of the imported files
	 *        (separated by commas) and the number of messages.
	 */
	ImportReport(ResKey2 summary) {
		_summary = summary;
	}

	/**
	 * Records the import of a project file that created the given project.
	 */
	void addProject(String fileName, TLObject project, List<BufferingI18NLog.Entry> messages) {
		String file = fileName(fileName);
		if (project != null) {
			_project = project;
			_imported.add(MetaLabelProvider.INSTANCE.getLabel(project));
		} else {
			_imported.add(file);
		}
		addMessages(file, messages);
	}

	/**
	 * Records the import of a product file of the given manufacturer, described by the given text.
	 */
	void addProductData(String fileName, TLObject manufacturer, String description,
			List<BufferingI18NLog.Entry> messages) {
		String file = fileName(fileName);
		if (manufacturer != null) {
			_manufacturer = manufacturer;
		}
		_imported.add(description);
		addMessages(file, messages);
	}

	private static String fileName(String fileName) {
		return fileName == null ? "" : fileName;
	}

	private void addMessages(String file, List<BufferingI18NLog.Entry> messages) {
		for (BufferingI18NLog.Entry message : messages) {
			_messages.add(Map.of(
				FILE, file,
				SEVERITY, severity(message.getLevel()),
				LEVEL, _resources.getString(levelLabel(message.getLevel())),
				MESSAGE, _resources.getString(message.getMessage())));
		}
	}

	/**
	 * The report as a dictionary for the action chain: {@value ImportEtsProjectAction#PROJECT} the
	 * project created last, {@value ImportProductDataAction#MANUFACTURER} the manufacturer of the
	 * product file imported last, {@value ImportEtsProjectAction#SUMMARY} a sentence naming what was
	 * imported and how many messages there are, and {@value ImportEtsProjectAction#MESSAGES} the
	 * messages of the import as dictionaries with {@value ImportEtsProjectAction#FILE},
	 * {@value ImportEtsProjectAction#SEVERITY}, {@value ImportEtsProjectAction#LEVEL} and
	 * {@value ImportEtsProjectAction#MESSAGE} (texts in the user's language).
	 */
	Map<String, Object> toMap() {
		Map<String, Object> result = new HashMap<>();
		result.put(PROJECT, _project);
		result.put(ImportProductDataAction.MANUFACTURER, _manufacturer);
		result.put(SUMMARY, _resources.getString(_summary.fill(String.join(", ", _imported), _messages.size())));
		result.put(MESSAGES, _messages);
		return result;
	}

	private static String severity(Level level) {
		if (level.is(Level.ERROR)) {
			return SEVERITY_ERROR;
		} else if (level.is(Level.WARN)) {
			return SEVERITY_WARNING;
		} else {
			return SEVERITY_INFO;
		}
	}

	private static ResKey levelLabel(Level level) {
		if (level.is(Level.ERROR)) {
			return I18NConstants.LEVEL_ERROR;
		} else if (level.is(Level.WARN)) {
			return I18NConstants.LEVEL_WARNING;
		} else {
			return I18NConstants.LEVEL_INFO;
		}
	}

}
