/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.HashMap;
import java.util.Map;

import de.haumacher.ehome.blocks.SunShading;

/**
 * The German texts of the sun shading's manifest (homecontrol <code>spec/blocks.md</code> 18.1), by
 * the id of the port, parameter or choice (see {@link RoomControllerTexts}), and the help texts of the ports and parameters in German and
 * English.
 */
public final class SunShadingTexts {

	/** The type id. */
	public static final String TYPE = SunShading.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Beschattung";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("brightness", "Helligkeit"),
		Map.entry("wind", "Windgeschwindigkeit"),
		Map.entry("rain", "Regen"),
		Map.entry("frost", "Frostalarm"),
		Map.entry("outside", "Außentemperatur"),
		Map.entry("manual", "Handbedienung"),
		Map.entry("presence", "Anwesenheit"),
		Map.entry("summer", "Sommer"),
		Map.entry("roomTemperature", "Raumtemperatur"),
		Map.entry("enable", "Automatik ein"),
		Map.entry("position", "Behangposition"),
		Map.entry("slat", "Lamellenposition"),
		Map.entry("upDown", "Auf/Ab"),
		Map.entry("state", "Zustand"),
		Map.entry("sunAzimuth", "Sonnenazimut"),
		Map.entry("sunElevation", "Sonnenhöhe"),
		Map.entry("facadeAzimuth", "Ausrichtung der Fassade"),
		Map.entry("openingAngle", "Öffnungswinkel"),
		Map.entry("minElevation", "Kleinste Sonnenhöhe"),
		Map.entry("overhangAngle", "Winkel des Vordachs"),
		Map.entry("horizonPoints", "Anzahl der Horizontpunkte"),
		Map.entry("horizonAngle1", "Horizontrichtung 1"),
		Map.entry("horizonElevation1", "Horizonthöhe 1"),
		Map.entry("horizonAngle2", "Horizontrichtung 2"),
		Map.entry("horizonElevation2", "Horizonthöhe 2"),
		Map.entry("horizonAngle3", "Horizontrichtung 3"),
		Map.entry("horizonElevation3", "Horizonthöhe 3"),
		Map.entry("horizonAngle4", "Horizontrichtung 4"),
		Map.entry("horizonElevation4", "Horizonthöhe 4"),
		Map.entry("brightnessOn", "Hell ab"),
		Map.entry("brightnessOff", "Dunkel unter"),
		Map.entry("brightnessOnDelay", "Verzögerung hell"),
		Map.entry("brightnessOffDelay", "Verzögerung dunkel"),
		Map.entry("shadePosition", "Beschattungsposition"),
		Map.entry("slatMode", "Lamellen beim Beschatten"),
		Map.entry("slatMode.fixed", "Fest"),
		Map.entry("slatMode.tracking", "Dem Sonnenstand nachgeführt"),
		Map.entry("shadeSlat", "Lamellenposition beim Beschatten"),
		Map.entry("slatRatio", "Lamellenabstand zu Lamellenbreite"),
		Map.entry("slatOpen", "Lamellenposition waagerecht"),
		Map.entry("slatStep", "Lamellenschritt"),
		Map.entry("heatLimit", "Hitzeschutz oberhalb"),
		Map.entry("windLimit", "Windalarm ab"),
		Map.entry("windTimeout", "Überwachungszeit des Windsensors"),
		Map.entry("frostLimit", "Frostalarm unterhalb"),
		Map.entry("alarmRelease", "Rückfallzeit nach Alarm"),
		Map.entry("manualLock", "Nach Handbedienung"),
		Map.entry("manualLock.duration", "Für eine feste Dauer"),
		Map.entry("manualLock.sunChange", "Bis sich die Sonne ändert"),
		Map.entry("manualLock.midnight", "Bis Mitternacht"),
		Map.entry("manualDuration", "Sperrzeit nach Handbedienung"),
		Map.entry("minMoveInterval", "Kürzeste Zeit zwischen zwei Fahrten"));

	/** The defaults of the horizon points' directions, as in the manifest. */
	private static final int[] HORIZON_ANGLE = { -60, -20, 20, 60 };

	/**
	 * The German help texts of the ports and parameters by id (haumacher/ehome #101): what the member
	 * does, for the installer. The choices of an enumeration each in a paragraph of their own.
	 */
	public static final Map<String, String> HELP_DE = helpDe();

	/** The English help texts of the ports and parameters by id, as {@link #HELP_DE}. */
	public static final Map<String, String> HELP_EN = helpEn();

	private static Map<String, String> helpDe() {
		Map<String, String> result = new HashMap<>(Map.ofEntries(
			Map.entry("brightness", "Die Helligkeit an der Fassade in Lux, bis zu 4 Fühler (größter Wert oder Mittelwert), z. B. vom Helligkeitssensor der Wetterstation auf dieser Seite. "
				+ "Hell ist es ab „Hell ab“ nach „Verzögerung hell“, dunkel unter „Dunkel unter“ nach „Verzögerung dunkel“; ein ungültiger Wert zählt als dunkel. "
				+ "Ungebunden gilt es immer als hell: Dann beschattet allein der Sonnenstand."),
			Map.entry("wind", "Die Windgeschwindigkeit in m/s, bis zu 4 Fühler. Erreicht einer „Windalarm ab“, fährt der Behang sofort hoch (Windalarm) "
				+ "und bleibt oben, bis der Wind „Rückfallzeit nach Alarm“ lang darunter war. Mit „Überwachungszeit des Windsensors“ löst auch ein stummer Fühler den Alarm aus."),
			Map.entry("rain", "Regenmelder, bis zu 4; meldet einer Regen, fährt der Behang sofort hoch (Regenalarm), z. B. für Markisen oder Raffstores, "
				+ "die nicht nass werden sollen. Der Alarm endet „Rückfallzeit nach Alarm“ nach dem Ende des Regens."),
			Map.entry("frost", "Ein Frostalarm von außen, z. B. von der Wetterstation; ein (1): Der Behang fährt hoch und wird nicht mehr bewegt, damit er nicht festfriert. "
				+ "Wirkt zusätzlich zu „Frostalarm unterhalb“ an „Außentemperatur“."),
			Map.entry("outside", "Die Außentemperatur; liegt sie unter „Frostalarm unterhalb“, gilt Frostalarm (der Behang fährt hoch). Ungebunden: nur „Frostalarm“ zählt."),
			Map.entry("manual", "Die Gruppenadressen, über die der Behang von Hand bedient wird, bis zu 8, z. B. Auf/Ab und Stopp der Taster am Fenster. "
				+ "Jedes Telegramm dort zählt als Bedienung, auch eines mit unverändertem Wert: Die Automatik pausiert dann, wie „Nach Handbedienung“ sagt. "
				+ "Während eines Alarms fährt der Behang nach einer Bedienung wieder hoch."),
			Map.entry("presence", "Ob jemand zu Hause ist, z. B. vom Ausgang „Bewohnt“ eines Hausmodus. Nur für den Hitzeschutz: "
				+ "Er greift nur, wenn abwesend (0) gemeldet ist. Ungebunden: kein Hitzeschutz."),
			Map.entry("summer", "Sommerbetrieb, z. B. vom Ausgang „Sommer“ einer Heizkurve. Nur für den Hitzeschutz: Er greift nur im Sommer (1). "
				+ "Ungebunden zählt jede Jahreszeit."),
			Map.entry("roomTemperature", "Die Raumtemperatur hinter der Fassade, bis zu 4 Fühler (größter Wert oder Mittelwert). Nur für den Hitzeschutz: "
				+ "Über „Hitzeschutz oberhalb“ fährt der Behang ganz zu, wenn niemand zu Hause ist. Ungebunden: kein Hitzeschutz."),
			Map.entry("enable", "Schaltet die Automatik ein und aus, z. B. über einen Taster oder eine Visualisierung. "
				+ "Aus (0): keine Beschattung und kein Hitzeschutz, der Behang bleibt, wo er ist; Wind-, Regen- und Frostalarm wirken weiter. "
				+ "Ein (1) oder ungebunden: Automatik ein."),
			Map.entry("position", "Die Behangposition für den Jalousie- oder Rollladenaktor (DPT 5.001): 0 % oben, 100 % unten. "
				+ "Beim Beschatten „Beschattungsposition“, beim Hitzeschutz 100 %, bei einem Alarm und wenn die Sonne die Fassade verlässt 0 %. "
				+ "Gesendet wird nur eine Änderung, höchstens alle „Kürzeste Zeit zwischen zwei Fahrten“. Hält das virtuelle Gerät an, fährt er hoch (0 %)."),
			Map.entry("slat", "Die Lamellenposition für einen Jalousieaktor (DPT 5.001): 0 % offen, 100 % geschlossen. "
				+ "Beim Beschatten nach „Lamellen beim Beschatten“, beim Hitzeschutz 100 %, bei einem Alarm 0 %. Für Rollläden und Markisen nicht binden. "
				+ "Hält das virtuelle Gerät an, geht sie auf 0 %."),
			Map.entry("upDown", "Auf/Ab für Aktoren ohne Positionsvorgabe (DPT 1.008: 0 auf, 1 ab): ab beim Beschatten (mit „Beschattungsposition“ über 0 %) und beim Hitzeschutz, "
				+ "auf bei einem Alarm und wenn die Sonne die Fassade verlässt. Hält das virtuelle Gerät an, fährt er auf."),
			Map.entry("state", "Was die Beschattung gerade tut: 0 Ruhe, 1 Beschattung, 2 Windalarm, 3 Regenalarm, 4 Frostalarm, "
				+ "5 Handbedienung (die Automatik pausiert), 6 Hitzeschutz, 7 Aus („Automatik ein“ aus)."),
			Map.entry("sunAzimuth", "Die Himmelsrichtung der Sonne in Grad (0 Nord, 90 Ost, 180 Süd, 270 West), aus Standort und Uhrzeit des Controllers. "
				+ "Hilft beim Einstellen von „Ausrichtung der Fassade“ und der Horizontpunkte; zur Fehlersuche."),
			Map.entry("sunElevation", "Die Höhe der Sonne über dem Horizont in Grad, aus Standort und Uhrzeit des Controllers; "
				+ "hilft beim Einstellen von „Kleinste Sonnenhöhe“, „Winkel des Vordachs“ und der Horizontpunkte. Zur Fehlersuche."),
			Map.entry("facadeAzimuth", "Wohin die Fenster der Fassade schauen, als Himmelsrichtung in Grad: 90 Ost, 180 Süd, 270 West. "
				+ "Die Sonne steht auf der Fassade, solange ihre Richtung um weniger als den halben „Öffnungswinkel“ davon abweicht. "
				+ "Der Sonnenstand braucht Standort und Uhrzeit des Controllers; ohne sie beschattet die Beschattung nicht (nur der Hitzeschutz wirkt)."),
			Map.entry("openingAngle", "Aus welchem Winkelbereich vor der Fassade die Sonne hereinscheint, mittig um „Ausrichtung der Fassade“: "
				+ "180° ist alles vor der Fassade (±90°); in einer tiefen Laibung z. B. 120° (±60°)."),
			Map.entry("minElevation", "Unter dieser Sonnenhöhe wird nicht beschattet, z. B. 5°, weil Nachbarhäuser, Bäume oder Berge die tiefe Sonne verdecken. "
				+ "Für einen Horizont, der je nach Richtung verschieden hoch ist, die Horizontpunkte."),
			Map.entry("overhangAngle", "Ein Vordach, Balkon oder Dachüberstand über dem Fenster: Steht die Sonne, senkrecht zur Fassade gesehen, höher als dieser Winkel, "
				+ "fällt kein Licht mehr ins Fenster und es wird nicht beschattet, z. B. 60°. 90°: kein Vordach."),
			Map.entry("horizonPoints", "Wie viele Horizontpunkte gelten, 0 bis 4: Mit ihnen beschreibt man einen verschieden hohen Horizont vor der Fassade "
				+ "(ein Haus links, ein Baum rechts); unterhalb wird nicht beschattet. 0: kein Horizont, nur „Kleinste Sonnenhöhe“."),
			Map.entry("brightnessOn", "Ab dieser Helligkeit gilt es als hell, z. B. 30.000 lx (Sonne auf der Fassade), nach „Verzögerung hell“. "
				+ "Muss über „Dunkel unter“ liegen; nur mit gebundener „Helligkeit“."),
			Map.entry("brightnessOff", "Unter dieser Helligkeit gilt es als dunkel, z. B. 20.000 lx, nach „Verzögerung dunkel“; "
				+ "der Abstand zu „Hell ab“ verhindert Hin- und Herfahren bei wechselnder Bewölkung. Nur mit gebundener „Helligkeit“."),
			Map.entry("brightnessOnDelay", "Wie lange es ununterbrochen hell sein muss, bevor beschattet wird, z. B. 2 min. 0: sofort."),
			Map.entry("brightnessOffDelay", "Wie lange es ununterbrochen dunkel sein muss, bevor die Beschattung endet, z. B. 15 min, damit eine einzelne Wolke den Behang nicht hochfährt. 0: sofort."),
			Map.entry("shadePosition", "Wie weit der Behang beim Beschatten herunterfährt: 100 % ganz unten, z. B. 80 %, damit unten etwas Licht hereinkommt. "
				+ "0 %: Der Behang bleibt oben, nur die Lamellen werden gestellt. Die Bewohner dürfen sie ändern."),
			Map.entry("slatMode", "Wie die Lamellen einer Jalousie beim Beschatten stehen. Für Rollläden und Markisen ohne Bedeutung.\n\n"
				+ "Fest: Die Lamellen stehen immer auf „Lamellenposition beim Beschatten“, z. B. 75 %. Einfach und ruhig, lässt aber je nach Sonnenstand mehr oder weniger Licht durch; "
				+ "„Lamellenabstand zu Lamellenbreite“, „Lamellenposition waagerecht“ und „Lamellenschritt“ wirken nicht.\n\n"
				+ "Dem Sonnenstand nachgeführt: Die Lamellen schließen gerade so weit, dass keine direkte Sonne durchfällt: bei tiefer Sonne fast zu, "
				+ "bei hoher weiter offen, damit mehr Tageslicht hereinkommt. Es wirken „Lamellenabstand zu Lamellenbreite“, „Lamellenposition waagerecht“ "
				+ "und „Lamellenschritt“; „Lamellenposition beim Beschatten“ wirkt nicht."),
			Map.entry("shadeSlat", "Die Lamellenposition beim Beschatten: 0 % offen, 100 % geschlossen, z. B. 75 %. Nur bei „Lamellen beim Beschatten“ „Fest“."),
			Map.entry("slatRatio", "Der Abstand zweier Lamellen geteilt durch ihre Breite, aus dem Datenblatt der Jalousie: 72 mm Abstand bei 80 mm Breite ergeben 0,9. "
				+ "Daraus folgt, wie weit die Lamellen bei welchem Sonnenstand schließen müssen. Nur bei „Lamellen beim Beschatten“ „Dem Sonnenstand nachgeführt“."),
			Map.entry("slatOpen", "Die Lamellenposition, bei der die Lamellen waagerecht stehen: bei vielen Aktoren 0 %, bei manchen z. B. 50 %. "
				+ "Von hier bis 100 % wird nachgeführt. Nur bei „Lamellen beim Beschatten“ „Dem Sonnenstand nachgeführt“."),
			Map.entry("slatStep", "Die nachgeführte Lamellenposition wird auf Schritte dieser Größe aufgerundet, z. B. 10 %: "
				+ "So fahren die Lamellen nicht bei jeder kleinen Änderung des Sonnenstands. Nur bei „Lamellen beim Beschatten“ „Dem Sonnenstand nachgeführt“."),
			Map.entry("heatLimit", "Hitzeschutz: Ist niemand zu Hause („Anwesenheit“ abwesend), ist Sommer (oder „Sommer“ ungebunden) und steigt die „Raumtemperatur“ über diesen Wert, "
				+ "z. B. 26 °C, fährt der Behang ganz zu (Behang und Lamellen 100 %), auch ohne Sonne auf der Fassade; "
				+ "das endet 1 K darunter (bei 26 °C: unter 25 °C) oder wenn jemand heimkommt. Braucht „Anwesenheit“ und „Raumtemperatur“. Die Bewohner dürfen ihn ändern."),
			Map.entry("windLimit", "Ab dieser Windgeschwindigkeit an einem der Windfühler fährt der Behang hoch, z. B. 10 m/s (etwa Windstärke 5); "
				+ "Raffstores und Markisen nennen im Datenblatt ihre Windklasse."),
			Map.entry("windTimeout", "Sendet ein Windfühler länger als diese Zeit nichts (oder seit dem Start noch nie), gilt Windalarm, damit ein ausgefallener Fühler "
				+ "den Behang nicht im Sturm unten lässt, z. B. 60 min bei einem Fühler, der alle 15 min sendet. 0: keine Überwachung."),
			Map.entry("frostLimit", "Unter dieser „Außentemperatur“ gilt Frostalarm, z. B. 2 °C: Der Behang fährt hoch und wird nicht mehr bewegt, damit er nicht festfriert. "
				+ "Nur mit gebundener „Außentemperatur“."),
			Map.entry("alarmRelease", "Wie lange nach dem Ende von Wind, Regen oder Frost der Alarm noch bleibt, z. B. 10 min, damit eine Böe oder ein Schauer "
				+ "den Behang nicht mehrmals hoch- und runterfahren lässt. 0: Der Alarm endet sofort."),
			Map.entry("manualLock", "Wie lange die Automatik nach einer „Handbedienung“ pausiert. Wind-, Regen- und Frostalarm wirken trotzdem. "
				+ "Endet die Pause bei Sonne, beschattet die Automatik wieder; sonst bleibt der Behang, wo er von Hand hingefahren wurde.\n\n"
				+ "Für eine feste Dauer: Die Automatik pausiert „Sperrzeit nach Handbedienung“ lang, z. B. 2 h ab der letzten Bedienung.\n\n"
				+ "Bis sich die Sonne ändert: Die Automatik pausiert, bis die Sonne auf die Fassade kommt oder sie verlässt (oder es hell oder dunkel wird), "
				+ "spätestens nach „Sperrzeit nach Handbedienung“. Wer bei Sonne hochfährt, behält den Behang oben, bis die Sonne die Fassade verlassen hat; kommt sie wieder, beschattet die Automatik erneut.\n\n"
				+ "Bis Mitternacht: Die Automatik pausiert bis zum Ende des Tages; braucht die Uhrzeit des Controllers, ohne sie gilt „Sperrzeit nach Handbedienung“."),
			Map.entry("manualDuration", "Wie lange die Automatik nach einer Handbedienung pausiert, z. B. 2 h: bei „Nach Handbedienung“ „Für eine feste Dauer“ genau so lange, "
				+ "bei „Bis sich die Sonne ändert“ höchstens so lange; bei „Bis Mitternacht“ nur, wenn der Controller keine Uhrzeit hat."),
			Map.entry("minMoveInterval", "Wie viel Zeit zwischen zwei Fahrten der Automatik mindestens vergeht, z. B. 5 min, gegen ständiges Fahren bei wechselndem Licht; "
				+ "eine anstehende Fahrt wird danach nachgeholt. Alarme fahren sofort. 0: keine Mindestzeit.")));
		for (int i = 0; i < HORIZON_ANGLE.length; i++) {
			int n = i + 1;
			result.put("horizonAngle" + n, "Die Richtung des " + n + ". Horizontpunkts in Grad, gemessen von der Blickrichtung der Fassade: "
				+ "negativ links, positiv rechts, von innen hinausgesehen (Vorgabe " + HORIZON_ANGLE[i] + "°); zu ihr gehört „Horizonthöhe " + n + "“. "
				+ "Die Richtungen müssen von Punkt zu Punkt steigen. Nur wenn „Anzahl der Horizontpunkte“ mindestens " + n + " ist.");
			result.put("horizonElevation" + n, "Wie hoch der Horizont in der „Horizontrichtung " + n + "“ ist, in Grad, z. B. 20° für ein Nachbarhaus; "
				+ "steht die Sonne niedriger, wird nicht beschattet. Zwischen zwei Punkten wird geradlinig verbunden, außerhalb des ersten und letzten gilt deren Höhe. "
				+ "Nur wenn „Anzahl der Horizontpunkte“ mindestens " + n + " ist.");
		}
		return Map.copyOf(result);
	}

	private static Map<String, String> helpEn() {
		Map<String, String> result = new HashMap<>(Map.ofEntries(
			Map.entry("brightness", "The brightness on the facade in lux, up to 4 sensors (highest value or mean), for example from the weather station's sensor on this side. "
				+ "It counts as bright from \"Bright from\" after the \"Bright delay\", as dark below \"Dark below\" after the \"Dark delay\"; an invalid value counts as dark. "
				+ "Unbound, it always counts as bright: then the position of the sun alone decides."),
			Map.entry("wind", "The wind speed in m/s, up to 4 sensors. When one reaches \"Wind alarm from\", the blind goes up at once (wind alarm) "
				+ "and stays up until the wind has been below for the \"Alarm release delay\". With a \"Wind sensor timeout\", a silent sensor raises the alarm, too."),
			Map.entry("rain", "Rain sensors, up to 4; when one reports rain, the blind goes up at once (rain alarm), for awnings or external blinds that must not get wet, say. "
				+ "The alarm ends the \"Alarm release delay\" after the rain has stopped."),
			Map.entry("frost", "A frost alarm from outside, from the weather station, say; on (1): the blind goes up and is not moved any more, so it does not freeze in place. "
				+ "In addition to \"Frost alarm below\" on the \"Outside temperature\"."),
			Map.entry("outside", "The outside temperature; below \"Frost alarm below\" there is a frost alarm (the blind goes up). Unbound: only \"Frost alarm\" counts."),
			Map.entry("manual", "The group addresses the blind is operated by hand on, up to 8, for example up/down and stop of the push-button at the window. "
				+ "Every telegram there counts as an operation, even one with an unchanged value: the automatic then pauses as \"After an operation\" says. "
				+ "During an alarm, the blind goes up again after an operation."),
			Map.entry("presence", "Whether someone is at home, for example from the output \"Occupied\" of a house mode. Only for the heat protection: "
				+ "it takes effect only when absent (0) is reported. Unbound: no heat protection."),
			Map.entry("summer", "Summer operation, for example from the output \"Summer\" of a heating curve. Only for the heat protection: it takes effect only in summer (1). "
				+ "Unbound, every season counts."),
			Map.entry("roomTemperature", "The temperature of the room behind the facade, up to 4 sensors (highest value or mean). Only for the heat protection: "
				+ "above \"Heat protection above\" the blind closes fully while nobody is at home. Unbound: no heat protection."),
			Map.entry("enable", "Switches the automatic on and off, from a push-button or a visualization, say. "
				+ "Off (0): no shading and no heat protection, the blind stays where it is; the wind, rain and frost alarms still work. "
				+ "On (1) or unbound: automatic on."),
			Map.entry("position", "The blind position for the blind or shutter actuator (DPT 5.001): 0 % up, 100 % down. "
				+ "While shading \"Shading position\", during heat protection 100 %, on an alarm and when the sun leaves the facade 0 %. "
				+ "Only a change is sent, at most every \"Shortest time between moves\". When the virtual device stops, the blind goes up (0 %)."),
			Map.entry("slat", "The slat position for a venetian blind actuator (DPT 5.001): 0 % open, 100 % closed. "
				+ "While shading as \"Slats while shading\" says, during heat protection 100 %, on an alarm 0 %. Do not bind it for shutters and awnings. "
				+ "When the virtual device stops, it goes to 0 %."),
			Map.entry("upDown", "Up/down for actuators without a position input (DPT 1.008: 0 up, 1 down): down while shading (with a \"Shading position\" above 0 %) and during heat protection, "
				+ "up on an alarm and when the sun leaves the facade. When the virtual device stops, it goes up."),
			Map.entry("state", "What the sun shading is doing: 0 idle, 1 shading, 2 wind alarm, 3 rain alarm, 4 frost alarm, "
				+ "5 manual operation (the automatic pauses), 6 heat protection, 7 off (\"Automatic on\" is off)."),
			Map.entry("sunAzimuth", "The compass direction of the sun in degrees (0 north, 90 east, 180 south, 270 west), from the controller's location and time. "
				+ "Helps to set \"Facade direction\" and the horizon points; for troubleshooting."),
			Map.entry("sunElevation", "The height of the sun above the horizon in degrees, from the controller's location and time; "
				+ "helps to set \"Lowest sun elevation\", \"Overhang angle\" and the horizon points. For troubleshooting."),
			Map.entry("facadeAzimuth", "Where the windows of the facade look, as a compass direction in degrees: 90 east, 180 south, 270 west. "
				+ "The sun is on the facade while its direction differs from this by less than half the \"Opening angle\". "
				+ "The sun's position needs the controller's location and time; without them there is no shading (only the heat protection works)."),
			Map.entry("openingAngle", "The range of angles in front of the facade from which the sun shines in, centred on \"Facade direction\": "
				+ "180° is everything in front of the facade (±90°); in a deep window reveal, 120° (±60°), say."),
			Map.entry("minElevation", "Below this height of the sun there is no shading, 5°, say, because neighbouring houses, trees or hills hide the low sun. "
				+ "For a horizon whose height differs with the direction, use the horizon points."),
			Map.entry("overhangAngle", "A canopy, balcony or roof overhang above the window: when the sun, seen at right angles to the facade, stands higher than this angle, "
				+ "no light falls into the window and there is no shading, 60°, say. 90°: no overhang."),
			Map.entry("horizonPoints", "How many horizon points apply, 0 to 4: they describe a horizon in front of the facade whose height differs with the direction "
				+ "(a house to the left, a tree to the right); below it there is no shading. 0: no horizon, only \"Lowest sun elevation\"."),
			Map.entry("brightnessOn", "From this brightness on it counts as bright, 30,000 lx (sun on the facade), say, after the \"Bright delay\". "
				+ "Must be above \"Dark below\"; only with \"Brightness\" bound."),
			Map.entry("brightnessOff", "Below this brightness it counts as dark, 20,000 lx, say, after the \"Dark delay\"; "
				+ "the gap to \"Bright from\" prevents moving up and down under changing clouds. Only with \"Brightness\" bound."),
			Map.entry("brightnessOnDelay", "How long it has to be bright without a break before shading starts, 2 min, say. 0: at once."),
			Map.entry("brightnessOffDelay", "How long it has to be dark without a break before the shading ends, 15 min, say, so a single cloud does not raise the blind. 0: at once."),
			Map.entry("shadePosition", "How far the blind goes down while shading: 100 % fully down, 80 %, say, to let some light in at the bottom. "
				+ "0 %: the blind stays up and only the slats are set. The residents may change it."),
			Map.entry("slatMode", "How the slats of a venetian blind stand while shading. Of no meaning for shutters and awnings.\n\n"
				+ "Fixed: the slats always stand at \"Slat position\" (the parameter), 75 %, say. Simple and calm, but lets more or less light through depending on the sun; "
				+ "\"Slat spacing to width\", \"Slat position when horizontal\" and \"Slat step\" do not apply.\n\n"
				+ "Following the sun: the slats close just far enough that no direct sun falls through: almost closed with a low sun, "
				+ "further open with a high one, so more daylight comes in. \"Slat spacing to width\", \"Slat position when horizontal\" "
				+ "and \"Slat step\" apply; \"Slat position\" (the parameter) does not."),
			Map.entry("shadeSlat", "The slat position while shading: 0 % open, 100 % closed, 75 %, say. Only with \"Slats while shading\" \"Fixed\"."),
			Map.entry("slatRatio", "The spacing of two slats divided by their width, from the blind's data sheet: 72 mm spacing at 80 mm width gives 0.9. "
				+ "It decides how far the slats have to close at which position of the sun. Only with \"Slats while shading\" \"Following the sun\"."),
			Map.entry("slatOpen", "The slat position at which the slats stand horizontal: 0 % with many actuators, 50 %, say, with some. "
				+ "The tracking runs from here to 100 %. Only with \"Slats while shading\" \"Following the sun\"."),
			Map.entry("slatStep", "The tracked slat position is rounded up to steps of this size, 10 %, say, "
				+ "so the slats do not move at every small change of the sun's position. Only with \"Slats while shading\" \"Following the sun\"."),
			Map.entry("heatLimit", "Heat protection: when nobody is at home (\"Presence\" absent), it is summer (or \"Summer\" is unbound) and the \"Room temperature\" rises above this value, "
				+ "26 °C, say, the blind closes fully (blind and slats 100 %), even without sun on the facade; "
				+ "this ends 1 K below (with 26 °C: below 25 °C) or when someone comes home. Needs \"Presence\" and \"Room temperature\". The residents may change it."),
			Map.entry("windLimit", "From this wind speed at one of the wind sensors on, the blind goes up, 10 m/s (about Beaufort 5), say; "
				+ "external blinds and awnings state their wind class in their data sheet."),
			Map.entry("windTimeout", "When a wind sensor sends nothing for longer than this (or never has since the start), there is a wind alarm, so a failed sensor "
				+ "does not leave the blind down in a storm, for example 60 min for a sensor that sends every 15 min. 0: no monitoring."),
			Map.entry("frostLimit", "Below this \"Outside temperature\" there is a frost alarm, 2 °C, say: the blind goes up and is not moved any more, so it does not freeze in place. "
				+ "Only with \"Outside temperature\" bound."),
			Map.entry("alarmRelease", "How long the alarm stays after the wind, rain or frost is over, 10 min, say, so a gust or a shower "
				+ "does not move the blind up and down several times. 0: the alarm ends at once."),
			Map.entry("manualLock", "How long the automatic pauses after a \"Manual operation\". The wind, rain and frost alarms still work. "
				+ "When the pause ends in sunshine, the automatic shades again; otherwise the blind stays where it was moved by hand.\n\n"
				+ "For a duration: the automatic pauses for the \"Suspension after an operation\", 2 h after the last operation, say.\n\n"
				+ "Until the sun changes: the automatic pauses until the sun reaches the facade or leaves it (or it gets bright or dark), "
				+ "after the \"Suspension after an operation\" at the latest. Whoever raises the blind in the sunshine keeps it up until the sun has left the facade; when it comes back, the automatic shades again.\n\n"
				+ "Until midnight: the automatic pauses until the end of the day; this needs the controller's clock, without it the \"Suspension after an operation\" applies."),
			Map.entry("manualDuration", "How long the automatic pauses after a manual operation, 2 h, say: with \"After an operation\" \"For a duration\" exactly this long, "
				+ "with \"Until the sun changes\" at most this long; with \"Until midnight\" only when the controller has no clock."),
			Map.entry("minMoveInterval", "The shortest time between two moves of the automatic, 5 min, say, against constant moving under changing light; "
				+ "a pending move is made up for afterwards. Alarms move at once. 0: no minimum.")));
		for (int i = 0; i < HORIZON_ANGLE.length; i++) {
			int n = i + 1;
			result.put("horizonAngle" + n, "The direction of horizon point " + n + " in degrees, measured from the direction the facade looks: "
				+ "negative to the left, positive to the right, looking out from inside (" + HORIZON_ANGLE[i] + "° by default); \"Horizon elevation " + n + "\" belongs to it. "
				+ "The directions must rise from point to point. Only when \"Horizon points\" is at least " + n + ".");
			result.put("horizonElevation" + n, "How high the horizon is in \"Horizon direction " + n + "\", in degrees, for example 20° for a neighbouring house; "
				+ "when the sun is lower, there is no shading. Two points are joined by a straight line, beyond the first and the last their height applies. "
				+ "Only when \"Horizon points\" is at least " + n + ".");
		}
		return Map.copyOf(result);
	}

	private SunShadingTexts() {
		// Constants only.
	}

}
