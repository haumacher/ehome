-- The id texts of the properties of rules and schedule entries that become references (haumacher/ehome
-- #90) move to the flex attributes issue90.<attribute>, in every revision, so that deleting the
-- properties does not delete them; the startup action RuleReferenceMigration resolves them. The
-- objects of these types live in GenericObject; the restriction to the types keeps the attributes of
-- the same name of other types (the parameter of a block, BlockParameter#parameter) as they are.
UPDATE FLEX_DATA SET ATTR = 'issue90.' || ATTR
	WHERE TYPE = 'GenericObject'
		AND ATTR = 'datapoint'
		AND IDENTIFIER IN (
			SELECT g.IDENTIFIER FROM GENERIC_OBJECT g
				JOIN META_ELEMENT t ON t.IDENTIFIER = g.T_TYPE_ID
				JOIN TL_MODULE m ON m.IDENTIFIER = t.MODULE_ID
			WHERE m.NAME = 'ehome.runtime'
				AND t.NAME IN ('DatapointTrigger', 'SilenceTrigger', 'WriteAction', 'ReadAction'));

UPDATE FLEX_DATA SET ATTR = 'issue90.' || ATTR
	WHERE TYPE = 'GenericObject'
		AND ATTR = 'schedule'
		AND IDENTIFIER IN (
			SELECT g.IDENTIFIER FROM GENERIC_OBJECT g
				JOIN META_ELEMENT t ON t.IDENTIFIER = g.T_TYPE_ID
				JOIN TL_MODULE m ON m.IDENTIFIER = t.MODULE_ID
			WHERE m.NAME = 'ehome.runtime'
				AND t.NAME = 'ScheduleTrigger');

UPDATE FLEX_DATA SET ATTR = 'issue90.' || ATTR
	WHERE TYPE = 'GenericObject'
		AND ATTR = 'timer'
		AND IDENTIFIER IN (
			SELECT g.IDENTIFIER FROM GENERIC_OBJECT g
				JOIN META_ELEMENT t ON t.IDENTIFIER = g.T_TYPE_ID
				JOIN TL_MODULE m ON m.IDENTIFIER = t.MODULE_ID
			WHERE m.NAME = 'ehome.runtime'
				AND t.NAME IN ('TimerTrigger', 'StartTimerAction', 'RestartTimerAction', 'CancelTimerAction'));

UPDATE FLEX_DATA SET ATTR = 'issue90.' || ATTR
	WHERE TYPE = 'GenericObject'
		AND ATTR = 'parameter'
		AND IDENTIFIER IN (
			SELECT g.IDENTIFIER FROM GENERIC_OBJECT g
				JOIN META_ELEMENT t ON t.IDENTIFIER = g.T_TYPE_ID
				JOIN TL_MODULE m ON m.IDENTIFIER = t.MODULE_ID
			WHERE m.NAME = 'ehome.runtime'
				AND t.NAME IN ('ParameterTrigger', 'SetParameterAction'));

UPDATE FLEX_DATA SET ATTR = 'issue90.' || ATTR
	WHERE TYPE = 'GenericObject'
		AND ATTR = 'output'
		AND IDENTIFIER IN (
			SELECT g.IDENTIFIER FROM GENERIC_OBJECT g
				JOIN META_ELEMENT t ON t.IDENTIFIER = g.T_TYPE_ID
				JOIN TL_MODULE m ON m.IDENTIFIER = t.MODULE_ID
			WHERE m.NAME = 'ehome.runtime'
				AND t.NAME IN ('WeeklySchedule', 'YearlySchedule', 'DatesSchedule', 'AstroSchedule', 'IntervalSchedule',
					'OnceSchedule'));
