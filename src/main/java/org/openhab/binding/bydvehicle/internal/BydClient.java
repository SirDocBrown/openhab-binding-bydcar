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

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bydvehicle.internal.BydProtocol.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Cloud client for a German BYD owner account and one electric vehicle.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class BydClient {
    private static final String API = "https://dilinkappoversea-eu.byd.auto";
    private static final Set<String> EXPIRED = Set.of("1002", "1011", "1030", "3001", "3003");
    private static final String LOGIN = "/app/account/login";
    private final Logger logger = LoggerFactory.getLogger(BydClient.class);
    private long clockOffset;
    private final HttpClient http;
    private final BydProtocol protocol;
    private final Bangcle codec;
    private final String requestedVin;
    private @Nullable Session session;
    private String vin = "";
    private String verifiedKey = "";

    BydClient(String email, String password, String requestedVin) throws GeneralSecurityException, IOException {
        this(email, password, requestedVin, null);
    }

    BydClient(String email, String password, String requestedVin, @Nullable HttpClient supplied)
            throws GeneralSecurityException, IOException {
        this.protocol = new BydProtocol(email, password);
        this.codec = new Bangcle();
        this.requestedVin = requestedVin;
        this.http = supplied != null ? supplied
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
    }

    void stop() {
        http.shutdownNow();
    }

    private Session authenticate() throws GeneralSecurityException, IOException, InterruptedException {
        Session active = session;
        if (active == null) {
            JsonObject token = post(LOGIN, protocol.login(now()), protocol.loginKey()).getAsJsonObject()
                    .getAsJsonObject("token");
            active = new Session(required(token, "userId"), BydProtocol.md5(required(token, "signToken")),
                    BydProtocol.md5(required(token, "encryToken")));
            session = active;
        }
        if (vin.isEmpty()) {
            JsonObject query = protocol.tokenRequest(protocol.base(now()), active, false);
            JsonElement cars = post("/app/account/getAllListByUserId", query, active.contentKey());
            vin = selectVin(cars, requestedVin);
        }
        return active;
    }

    synchronized JsonObject read(String serial) throws GeneralSecurityException, IOException, InterruptedException {
        JsonObject fields = new JsonObject();
        BydProtocol.put(fields, "energyType", "0", "tboxVersion", "3");
        if (!serial.isEmpty()) {
            fields.addProperty("requestSerial", serial);
        }
        String endpoint = serial.isEmpty() ? "vehicleRealTimeRequest" : "vehicleRealTimeResult";
        return retry(() -> vehicleRequest("/vehicleInfo/vehicle/" + endpoint, fields, true));
    }

    synchronized JsonObject charging() throws GeneralSecurityException, IOException, InterruptedException {
        return retry(() -> vehicleRequest("/control/smartCharge/homePage", new JsonObject(), false));
    }

    synchronized JsonObject gps(String serial) throws GeneralSecurityException, IOException, InterruptedException {
        JsonObject fields = new JsonObject();
        if (!serial.isEmpty()) {
            fields.addProperty("requestSerial", serial);
        }
        return retry(() -> vehicleRequest(serial.isEmpty() ? "/control/getGpsInfo" : "/control/getGpsInfoResult",
                fields, true));
    }

    synchronized JsonObject control(RemoteAction action, String pinHash, int temperature, int minutes, String serial)
            throws GeneralSecurityException, IOException, InterruptedException {
        return retry(() -> controlRequest(action, pinHash, temperature, minutes, serial));
    }

    private JsonObject controlRequest(RemoteAction action, String pinHash, int temperature, int minutes, String serial)
            throws GeneralSecurityException, IOException, InterruptedException {
        authenticate();
        if (serial.isEmpty() && !pinHash.equals(verifiedKey)) {
            JsonObject verification = new JsonObject();
            BydProtocol.put(verification, "functionType", "remoteControl", "commandPwd", pinHash);
            JsonObject verified = vehicleRequest("/vehicle/vehicleswitch/verifyControlPassword", verification, false);
            if (verified.has("ok") && !"true".equalsIgnoreCase(Telemetry.text(verified, "ok"))) {
                throw new PinRejectedException();
            }
            verifiedKey = pinHash;
        }
        JsonObject fields = new JsonObject();
        BydProtocol.put(fields, "commandType", action.wireName, "commandPwd", serial.isEmpty() ? pinHash : "");
        if (serial.isEmpty()) {
            if (action == RemoteAction.START_CLIMATE) {
                fields.addProperty("controlParamsMap", RemoteAction.climateParams(temperature, minutes).toString());
            }
        } else {
            fields.addProperty("requestSerial", serial);
        }
        return vehicleRequest(serial.isEmpty() ? "/control/remoteControl" : "/control/remoteControlResult", fields,
                true);
    }

    private JsonObject vehicleRequest(String endpoint, JsonObject fields, boolean vehicle)
            throws GeneralSecurityException, IOException, InterruptedException {
        Session active = authenticate();
        JsonObject inner = protocol.base(now());
        inner.addProperty("vin", vin);
        fields.entrySet().forEach(entry -> inner.add(entry.getKey(), entry.getValue()));
        return post(endpoint, protocol.tokenRequest(inner, active, vehicle), active.contentKey()).getAsJsonObject();
    }

    static String selectVin(JsonElement cars, String requested) throws IOException {
        if (!cars.isJsonArray()) {
            throw new IOException("Unexpected vehicle list");
        }
        for (JsonElement entry : cars.getAsJsonArray()) {
            String candidate = required(entry.getAsJsonObject(), "vin");
            if (candidate.equalsIgnoreCase(requested) || (requested.isEmpty() && cars.getAsJsonArray().size() == 1)) {
                return candidate;
            }
        }
        throw new SetupException("Check the vehicle VIN and its assignment to the BYD account.");
    }

    private String now() {
        return Long.toString(System.currentTimeMillis() + clockOffset);
    }

    private static String required(JsonObject source, String name) throws IOException {
        JsonElement value = source.get(name);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new IOException("Incomplete BYD response");
        }
        return value.getAsString();
    }

    private JsonElement post(String endpoint, JsonObject outer, String key)
            throws IOException, GeneralSecurityException, InterruptedException {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("request", codec.encode(outer.toString()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + endpoint)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=UTF-8").header("Accept-Encoding", "identity")
                .header("User-Agent", "okhttp/4.12.0").POST(HttpRequest.BodyPublishers.ofString(wrapper.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long previousOffset = clockOffset;
        response.headers().firstValue("Date").ifPresent(this::adjustClock);
        logger.debug("BYD endpoint {} returned HTTP {}", endpoint, response.statusCode());
        if (response.statusCode() != 200) {
            throw new IOException("BYD returned HTTP " + response.statusCode());
        }
        String decoded = codec.decode(required(JsonParser.parseString(response.body()).getAsJsonObject(), "response"))
                .strip();
        JsonObject result = JsonParser.parseString(decoded.startsWith("F{") ? decoded.substring(1) : decoded)
                .getAsJsonObject();
        String code = required(result, "code");
        String safeCode = code.matches("[0-9]{1,8}") ? code : "unknown";
        logger.debug("BYD endpoint {} returned code {}", endpoint, safeCode);
        if (!"0".equals(code)) {
            if (EXPIRED.contains(code)) {
                verifiedKey = "";
                session = null;
                vin = "";
                if (!LOGIN.equals(endpoint)) {
                    throw new SessionExpiredException(safeCode);
                }
            }
            if ("1003".equals(code) && Math.abs(previousOffset - clockOffset) > 30_000 && !LOGIN.equals(endpoint)
                    && !"/control/remoteControl".equals(endpoint)) {
                throw new ClockAdjustedException();
            }
            throw new BydApiException(safeCode);
        }
        return JsonParser.parseString(BydProtocol.decrypt(required(result, "respondData"), key));
    }

    private void adjustClock(String date) {
        try {
            long difference = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                    - System.currentTimeMillis();
            clockOffset = Math.abs(difference) > 30_000 ? difference : 0;
        } catch (DateTimeParseException ignored) {
            logger.debug("BYD returned an invalid HTTP Date header");
        }
    }

    @FunctionalInterface
    private interface Request {
        JsonObject execute() throws IOException, GeneralSecurityException, InterruptedException;
    }

    private JsonObject retry(Request request) throws IOException, GeneralSecurityException, InterruptedException {
        try {
            return request.execute();
        } catch (SessionExpiredException | ClockAdjustedException e) {
            logger.debug("Retrying BYD request after code {}", e.code);
            return request.execute();
        }
    }

    static class BydApiException extends IOException {
        private static final long serialVersionUID = 1L;
        final String code;

        BydApiException(String code) {
            super("BYD rejected the request (code " + code + ")." + ("1003".equals(code) ? " Check system time." : ""));
            this.code = code;
        }
    }

    private static final class SessionExpiredException extends BydApiException {
        private static final long serialVersionUID = 1L;

        SessionExpiredException(String code) {
            super(code);
        }
    }

    private static final class ClockAdjustedException extends BydApiException {
        private static final long serialVersionUID = 1L;

        ClockAdjustedException() {
            super("1003");
        }
    }

    static final class PinRejectedException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    static final class SetupException extends IOException {
        private static final long serialVersionUID = 1L;

        SetupException(String message) {
            super(message);
        }
    }
}
