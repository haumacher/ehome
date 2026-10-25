-- The singleton entry ROOT of ehome.mask becomes the one of ehome.operational (TL #29806).
UPDATE TL_MODULE_SINGLETONS
	SET MODULE_ID = (SELECT IDENTIFIER FROM TL_MODULE WHERE NAME = 'ehome.operational')
	WHERE MODULE_ID = (SELECT IDENTIFIER FROM TL_MODULE WHERE NAME = 'ehome.mask');

-- The association of the reference MaskVersions#masks moves with its class. update-class should do
-- this, but looks the associations up by the qualified name of the class (ehome.mask:MaskVersions$)
-- instead of its local name, so it moves none of them (8.0.0-alpha11).
UPDATE META_ELEMENT
	SET MODULE_ID = (SELECT IDENTIFIER FROM TL_MODULE WHERE NAME = 'ehome.operational'),
		SCOPE_ID = (SELECT IDENTIFIER FROM TL_MODULE WHERE NAME = 'ehome.operational')
	WHERE NAME = 'MaskVersions$masks'
		AND MODULE_ID = (SELECT IDENTIFIER FROM TL_MODULE WHERE NAME = 'ehome.mask');
