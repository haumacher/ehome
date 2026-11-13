-- The number of a channel becomes a text in the channel's own notation (haumacher/ehome #162): the
-- stored integers (DATA_TYPE 11 = integer, 10 = long, in LONG_DATA) become their decimal text
-- (DATA_TYPE 30 = string, in VARCHAR_DATA), in every revision. Channels live in EHConfigFragment,
-- together with cases, channel independent parts and parameter blocks, none of which has an
-- attribute number; so the table and the attribute name the numbers of channels exactly.
UPDATE FLEX_DATA SET VARCHAR_DATA = CAST(LONG_DATA AS VARCHAR), LONG_DATA = NULL, DATA_TYPE = 30
	WHERE TYPE = 'EHConfigFragment'
		AND ATTR = 'number'
		AND DATA_TYPE IN (10, 11);
