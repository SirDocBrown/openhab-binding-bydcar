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
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bydvehicle.internal.BydProtocol.Session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Read-only client for a German BYD owner account and one electric vehicle.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class BydClient {
    private static final String API = "https://dilinkappoversea-eu.byd.auto";
    private static final Set<String> EXPIRED = Set.of("1002", "1011", "1030", "3001", "3003");
    private final HttpClient http;
    private final BydProtocol protocol;
    private final Bangcle codec;
    private final String requestedVin;
    private @Nullable Session session;
    private String vin = "";

    BydClient(String email, String password, String requestedVin) throws GeneralSecurityException, IOException {
        this(email, password, requestedVin, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build());
    }

    BydClient(String email, String password, String requestedVin, HttpClient http)
            throws GeneralSecurityException, IOException {
        this.http = http;
        this.protocol = new BydProtocol(email, password);
        this.codec = new Bangcle();
        this.requestedVin = requestedVin;
    }

    void stop() {
        http.shutdownNow();
    }

    JsonObject read(String serial) throws GeneralSecurityException, IOException, InterruptedException {
        Session active = session;
        if (active == null) {
            JsonObject token = post("/app/account/login", protocol.login(now()), protocol.loginKey()).getAsJsonObject()
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
        JsonObject inner = protocol.base(now());
        BydProtocol.put(inner, "vin", vin, "energyType", "0", "tboxVersion", "3");
        if (!serial.isEmpty()) {
            inner.addProperty("requestSerial", serial);
        }
        String endpoint = serial.isEmpty() ? "vehicleRealTimeRequest" : "vehicleRealTimeResult";
        return post("/vehicleInfo/vehicle/" + endpoint, protocol.tokenRequest(inner, active, true), active.contentKey())
                .getAsJsonObject();
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

    private static String now() {
        return Long.toString(System.currentTimeMillis());
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
        if (response.statusCode() != 200) {
            throw new IOException("BYD returned HTTP " + response.statusCode());
        }
        String decoded = codec.decode(required(JsonParser.parseString(response.body()).getAsJsonObject(), "response"))
                .strip();
        JsonObject result = JsonParser.parseString(decoded.startsWith("F{") ? decoded.substring(1) : decoded)
                .getAsJsonObject();
        String code = required(result, "code");
        if (!"0".equals(code)) {
            if (EXPIRED.contains(code)) {
                session = null;
                vin = "";
            }
            String safeCode = code.matches("[0-9]{1,8}") ? code : "unknown";
            if ("/app/account/login".equals(endpoint)) {
                throw new IOException(
                        "BYD login rejected (code " + safeCode + "). Check account details and system time.");
            }
            throw new IOException("BYD telemetry unavailable (code " + safeCode + ").");
        }
        return JsonParser.parseString(BydProtocol.decrypt(required(result, "respondData"), key));
    }

    static final class SetupException extends IOException {
        private static final long serialVersionUID = 1L;

        SetupException(String message) {
            super(message);
        }
    }
}
