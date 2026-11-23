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

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Owner commands supported by the binding.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
enum RemoteAction {
    LOCK("LOCKDOOR"),
    UNLOCK("OPENDOOR"),
    START_CLIMATE("OPENAIR"),
    STOP_CLIMATE("CLOSEAIR"),
    CLOSE_WINDOWS("CLOSEWINDOW"),
    FLASH_LIGHTS("FLASHLIGHTNOWHISTLE");

    final String wireName;

    RemoteAction(String wireName) {
        this.wireName = wireName;
    }

    static JsonObject climateParams(int temperature, int minutes) {
        if (temperature < 15 || temperature > 31 || minutes < 10 || minutes > 30 || minutes % 5 != 0) {
            throw new IllegalArgumentException("Unsupported climate settings");
        }
        JsonObject params = new JsonObject();
        params.addProperty("airAccuracy", 1);
        params.addProperty("airConditioningMode", 1);
        params.add("airSet", JsonNull.INSTANCE);
        params.addProperty("copilotSettingTemp", temperature - 14);
        params.addProperty("cycleMode", 2);
        params.addProperty("mainSettingTemp", temperature - 14);
        params.addProperty("remoteMode", 4);
        params.addProperty("timeSpan", (minutes - 5) / 5);
        return params;
    }

    static String outcome(JsonObject result) {
        if ("false".equalsIgnoreCase(Telemetry.text(result, "success"))) {
            String state = Telemetry.text(result, "controlState");
            if ("2".equals(state) || "3".equals(Telemetry.text(result, "res"))) {
                return "REJECTED";
            }
            return "PENDING";
        }
        String state = Telemetry.text(result, "controlState");
        if (!state.isEmpty()) {
            return switch (state) {
                case "1" -> "CONFIRMED";
                case "2" -> "REJECTED";
                default -> "PENDING";
            };
        }
        return switch (Telemetry.text(result, "res")) {
            case "2", "4" -> "CONFIRMED";
            case "3" -> "REJECTED";
            default -> "PENDING";
        };
    }
}
