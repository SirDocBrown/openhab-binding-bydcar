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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    @Test
    void mapsMeasuredContactsAndPressureWithoutAssumingMissingValues() {
        JsonObject data = JsonParser.parseString("""
                {"leftFrontDoor":0,"rightFrontDoor":1,"leftFrontWindow":2,"rightFrontWindow":1,
                 "leftRearDoorLock":2,"rightRearDoorLock":0,"backCover":1,"leftFrontTirepressure":2.5,"tirePressUnit":1}
                """).getAsJsonObject();
        var states = Telemetry.states(data);
        assertEquals("OPEN", String.valueOf(states.get("trunk")));
        assertEquals("CLOSED", String.valueOf(states.get("leftFrontDoor")));
        assertEquals("OPEN", String.valueOf(states.get("rightFrontDoor")));
        assertEquals("OPEN", String.valueOf(states.get("leftFrontWindow")));
        assertEquals("CLOSED", String.valueOf(states.get("rightFrontWindow")));
        assertEquals("ON", String.valueOf(states.get("leftRearLock")));
        assertEquals(UnDefType.UNDEF, states.get("rightRearLock"));
        assertEquals("2.5 bar", String.valueOf(states.get("leftFrontPressure")));
        assertEquals(UnDefType.UNDEF, states.get("rightFrontPressure"));
        data.remove("tirePressUnit");
        assertEquals(UnDefType.UNDEF, Telemetry.states(data).get("leftFrontPressure"));
    }

    @Test
    void readsChargingWindowAndGpsFix() {
        JsonObject data = JsonParser.parseString("""
                {"connectState":1,"fullHour":1,"fullMinute":17,
                "smartChargeDto":{"status":"1","startChargeTime":"21:00","endChargeTime":"full","chargeWay":"e"}}
                """).getAsJsonObject();
        var states = Telemetry.charging(data);
        assertEquals("77 min", String.valueOf(states.get("timeToFull")));
        assertEquals("ON", String.valueOf(states.get("cableConnected")));
        assertEquals("full", String.valueOf(states.get("scheduleEnd")));
        assertEquals(UnDefType.UNDEF, Telemetry.location(data));
        assertEquals(UnDefType.UNDEF, Telemetry.charging(new JsonObject()).get("scheduleEnabled"));
        assertNotEquals(UnDefType.UNDEF, Telemetry
                .location(JsonParser.parseString("{\"data\":{\"latitude\":-1,\"longitude\":2}}").getAsJsonObject()));
    }

    @Test
    void mapsClimateParametersAndTerminalStates() {
        JsonObject settings = RemoteAction.climateParams(21, 20);
        assertEquals(7, settings.get("mainSettingTemp").getAsInt());
        assertEquals(3, settings.get("timeSpan").getAsInt());
        assertTrue(settings.get("airSet").isJsonNull());
        assertThrows(IllegalArgumentException.class, () -> RemoteAction.climateParams(21, 8));
        assertEquals("PENDING", RemoteAction.outcome(new JsonObject()));
        for (int value : new int[] { 1, 2, 3, 4 }) {
            JsonObject ack = new JsonObject();
            ack.addProperty("res", value);
            assertEquals(List.of("PENDING", "CONFIRMED", "REJECTED", "CONFIRMED").get(value - 1),
                    RemoteAction.outcome(ack));
        }
    }

    @Test
    void verifiesPinThenTriggersOnceAndPolls() throws Exception {
        List<String> session = fixtures("pybyd-responses.txt");
        List<String> control = fixtures("control-responses.txt");
        List<String> paths = new ArrayList<>();
        BydClient client = scriptedClient(
                List.of(session.get(0), session.get(1), control.get(0), control.get(2), control.get(3), control.get(4)),
                paths);
        String hash = BydProtocol.md5("123456");
        JsonObject pending = client.control(RemoteAction.START_CLIMATE, hash, 21, 15, "");
        assertEquals("PENDING", RemoteAction.outcome(pending));
        JsonObject result = client.control(RemoteAction.START_CLIMATE, hash, 21, 15,
                pending.get("requestSerial").getAsString());
        assertEquals("CONFIRMED", RemoteAction.outcome(result));
        assertEquals("REJECTED", RemoteAction.outcome(client.control(RemoteAction.STOP_CLIMATE, hash, 21, 15, "")));
        assertEquals(List.of("/app/account/login", "/app/account/getAllListByUserId",
                "/vehicle/vehicleswitch/verifyControlPassword", "/control/remoteControl",
                "/control/remoteControlResult", "/control/remoteControl"), paths);
    }

    @Test
    void rejectedPinStopsBeforeTrigger() throws Exception {
        List<String> session = fixtures("pybyd-responses.txt");
        List<String> paths = new ArrayList<>();
        BydClient client = scriptedClient(
                List.of(session.get(0), session.get(1), fixtures("control-responses.txt").get(1)), paths);
        assertThrows(IOException.class,
                () -> client.control(RemoteAction.UNLOCK, BydProtocol.md5("123456"), 21, 15, ""));
        assertFalse(paths.contains("/control/remoteControl"));
    }

    private List<String> fixtures(String name) throws IOException {
        try (var reader = new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/" + name)), UTF_8))) {
            return reader.lines().toList();
        }
    }

    private BydClient scriptedClient(List<String> vectors, List<String> endpoints) throws Exception {
        return scriptedClient(vectors, endpoints, Map.of());
    }

    @SuppressWarnings("unchecked")
    private BydClient scriptedClient(List<String> vectors, List<String> endpoints, Map<Integer, String> dates)
            throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            endpoints.add(request.uri().getPath());
            assertEquals("dilinkappoversea-eu.byd.auto", request.uri().getHost());
            if (sequence.get() == vectors.size()) {
                throw new java.net.http.HttpTimeoutException("Scripted timeout");
            }
            HttpResponse<String> response = mock(HttpResponse.class);
            String date = dates.get(sequence.get());
            Map<String, List<String>> headers = date == null ? Map.of() : Map.of("Date", List.of(date));
            when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
            when(response.statusCode()).thenReturn(200);
            JsonObject body = new JsonObject();
            body.addProperty("response", vectors.get(sequence.getAndIncrement()));
            when(response.body()).thenReturn(body.toString());
            return response;
        });
        return new BydClient("owner@example.invalid", "test password", "", http);
    }

    @Test
    void loginVehicleSelectionPollingAndExpiredSessionRecovery() throws Exception {
        List<String> v = fixtures("pybyd-responses.txt");
        List<String> endpoints = new ArrayList<>();
        BydClient client = scriptedClient(
                List.of(v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(0), v.get(1), v.get(3)), endpoints);
        String serial = client.read("").get("requestSerial").getAsString();
        var states = Telemetry.states(client.read(serial));
        assertEquals("0 %", Objects.requireNonNull(states.get("battery")).toString());
        assertEquals("SCHEDULED", Objects.requireNonNull(states.get("chargingStatus")).toString());
        assertTrue(Telemetry.ready(client.read("")));
        String login = "/app/account/login";
        String list = "/app/account/getAllListByUserId";
        String trigger = "/vehicleInfo/vehicle/vehicleRealTimeRequest";
        assertEquals(List.of(login, list, trigger, "/vehicleInfo/vehicle/vehicleRealTimeResult", trigger, login, list,
                trigger), endpoints);
    }

    private String error(String code) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("code", code);
        return new Bangcle().encode(result.toString());
    }

    @Test
    void retriesRejectedTriggerAndNeverRetriggersWhilePolling() throws Exception {
        var v = fixtures("pybyd-responses.txt");
        var c = fixtures("control-responses.txt");
        List<String> paths = new ArrayList<>();
        var client = scriptedClient(List.of(v.get(0), v.get(1), c.get(0), error("1011"), v.get(0), v.get(1), c.get(0),
                c.get(2), error("3003"), v.get(0), v.get(1), c.get(3)), paths);
        String key = BydProtocol.md5("123456");
        String serial = client.control(RemoteAction.LOCK, key, 21, 15, "").get("requestSerial").getAsString();
        assertEquals("CONFIRMED", RemoteAction.outcome(client.control(RemoteAction.LOCK, key, 21, 15, serial)));
        assertEquals(List.of("/app/account/login", "/app/account/getAllListByUserId",
                "/vehicle/vehicleswitch/verifyControlPassword", "/control/remoteControl", "/app/account/login",
                "/app/account/getAllListByUserId", "/vehicle/vehicleswitch/verifyControlPassword",
                "/control/remoteControl", "/control/remoteControlResult", "/app/account/login",
                "/app/account/getAllListByUserId", "/control/remoteControlResult"), paths);
    }

    @Test
    void retriesExtrasOnceAndPropagatesSecondRejection() throws Exception {
        var v = fixtures("pybyd-responses.txt");
        for (boolean gps : List.of(true, false)) {
            List<String> paths = new ArrayList<>();
            var client = scriptedClient(List.of(v.get(0), v.get(1), error("1030"), v.get(0), v.get(1), error("3001")),
                    paths);
            var failure = assertThrows(BydClient.BydApiException.class, () -> {
                if (gps)
                    client.gps("serial");
                else
                    client.charging();
            });
            assertEquals("3001", failure.code);
            assertEquals(2,
                    Collections.frequency(paths, gps ? "/control/getGpsInfoResult" : "/control/smartCharge/homePage"));
        }
    }

    @Test
    void loginFailureAndBusyTriggerAreNeverRetried() throws Exception {
        var v = fixtures("pybyd-responses.txt");
        var c = fixtures("control-responses.txt");
        List<String> paths = new ArrayList<>();
        var login = scriptedClient(List.of(error("1002")), paths);
        assertEquals("1002", assertThrows(BydClient.BydApiException.class, () -> login.read("")).code);
        assertEquals(List.of("/app/account/login"), paths);
        paths.clear();
        var busy = scriptedClient(List.of(v.get(0), v.get(1), c.get(0), error("6024")), paths);
        assertEquals("6024", assertThrows(BydClient.BydApiException.class,
                () -> busy.control(RemoteAction.LOCK, "hash", 21, 15, "")).code);
        assertEquals(1, Collections.frequency(paths, "/control/remoteControl"));
    }

    @Test
    void retriesClockErrorOnlyWithServerTime() throws Exception {
        var v = fixtures("pybyd-responses.txt");
        List<String> paths = new ArrayList<>();
        String date = ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(10).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        var client = scriptedClient(List.of(v.get(0), v.get(1), error("1003"), v.get(3)), paths, Map.of(2, date));
        assertTrue(Telemetry.ready(client.read("")));
        assertEquals(1, Collections.frequency(paths, "/app/account/login"));
        paths.clear();
        var missing = scriptedClient(List.of(v.get(0), v.get(1), error("1003")), paths);
        assertTrue(Objects
                .requireNonNull(assertThrows(BydClient.BydApiException.class, () -> missing.read("")).getMessage())
                .contains("system time"));
    }

    @Test
    void doesNotReplayTriggerOnClockErrorOrLostResponse() throws Exception {
        var v = fixtures("pybyd-responses.txt");
        var c = fixtures("control-responses.txt");
        String date = ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(10).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        List<String> paths = new ArrayList<>();
        var client = scriptedClient(List.of(v.get(0), v.get(1), c.get(0), error("1003")), paths, Map.of(3, date));
        assertEquals("1003", assertThrows(BydClient.BydApiException.class,
                () -> client.control(RemoteAction.LOCK, "hash", 21, 15, "")).code);
        assertEquals(1, Collections.frequency(paths, "/control/remoteControl"));
        paths.clear();
        var lost = scriptedClient(List.of(v.get(0), v.get(1), c.get(0)), paths);
        assertThrows(java.net.http.HttpTimeoutException.class,
                () -> lost.control(RemoteAction.LOCK, "hash", 21, 15, ""));
        assertEquals(1, Collections.frequency(paths, "/control/remoteControl"));
    }

    @Test
    void retriesLoginOnceAfterClockCorrection() throws Exception {
        var v = fixtures("pybyd-responses.txt");
        String login = "/app/account/login";
        var now = ZonedDateTime.now(ZoneOffset.UTC);
        String ahead = now.plusMinutes(10).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        String behind = now.minusMinutes(10).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        List<String> paths = new ArrayList<>();
        var client = scriptedClient(List.of(error("1003"), v.get(0), v.get(1), v.get(3)), paths, Map.of(0, ahead));
        assertTrue(Telemetry.ready(client.read("")));
        assertEquals(
                List.of(login, login, "/app/account/getAllListByUserId", "/vehicleInfo/vehicle/vehicleRealTimeRequest"),
                paths);
        paths.clear();
        var repeated = scriptedClient(List.of(error("1003"), error("1003")), paths, Map.of(0, ahead, 1, behind));
        assertEquals("1003", assertThrows(BydClient.BydApiException.class, () -> repeated.read("")).code);
        assertEquals(List.of(login, login), paths);
        paths.clear();
        var small = scriptedClient(List.of(error("1003")), paths,
                Map.of(0, now.plusSeconds(15).format(DateTimeFormatter.RFC_1123_DATE_TIME)));
        assertEquals("1003", assertThrows(BydClient.BydApiException.class, () -> small.read("")).code);
        assertEquals(List.of(login), paths);
    }

    @Test
    void serviceTimeUsesLocalClockWithCorrectedRequestTimestamp() throws Exception {
        BydProtocol protocol = new BydProtocol("owner@example.invalid", "test password");
        long before = System.currentTimeMillis();
        String corrected = Long.toString(before + 600_000);
        JsonObject request = protocol.login(corrected);
        long serviceTime = request.get("serviceTime").getAsLong();
        assertEquals(corrected, request.get("reqTimestamp").getAsString());
        assertTrue(serviceTime >= before && serviceTime <= System.currentTimeMillis());
    }
}
