-- A datapoint with a history set before haumacher/ehome #73 keeps it: it becomes one set by hand
-- (historyManual, a boolean flex attribute, DATA_TYPE 1 = true), for every revision in which it had
-- a retention, so the history of the knowledge base tells the same as the current state. Group
-- addresses live in EHGroupAddress, application datapoints in GenericObject; no other type has the
-- attributes historyRetention and historySpacing.
INSERT INTO FLEX_DATA (TYPE, IDENTIFIER, REV_MAX, ATTR, REV_MIN, DATA_TYPE)
	SELECT TYPE, IDENTIFIER, REV_MAX, 'historyManual', REV_MIN, 1
	FROM FLEX_DATA
	WHERE ATTR = 'historyRetention';

-- An incomplete history of a spacing alone (the image's check refuses it) stays as it was, too.
INSERT INTO FLEX_DATA (TYPE, IDENTIFIER, REV_MAX, ATTR, REV_MIN, DATA_TYPE)
	SELECT s.TYPE, s.IDENTIFIER, s.REV_MAX, 'historyManual', s.REV_MIN, 1
	FROM FLEX_DATA s
	WHERE s.ATTR = 'historySpacing'
		AND NOT EXISTS (
			SELECT 1 FROM FLEX_DATA r
			WHERE r.ATTR = 'historyRetention' AND r.TYPE = s.TYPE AND r.IDENTIFIER = s.IDENTIFIER);
