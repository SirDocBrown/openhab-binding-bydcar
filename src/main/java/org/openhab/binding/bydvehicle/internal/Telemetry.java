/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bydvehicle.internal;

import static org.openhab.core.library.unit.MetricPrefix.KILO;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Maps a usable telemetry sample to openHAB states.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class Telemetry {
    static double number(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value != null && value.isJsonPrimitive()) {
            try {
                double n = Double.parseDouble(value.getAsString());
                return Double.isFinite(n) ? n : -1;
            } catch (NumberFormatException ignored) {
                // The service also returns placeholders such as "--".
            }
        }
        return -1;
    }

    static boolean ready(JsonObject data) {
        return number(data, "onlineState") != 0 && (number(data, "time") > 0 || number(data, "enduranceMileage") > 0
                || number(data, "leftFrontTirepressure") > 0 || number(data, "rightFrontTirepressure") > 0
                || number(data, "leftRearTirepressure") > 0 || number(data, "rightRearTirepressure") > 0);
    }

    static Map<String, State> states(JsonObject data) {
        double soc = number(data, "elecPercent");
        double range = number(data, "enduranceMileage");
        double distance = number(data, "totalMileage");
        long time = (long) number(data, "time");
        State charging = switch ((int) number(data, "chargeState")) {
            case 0 -> new StringType("NOT_CHARGING");
            case 1 -> new StringType("CHARGING");
            case 9 -> new StringType("SCHEDULED");
            case 15 -> new StringType("IDLE");
            default -> UnDefType.UNDEF;
        };
        Map<String, State> states = new HashMap<>(Map.of("battery",
                soc >= 0 && soc <= 100 ? QuantityType.valueOf(soc, Units.PERCENT) : UnDefType.UNDEF, "range",
                range >= 0 ? QuantityType.valueOf(range, KILO(SIUnits.METRE)) : UnDefType.UNDEF, "odometer",
                distance >= 0 ? QuantityType.valueOf(distance, KILO(SIUnits.METRE)) : UnDefType.UNDEF, "chargingStatus",
                charging, "lastReport",
                time > 0 ? new DateTimeType(
                        time < 100000000000L ? Instant.ofEpochSecond(time) : Instant.ofEpochMilli(time))
                        : UnDefType.UNDEF));
        for (String wheel : new String[] { "leftFront", "rightFront", "leftRear", "rightRear" }) {
            states.put(wheel + "Door", contact(number(data, wheel + "Door"), 0, 1));
            states.put(wheel + "Window", contact(number(data, wheel + "Window"), 1, 2));
            states.put(wheel + "Lock", binary(number(data, wheel + "DoorLock"), 1, 2));
            double pressure = number(data, wheel + "Tirepressure");
            if (pressure < 0) {
                pressure = number(data, wheel + "TirePressure");
            }
            states.put(wheel + "Pressure", pressure(data, pressure));
        }
        states.put("trunk", contact(number(data, data.has("backCover") ? "backCover" : "trunkLid"), 0, 1));
        return states;
    }

    private static State contact(double value, int closed, int open) {
        return value == closed ? OpenClosedType.CLOSED : value == open ? OpenClosedType.OPEN : UnDefType.UNDEF;
    }

    private static State binary(double value, int off, int on) {
        return value == off ? OnOffType.OFF : value == on ? OnOffType.ON : UnDefType.UNDEF;
    }

    private static State pressure(JsonObject data, double value) {
        if (value <= 0) {
            return UnDefType.UNDEF;
        }
        return switch ((int) number(data, "tirePressUnit")) {
            case 1 -> QuantityType.valueOf(value, Units.BAR);
            case 2 -> QuantityType.valueOf(value, ImperialUnits.POUND_FORCE_SQUARE_INCH);
            case 3 -> QuantityType.valueOf(value, KILO(SIUnits.PASCAL));
            default -> UnDefType.UNDEF;
        };
    }

    static State location(JsonObject result) {
        JsonObject data = object(result, "data");
        if (data.isEmpty()) {
            data = result;
        }
        try {
            double lat = Double.parseDouble(text(data, "latitude"));
            double lon = Double.parseDouble(text(data, "longitude"));
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && (lat != 0 || lon != 0)) {
                return new PointType(new DecimalType(lat), new DecimalType(lon));
            }
        } catch (NumberFormatException ignored) {
            // GPS fixes can be missing while parked.
        }
        return UnDefType.UNDEF;
    }

    static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonObject object(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static Map<String, State> charging(JsonObject data) {
        double hours = number(data, "fullHour");
        double minutes = number(data, "fullMinute");
        double connected = number(data, "connectState");
        JsonObject schedule = object(data, "smartChargeDto");
        return Map.of("cableConnected", connected > 0 ? OnOffType.ON : binary(connected, 0, 1), "timeToFull",
                hours >= 0 && minutes >= 0 && minutes < 60 ? QuantityType.valueOf(hours * 60 + minutes, Units.MINUTE)
                        : UnDefType.UNDEF,
                "scheduleEnabled", scheduleEnabled(schedule), "scheduleStart",
                scheduleValue(schedule, "startChargeTime"), "scheduleEnd", scheduleValue(schedule, "endChargeTime"),
                "scheduleRepeat", scheduleValue(schedule, "chargeWay"));
    }

    private static State scheduleEnabled(JsonObject schedule) {
        return switch (text(schedule, "status")) {
            case "1", "true" -> OnOffType.ON;
            case "0", "false" -> OnOffType.OFF;
            default -> UnDefType.UNDEF;
        };
    }

    private static State scheduleValue(JsonObject schedule, String key) {
        String value = text(schedule, key);
        return value.isBlank() || "--".equals(value) ? UnDefType.UNDEF : new StringType(value);
    }

    private Telemetry() {
    }
}
