/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.HashMap;
import java.util.Map;

import de.haumacher.ehome.blocks.PvSurplus;

/**
 * The German texts of the PV surplus control's manifest (homecontrol <code>spec/blocks.md</code>
 * 20.1), by the id of the port, parameter or choice (see {@link RoomControllerTexts}), and the help texts of the ports and parameters in German and
 * English.
 */
public final class PvSurplusTexts {

	/** The type id. */
	public static final String TYPE = PvSurplus.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "PV-Überschussregelung";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("gridPower", "Netzleistung"),
		Map.entry("pvPower", "PV-Leistung"),
		Map.entry("housePower", "Hausverbrauch"),
		Map.entry("batteryPower", "Batterieleistung"),
		Map.entry("batterySoc", "Ladezustand der Batterie"),
		Map.entry("lowerPower", "Leistung der nachrangigen Verbraucher"),
		Map.entry("enable", "Überschussregelung ein"),
		Map.entry("ready1", "Verbraucher 1 bereit"),
		Map.entry("ready2", "Verbraucher 2 bereit"),
		Map.entry("switch1", "Verbraucher 1 ein"),
		Map.entry("setpoint1", "Verbraucher 1 Sollwert"),
		Map.entry("state1", "Verbraucher 1 Zustand"),
		Map.entry("energy1", "Verbraucher 1 Energie heute"),
		Map.entry("switch2", "Verbraucher 2 ein"),
		Map.entry("setpoint2", "Verbraucher 2 Sollwert"),
		Map.entry("state2", "Verbraucher 2 Zustand"),
		Map.entry("energy2", "Verbraucher 2 Energie heute"),
		Map.entry("loadPower", "Leistung der Verbraucher"),
		Map.entry("surplus", "Verbleibender Überschuss"),
		Map.entry("gridSign", "Vorzeichen des Netzzählers"),
		Map.entry("gridSign.importPositive", "Bezug positiv"),
		Map.entry("gridSign.exportPositive", "Einspeisung positiv"),
		Map.entry("batterySign", "Vorzeichen der Batterie"),
		Map.entry("batterySign.chargePositive", "Laden positiv"),
		Map.entry("batterySign.dischargePositive", "Entladen positiv"),
		Map.entry("surplusFilter", "Glättung des Überschusses"),
		Map.entry("onMargin", "Einschaltreserve"),
		Map.entry("allowedImport", "Zulässiger Netzbezug"),
		Map.entry("batteryPriority", "Batterie zuerst bis Ladezustand"),
		Map.entry("meterTimeout", "Zählerausfall nach"),
		Map.entry("setpointUnit", "Einheit der Sollwerte"),
		Map.entry("setpointUnit.watt", "W"),
		Map.entry("setpointUnit.percent", "% der Leistung"),
		Map.entry("power1", "Verbraucher 1 Leistung"),
		Map.entry("minPower1", "Verbraucher 1 kleinste Leistung"),
		Map.entry("step1", "Verbraucher 1 Leistungsstufe"),
		Map.entry("minOn1", "Verbraucher 1 Mindestlaufzeit"),
		Map.entry("minOff1", "Verbraucher 1 Mindestpause"),
		Map.entry("maxPerDay1", "Verbraucher 1 höchste Laufzeit pro Tag"),
		Map.entry("mustRunBy1", "Verbraucher 1 Pflichtlaufzeit bis"),
		Map.entry("mustRunFor1", "Verbraucher 1 Pflichtlaufzeit"),
		Map.entry("power2", "Verbraucher 2 Leistung"),
		Map.entry("minPower2", "Verbraucher 2 kleinste Leistung"),
		Map.entry("step2", "Verbraucher 2 Leistungsstufe"),
		Map.entry("minOn2", "Verbraucher 2 Mindestlaufzeit"),
		Map.entry("minOff2", "Verbraucher 2 Mindestpause"),
		Map.entry("maxPerDay2", "Verbraucher 2 höchste Laufzeit pro Tag"),
		Map.entry("mustRunBy2", "Verbraucher 2 Pflichtlaufzeit bis"),
		Map.entry("mustRunFor2", "Verbraucher 2 Pflichtlaufzeit"));

	/**
	 * The German help texts of the ports and parameters by id (haumacher/ehome #101): what the member
	 * does, for the installer. The choices of an enumeration each in a paragraph of their own.
	 */
	public static final Map<String, String> HELP_DE = helpDe();

	/** The English help texts of the ports and parameters by id, as {@link #HELP_DE}. */
	public static final Map<String, String> HELP_EN = helpEn();

	private static Map<String, String> helpDe() {
		Map<String, String> result = new HashMap<>(Map.ofEntries(
			Map.entry("gridPower", "Die Leistung am Netzanschluss in W, z. B. vom Smart Meter; das Vorzeichen sagt „Vorzeichen des Netzzählers“. "
				+ "Ist sie gebunden, wird der Überschuss aus ihr bestimmt, „PV-Leistung“ und „Hausverbrauch“ werden nicht gebraucht. "
				+ "In einer Kette bindet die zweite Überschussregelung hier den „Verbleibenden Überschuss“ der ersten (mit „Einspeisung positiv“). "
				+ "Gelesen alle 30 s; ohne neuen Wert nach „Zählerausfall nach“ schalten die Verbraucher ab."),
			Map.entry("pvPower", "Die Leistung der PV-Anlage in W, z. B. vom Wechselrichter. Nur ohne „Netzleistung“ und nur zusammen mit „Hausverbrauch“: "
				+ "Überschuss = PV-Leistung − Hausverbrauch (5000 W − 1200 W: 3800 W)."),
			Map.entry("housePower", "Der Verbrauch des ganzen Hauses in W, einschließlich der geregelten Verbraucher (ihre Leistung wird wieder hinzugerechnet). "
				+ "Nur ohne „Netzleistung“ und nur zusammen mit „PV-Leistung“."),
			Map.entry("batteryPower", "Die Lade- oder Entladeleistung des Hausspeichers in W, das Vorzeichen sagt „Vorzeichen der Batterie“. "
				+ "Mit ihr entscheidet „Batterie zuerst bis Ladezustand“, ob Ladeleistung als Überschuss zählt. Ungebunden: kein Speicher."),
			Map.entry("batterySoc", "Der Ladezustand des Hausspeichers in %, verglichen mit „Batterie zuerst bis Ladezustand“. "
				+ "Ohne gültigen Wert hat der Speicher Vorrang, solange dieser Parameter über 0 % liegt."),
			Map.entry("lowerPower", "In einer Kette von Überschussregelungen die „Leistung der Verbraucher“ der nächsten (nachrangigen) Instanz: "
				+ "Diese Instanz zählt deren Leistung als verfügbar, ihre eigenen Verbraucher gehen also vor. Ungebunden: keine Kette."),
			Map.entry("enable", "Schaltet die Überschussregelung ein und aus. Aus (0): Alle Verbraucher schalten ab (nach ihrer Mindestlaufzeit), auch eine Pflichtlaufzeit läuft nicht. "
				+ "Ein (1) oder ungebunden: Regelung ein."),
			Map.entry("loadPower", "Die Leistung, die die Verbraucher dieser Instanz und die nachrangigen („Leistung der nachrangigen Verbraucher“) gerade bekommen, in W; "
				+ "für den Eingang „Leistung der nachrangigen Verbraucher“ der vorangehenden Instanz einer Kette. Hält das virtuelle Gerät an, geht sie auf 0 W."),
			Map.entry("surplus", "Was vom geglätteten Überschuss nach allen Verbrauchern übrig bleibt, in W (negativ: Netzbezug); "
				+ "für den Eingang „Netzleistung“ der nächsten Instanz einer Kette, dort mit „Einspeisung positiv“. Nur, solange die Zähler gültige Werte liefern."),
			Map.entry("gridSign", "Wie der Netzzähler seine Leistung meldet.\n\n"
				+ "Bezug positiv: Bezug aus dem Netz ist positiv, Einspeisung negativ: -1500 W heißt 1500 W Überschuss. So melden es die meisten Zähler.\n\n"
				+ "Einspeisung positiv: Einspeisung ist positiv, Bezug negativ: 1500 W heißt 1500 W Überschuss. "
				+ "So für die zweite Instanz einer Kette, die den „Verbleibenden Überschuss“ der ersten bindet."),
			Map.entry("batterySign", "Wie der Speicher seine Leistung meldet; nur mit gebundener „Batterieleistung“.\n\n"
				+ "Laden positiv: 800 W heißt, der Speicher lädt mit 800 W; -800 W, er entlädt.\n\n"
				+ "Entladen positiv: 800 W heißt, der Speicher entlädt mit 800 W; -800 W, er lädt."),
			Map.entry("surplusFilter", "Glättet den Überschuss, damit eine Wolke oder der Wasserkocher die Verbraucher nicht gleich schalten: "
				+ "Nach dieser Zeit hat der geglättete Wert knapp zwei Drittel eines Sprungs mitgemacht, z. B. 120 s. "
				+ "0: keine Glättung, so für die nächsten Instanzen einer Kette (die erste glättet schon)."),
			Map.entry("onMargin", "Ein Verbraucher schaltet erst ein, wenn der Überschuss seine Leistung um diese Reserve übersteigt: "
				+ "Ein Heizstab mit 2000 W kommt bei 100 W Reserve ab 2100 W. Mit „Zulässiger Netzbezug“ ergibt das die Hysterese."),
			Map.entry("allowedImport", "Ein laufender Verbraucher bleibt, solange ihm höchstens so viel zum Überschuss fehlt: Ein Heizstab mit 2000 W "
				+ "bleibt bei 100 W bis 1900 W Überschuss an; darunter schaltet er ab, ein stufiger Verbraucher geht eine Stufe zurück. "
				+ "Mit „Einschaltreserve“ ergibt das die Hysterese."),
			Map.entry("batteryPriority", "Bis zu diesem Ladezustand lädt der Speicher zuerst: Seine Ladeleistung zählt nicht als Überschuss, "
				+ "und entlädt er, zählt das gegen den Überschuss, damit die Verbraucher ihn nicht leeren. "
				+ "Darüber dürfen die Verbraucher die Ladeleistung nutzen. 100 %: der Speicher immer zuerst; 0 %: die Verbraucher immer zuerst. "
				+ "Nur mit gebundener „Batterieleistung“. Die Bewohner dürfen ihn ändern."),
			Map.entry("meterTimeout", "Liefert ein gebundener Zähler länger als diese Zeit keinen neuen Wert (oder seit dem Start noch nie), "
				+ "ist der Überschuss unbekannt: Die Verbraucher schalten ab (nach ihrer Mindestlaufzeit), eine Pflichtlaufzeit läuft weiter. "
				+ "Z. B. 5 min; für die nächsten Instanzen einer Kette einige Perioden, z. B. 60 s."),
			Map.entry("setpointUnit", "In welcher Einheit die Ausgänge „Verbraucher 1 Sollwert“ und „Verbraucher 2 Sollwert“ die zugeteilte Leistung senden.\n\n"
				+ "W: in Watt, z. B. 1500 W, für Verbraucher, die eine Leistung vorgegeben bekommen (Wallbox, Heizstab mit Leistungseingang).\n\n"
				+ "% der Leistung: als Anteil von „Verbraucher 1 Leistung“ bzw. „Verbraucher 2 Leistung“: 1500 W von 3000 W sind 50 %, "
				+ "z. B. für einen Heizstab-Regler mit 0–100 %.")));
		for (int k = 1; k <= 2; k++) {
			String load = "Verbraucher " + k;
			String rank = k == 1 ? "Er kommt vor „Verbraucher 2“." : "Er bekommt, was „Verbraucher 1“ übrig lässt.";
			result.put("ready" + k, "Ob " + load + " gerade Leistung aufnehmen kann, z. B. der Thermostat eines Warmwasserspeichers (noch nicht heiß) "
				+ "oder die Wallbox (Auto angesteckt). Aus (0) oder ohne gültigen Wert: Der Verbraucher schaltet ab (nach seiner Mindestlaufzeit) und bleibt aus (Zustand Aus). Ungebunden: immer bereit.");
			result.put("switch" + k, "Schaltet " + load + " ein und aus, z. B. über einen Schaltaktor oder den SG-Ready-Eingang einer Wärmepumpe; "
				+ "ein, solange ihm Leistung zugeteilt ist. " + rank + " Hält das virtuelle Gerät an, schaltet er aus.");
			result.put("setpoint" + k, "Die Leistung, die " + load + " zugeteilt ist, in W oder in % nach „Einheit der Sollwerte“, "
				+ "für einen stufigen Verbraucher wie eine Wallbox oder einen Heizstab mit Leistungseingang; 0 heißt aus. "
				+ "Hält das virtuelle Gerät an, geht er auf 0.");
			result.put("state" + k, "Was " + load + " gerade tut: 0 Aus (nicht bereit, „Überschussregelung ein“ aus oder Leistung 0), "
				+ "1 Wartet (zu wenig Überschuss), 2 Läuft, 3 Pflichtlauf (läuft für die Pflichtlaufzeit, notfalls aus dem Netz), "
				+ "4 Tageslimit erreicht („" + load + " höchste Laufzeit pro Tag“).");
			result.put("energy" + k, "Die Energie, die " + load + " heute zugeteilt bekommen hat, in Wh, aus der zugeteilten Leistung gerechnet, nicht gemessen; "
				+ "beginnt um Mitternacht bei 0 (ohne Uhrzeit des Controllers alle 24 h). Zur Anzeige oder Fehlersuche.");
			result.put("power" + k, "Die Leistung von " + load + " in W: bei einem schaltenden Verbraucher seine Leistung im Betrieb, z. B. 2000 W für einen Heizstab, "
				+ "bei einem stufigen seine größte. 0: Der Verbraucher wird nicht genutzt. " + rank);
			result.put("minPower" + k, "Die kleinste Leistung von " + load + ", wenn er stufig ist, in W, z. B. 4140 W für eine dreiphasige Wallbox mit 6 A; "
				+ "darunter bleibt er aus. Muss über 0 und höchstens „" + load + " Leistung“ sein. Nur mit „" + load + " Leistungsstufe“ über 0.");
			result.put("step" + k, "0: " + load + " ist ein schaltender Verbraucher, ganz ein oder aus. Über 0: Er ist stufig und bekommt von „"
				+ load + " kleinste Leistung“ an Stufen dieser Größe bis „" + load + " Leistung“ "
				+ "(3000 W, 1000 W, 1000 W: 1000, 2000 oder 3000 W).");
			result.put("minOn" + k, "Wie lange " + load + " nach dem Einschalten mindestens läuft (wenigstens mit seiner kleinsten Leistung), "
				+ "auch wenn der Überschuss fehlt, z. B. 20 min für eine Wärmepumpe. 0: keine Mindestlaufzeit.");
			result.put("minOff" + k, "Wie lange " + load + " nach dem Ausschalten mindestens aus bleibt, z. B. 20 min für eine Wärmepumpe. 0: keine Mindestpause.");
			result.put("maxPerDay" + k, "Wie lange " + load + " an einem Tag höchstens läuft, z. B. 4 h für eine Poolpumpe; danach bleibt er bis Mitternacht aus "
				+ "(Zustand Tageslimit erreicht). 0: unbegrenzt.");
			result.put("mustRunBy" + k, "Bis zu dieser Uhrzeit muss " + load + " heute „" + load + " Pflichtlaufzeit“ gelaufen sein: "
				+ "Reicht der Überschuss nicht, läuft er rechtzeitig vorher mit voller Leistung, notfalls aus dem Netz "
				+ "(2 h bis 18:00, 30 min schon gelaufen: ab 16:30). Braucht die Uhrzeit des Controllers; nur mit „" + load + " Pflichtlaufzeit“ über 0. "
				+ "Die Bewohner dürfen sie ändern.");
			result.put("mustRunFor" + k, "Wie lange " + load + " jeden Tag mindestens laufen muss, aus Überschuss oder bis „" + load
				+ " Pflichtlaufzeit bis“ aus dem Netz, z. B. 3 h für die Poolpumpe. 0: keine Pflicht. "
				+ "Höchstens „" + load + " höchste Laufzeit pro Tag“, wenn diese über 0 ist. Die Bewohner dürfen sie ändern.");
		}
		return Map.copyOf(result);
	}

	private static Map<String, String> helpEn() {
		Map<String, String> result = new HashMap<>(Map.ofEntries(
			Map.entry("gridPower", "The power at the grid connection in W, from the smart meter, say; \"Grid meter's sign\" gives its sign. "
				+ "When it is bound, the surplus is taken from it and \"PV power\" and \"House consumption\" are not needed. "
				+ "In a chain, the second surplus control binds the \"Surplus left\" of the first one here (with \"Export positive\"). "
				+ "Read every 30 s; without a new value for the \"Meter timeout\", the loads switch off."),
			Map.entry("pvPower", "The power of the PV system in W, from the inverter, say. Only without \"Grid power\" and only together with \"House consumption\": "
				+ "surplus = PV power − house consumption (5000 W − 1200 W: 3800 W)."),
			Map.entry("housePower", "The consumption of the whole house in W, the controlled loads included (their power is added back). "
				+ "Only without \"Grid power\" and only together with \"PV power\"."),
			Map.entry("batteryPower", "The charging or discharging power of the home battery in W; \"Battery's sign\" gives its sign. "
				+ "With it, \"Battery first below\" decides whether charging power counts as surplus. Unbound: no battery."),
			Map.entry("batterySoc", "The home battery's state of charge in %, compared with \"Battery first below\". "
				+ "Without a valid value the battery comes first as long as that parameter is above 0 %."),
			Map.entry("lowerPower", "In a chain of surplus controls, the \"Power of the loads\" of the next (lower priority) instance: "
				+ "this instance counts their power as available, so its own loads come first. Unbound: no chain."),
			Map.entry("enable", "Switches the surplus control on and off. Off (0): all loads switch off (after their minimum on time), and no must-run time runs either. "
				+ "On (1) or unbound: control on."),
			Map.entry("loadPower", "The power the loads of this instance and the lower ones (\"Power of the lower loads\") get right now, in W; "
				+ "for the input \"Power of the lower loads\" of the instance before it in a chain. When the virtual device stops, it goes to 0 W."),
			Map.entry("surplus", "What is left of the smoothed surplus after all loads, in W (negative: drawn from the grid); "
				+ "for the input \"Grid power\" of the next instance in a chain, with \"Export positive\" there. Only while the meters deliver valid values."),
			Map.entry("gridSign", "How the grid meter reports its power.\n\n"
				+ "Import positive: drawing from the grid is positive, feeding in negative: -1500 W means a surplus of 1500 W. Most meters report it this way.\n\n"
				+ "Export positive: feeding in is positive, drawing negative: 1500 W means a surplus of 1500 W. "
				+ "For the second instance of a chain, which binds the \"Surplus left\" of the first."),
			Map.entry("batterySign", "How the battery reports its power; only with \"Battery power\" bound.\n\n"
				+ "Charging positive: 800 W means the battery charges with 800 W; -800 W, it discharges.\n\n"
				+ "Discharging positive: 800 W means the battery discharges with 800 W; -800 W, it charges."),
			Map.entry("surplusFilter", "Smooths the surplus so a cloud or the kettle does not switch the loads straight away: "
				+ "after this time the smoothed value has followed almost two thirds of a step, 120 s, say. "
				+ "0: no smoothing, for the next instances of a chain (the first one smooths already)."),
			Map.entry("onMargin", "A load switches on only when the surplus exceeds its power by this margin: "
				+ "a 2000 W heating rod with a margin of 100 W comes on from 2100 W. Together with \"Allowed import\" this makes the hysteresis."),
			Map.entry("allowedImport", "A running load stays on as long as the surplus falls short of its power by at most this much: a 2000 W heating rod "
				+ "with 100 W stays on down to 1900 W of surplus; below, it switches off, and a stepped load goes down a step. "
				+ "Together with \"Switch-on margin\" this makes the hysteresis."),
			Map.entry("batteryPriority", "Up to this state of charge the battery charges first: its charging power does not count as surplus, "
				+ "and when it discharges, that counts against the surplus, so the loads do not drain it. "
				+ "Above, the loads may use the charging power. 100 %: the battery always first; 0 %: the loads always first. "
				+ "Only with \"Battery power\" bound. The residents may change it."),
			Map.entry("meterTimeout", "When a bound meter delivers no new value for longer than this (or never has since the start), "
				+ "the surplus is unknown: the loads switch off (after their minimum on time), a must-run time goes on. "
				+ "5 min, say; for the next instances of a chain a few periods, 60 s, say."),
			Map.entry("setpointUnit", "The unit in which the outputs \"Load 1 setpoint\" and \"Load 2 setpoint\" send the power allotted.\n\n"
				+ "W: in watts, 1500 W, say, for loads that are given a power (a wallbox, a heating rod with a power input).\n\n"
				+ "% of the power: as a share of \"Load 1 power\" or \"Load 2 power\": 1500 W of 3000 W is 50 %, "
				+ "for a heating rod controller taking 0–100 %, say.")));
		for (int k = 1; k <= 2; k++) {
			String load = "Load " + k;
			String rank = k == 1 ? "It comes before \"Load 2\"." : "It gets what \"Load 1\" leaves.";
			result.put("ready" + k, "Whether load " + k + " can take power right now, for example the thermostat of a hot water tank (not hot yet) "
				+ "or the wallbox (car plugged in). Off (0) or without a valid value: the load switches off (after its minimum on time) and stays off (state off). Unbound: always ready.");
			result.put("switch" + k, "Switches load " + k + " on and off, through a switching actuator or the SG ready input of a heat pump, say; "
				+ "on while power is allotted to it. " + rank + " When the virtual device stops, it switches off.");
			result.put("setpoint" + k, "The power allotted to load " + k + ", in W or in % as \"Setpoint unit\" says, "
				+ "for a stepped load such as a wallbox or a heating rod with a power input; 0 means off. "
				+ "When the virtual device stops, it goes to 0.");
			result.put("state" + k, "What load " + k + " is doing: 0 off (not ready, \"Surplus control on\" off or power 0), "
				+ "1 waiting (too little surplus), 2 running, 3 must run (running for its must-run time, from the grid if need be), "
				+ "4 daily limit reached (\"" + load + " longest runtime per day\").");
			result.put("energy" + k, "The energy allotted to load " + k + " today in Wh, computed from the power allotted, not measured; "
				+ "starts at 0 at midnight (every 24 h without the controller's clock). For display or troubleshooting.");
			result.put("power" + k, "The power of load " + k + " in W: for a switched load its power when on, 2000 W for a heating rod, say, "
				+ "for a stepped one its highest. 0: the load is not used. " + rank);
			result.put("minPower" + k, "The lowest power of load " + k + " when it is stepped, in W, 4140 W for a three-phase wallbox at 6 A, say; "
				+ "below it, the load stays off. Must be above 0 and at most \"" + load + " power\". Only with \"" + load + " step\" above 0.");
			result.put("step" + k, "0: load " + k + " is a switched load, fully on or off. Above 0: it is stepped and gets, from \""
				+ load + " lowest power\" on, steps of this size up to \"" + load + " power\" "
				+ "(3000 W, 1000 W, 1000 W: 1000, 2000 or 3000 W).");
			result.put("minOn" + k, "The shortest time load " + k + " runs once switched on (at least at its lowest power), "
				+ "even without the surplus, 20 min for a heat pump, say. 0: no minimum.");
			result.put("minOff" + k, "The shortest time load " + k + " stays off once switched off, 20 min for a heat pump, say. 0: no minimum.");
			result.put("maxPerDay" + k, "The longest load " + k + " runs in a day, 4 h for a pool pump, say; then it stays off until midnight "
				+ "(state daily limit reached). 0: unlimited.");
			result.put("mustRunBy" + k, "By this time of day load " + k + " must have run for \"" + load + " must have run for\" today: "
				+ "when the surplus is not enough, it runs at full power early enough before, from the grid if need be "
				+ "(2 h by 18:00, 30 min run already: from 16:30). Needs the controller's clock; only with \"" + load + " must have run for\" above 0. "
				+ "The residents may change it.");
			result.put("mustRunFor" + k, "How long load " + k + " has to run every day at least, from surplus or, by \"" + load
				+ " must have run by\", from the grid, 3 h for the pool pump, say. 0: no obligation. "
				+ "At most \"" + load + " longest runtime per day\" when that is above 0. The residents may change it.");
		}
		return Map.copyOf(result);
	}

	private PvSurplusTexts() {
		// Constants only.
	}

}
