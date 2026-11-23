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
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
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
        return Map.of("battery", soc >= 0 && soc <= 100 ? QuantityType.valueOf(soc, Units.PERCENT) : UnDefType.UNDEF,
                "range", range >= 0 ? QuantityType.valueOf(range, KILO(SIUnits.METRE)) : UnDefType.UNDEF, "odometer",
                distance >= 0 ? QuantityType.valueOf(distance, KILO(SIUnits.METRE)) : UnDefType.UNDEF, "chargingStatus",
                charging, "lastReport",
                time > 0 ? new DateTimeType(
                        time < 100000000000L ? Instant.ofEpochSecond(time) : Instant.ofEpochMilli(time))
                        : UnDefType.UNDEF);
    }

    private Telemetry() {
    }
}
