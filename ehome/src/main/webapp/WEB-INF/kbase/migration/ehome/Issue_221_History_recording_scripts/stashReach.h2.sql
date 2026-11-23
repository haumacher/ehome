-- The reach of the histories set by hand (haumacher/ehome #221): the longest retention of the raw ring
-- and the archives of a datapoint whose historyManual is true (DATA_TYPE 1), in the current state, as
-- the integer flex attribute issue221.reach (DATA_TYPE 11) of the datapoint; 0 for a history set by
-- hand without a raw ring, which the image left out. The startup action RecordingMigration turns it
-- into the recording and removes it. Group addresses live in EHGroupAddress, application datapoints
-- in GenericObject; the archives are linked through HAS_STRUCTURE_CHILD (the composite reference
-- historyArchives) and hold their retention as a flex attribute.
INSERT INTO FLEX_DATA (TYPE, IDENTIFIER, REV_MAX, ATTR, REV_MIN, DATA_TYPE, LONG_DATA)
	SELECT m.TYPE, m.IDENTIFIER, m.REV_MAX, 'issue221.reach', m.REV_MIN, 11,
		CASE WHEN r.LONG_DATA IS NULL THEN 0 ELSE GREATEST(r.LONG_DATA, COALESCE((
			SELECT MAX(a.LONG_DATA) FROM HAS_STRUCTURE_CHILD l
				JOIN META_ATTRIBUTE p ON p.IDENTIFIER = l.META_ATTRIBUTE_ID
				JOIN FLEX_DATA a ON a.TYPE = l.DEST_TYPE AND a.IDENTIFIER = l.DEST_ID
			WHERE l.SOURCE_TYPE = m.TYPE AND l.SOURCE_ID = m.IDENTIFIER
				AND l.REV_MAX = 9223372036854775807
				AND p.NAME = 'historyArchives'
				AND a.ATTR = 'retention' AND a.REV_MAX = 9223372036854775807), 0)) END
	FROM FLEX_DATA m
		LEFT JOIN FLEX_DATA r ON r.TYPE = m.TYPE AND r.IDENTIFIER = m.IDENTIFIER
			AND r.ATTR = 'historyRetention' AND r.REV_MAX = 9223372036854775807
	WHERE m.ATTR = 'historyManual' AND m.DATA_TYPE = 1 AND m.REV_MAX = 9223372036854775807
		AND m.TYPE IN ('EHGroupAddress', 'GenericObject');
