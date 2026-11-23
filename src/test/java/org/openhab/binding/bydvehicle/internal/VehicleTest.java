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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Interoperability tests use synthetic ciphertext generated with pyBYD 0.0.90.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class VehicleTest {
    @Test
    void matchesIndependentEncryptionVectors() throws Exception {
        Bangcle codec = new Bangcle();
        String text = "{\"name\":\"Grüße\",\"test\":123}";
        String expected = "FBUIB7yehh/MZQaRxkJBYVuN0RJms6YbzNj8Yix4OFSc=";
        assertEquals(expected, codec.encode(text));
        assertEquals(text, codec.decode(expected));
        assertEquals("FlpybH7xiohrkjagBOJY5Mg==", codec.encode(""));
        assertThrows(IOException.class, () -> codec.decode("F"));
    }

    @Test
    void requiresUnambiguousAccountVehicle() throws Exception {
        var vehicles = JsonParser.parseString("[{\"vin\":\"aaa\"},{\"vin\":\"bbb\"}]");
        assertThrows(BydClient.SetupException.class, () -> BydClient.selectVin(vehicles, ""));
        assertThrows(BydClient.SetupException.class, () -> BydClient.selectVin(vehicles, "ccc"));
        assertEquals("bbb", BydClient.selectVin(vehicles, "BBB"));
    }

    @Test
    void sleepingVehicleAndMissingFields() {
        var sample = JsonParser.parseString("{\"onlineState\":1,\"time\":0,\"elecPercent\":0}").getAsJsonObject();
        assertFalse(Telemetry.ready(sample));
        sample.addProperty("time", 1795400000);
        assertTrue(Telemetry.ready(sample));
        sample.addProperty("elecPercent", 101);
        sample.addProperty("enduranceMileage", "--");
        assertEquals(UnDefType.UNDEF, Telemetry.states(sample).get("battery"));
        assertEquals(UnDefType.UNDEF, Telemetry.states(sample).get("range"));
        assertEquals(UnDefType.UNDEF, Telemetry.states(sample).get("chargingStatus"));
        sample.addProperty("onlineState", 0);
        assertFalse(Telemetry.ready(sample));
    }

    @SuppressWarnings("unchecked")
    @Test
    void loginVehicleSelectionPollingAndExpiredSessionRecovery() throws Exception {
        List<String> vectors;
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/pybyd-responses.txt")), UTF_8))) {
            vectors = reader.lines().toList();
        }
        int[] replies = { 0, 1, 2, 3, 4, 0, 1, 3 };
        AtomicInteger sequence = new AtomicInteger();
        List<String> endpoints = new ArrayList<>();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            endpoints.add(request.uri().getPath());
            assertEquals("dilinkappoversea-eu.byd.auto", request.uri().getHost());
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            JsonObject body = new JsonObject();
            body.addProperty("response", vectors.get(replies[sequence.getAndIncrement()]));
            when(response.body()).thenReturn(body.toString());
            return response;
        });
        BydClient client = new BydClient("owner@example.invalid", "test password", "", http);
        String serial = client.read("").get("requestSerial").getAsString();
        var states = Telemetry.states(client.read(serial));
        assertEquals("0 %", Objects.requireNonNull(states.get("battery")).toString());
        assertEquals("SCHEDULED", Objects.requireNonNull(states.get("chargingStatus")).toString());
        assertThrows(IOException.class, () -> client.read(""));
        assertTrue(Telemetry.ready(client.read("")));
        String login = "/app/account/login";
        String list = "/app/account/getAllListByUserId";
        String trigger = "/vehicleInfo/vehicle/vehicleRealTimeRequest";
        assertEquals(List.of(login, list, trigger, "/vehicleInfo/vehicle/vehicleRealTimeResult", trigger, login, list,
                trigger), endpoints);
    }
}
