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

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.google.gson.JsonObject;

/**
 * Signs EU cloud requests and encrypts their inner payloads.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class BydProtocol {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String username;
    private final String password;
    private final String deviceId;

    BydProtocol(String username, String password) throws GeneralSecurityException {
        this.username = username;
        this.password = password;
        deviceId = md5(username);
    }

    String loginKey() throws GeneralSecurityException {
        return md5(md5(password));
    }

    JsonObject login(String time) throws GeneralSecurityException {
        JsonObject inner = base(time);
        inner.remove("version");
        put(inner, "agreeStatus", "0", "agreementType", "[1,2]", "appInnerVersion", "363", "appVersion", "3.6.0",
                "deviceName", "XIAOMIPOCO F1", "isAuto", "1", "mobileBrand", "XIAOMI", "mobileModel", "POCO F1",
                "osType", "15", "osVersion", "35", "softType", "0", "timeZone", "Europe/Berlin");
        JsonObject outer = common(username, time);
        put(outer, "appName", "openHAB+5.3.0", "functionType", "pwdLogin", "identifierType", "0");
        seal(outer, inner, md5(password), loginKey());
        put(outer, "signKey", password, "isAuto", "1");
        return finish(outer);
    }

    JsonObject base(String time) {
        JsonObject inner = new JsonObject();
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        put(inner, "deviceType", "0", "imeiMD5", deviceId, "networkType", "wifi", "random", HEX.formatHex(random),
                "timeStamp", time, "version", "363");
        return inner;
    }

    JsonObject tokenRequest(JsonObject inner, Session session, boolean vehicle) throws GeneralSecurityException {
        JsonObject outer = common(session.userId(), inner.get("timeStamp").getAsString());
        if (vehicle) {
            outer.addProperty("userType", "0");
        }
        seal(outer, inner, session.signKey(), session.contentKey());
        return finish(outer);
    }

    private JsonObject common(String identifier, String time) {
        JsonObject outer = new JsonObject();
        put(outer, "countryCode", "DE", "language", "de", "identifier", identifier, "imeiMD5", deviceId, "reqTimestamp",
                time);
        return outer;
    }

    private static void seal(JsonObject outer, JsonObject inner, String signKey, String key)
            throws GeneralSecurityException {
        Map<String, String> fields = new TreeMap<>();
        inner.entrySet().forEach(e -> fields.put(e.getKey(), e.getValue().getAsString()));
        outer.entrySet().forEach(e -> fields.put(e.getKey(), e.getValue().getAsString()));
        String signed = fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&")) + "&password=" + signKey;
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(signed.getBytes(UTF_8));
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            String pair = (i % 2 == 0 ? HEX : HexFormat.of()).toHexDigits(hash[i]);
            signature.append(pair.charAt(0) == '0' ? pair.substring(1) : pair);
        }
        put(outer, "encryData",
                HEX.formatHex(cipher(Cipher.ENCRYPT_MODE, key).doFinal(inner.toString().getBytes(UTF_8))), "sign",
                signature.toString());
    }

    private static JsonObject finish(JsonObject outer) throws GeneralSecurityException {
        put(outer, "ostype", "and", "imei", "BANGCLE01234", "mac", "00:00:00:00:00:00", "model", "POCO F1", "sdk", "35",
                "mod", "Xiaomi", "serviceTime", Long.toString(System.currentTimeMillis()));
        String digest = md5(outer.toString()).toLowerCase(Locale.ROOT);
        outer.addProperty("checkcode", digest.substring(24) + digest.substring(8, 24) + digest.substring(0, 8));
        return outer;
    }

    static void put(JsonObject json, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            json.addProperty(pairs[i], pairs[i + 1]);
        }
    }

    static String md5(String value) throws GeneralSecurityException {
        return HEX.formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(UTF_8)));
    }

    static String decrypt(String ciphertext, String key) throws GeneralSecurityException {
        return new String(cipher(Cipher.DECRYPT_MODE, key).doFinal(HEX.parseHex(ciphertext)), UTF_8);
    }

    private static Cipher cipher(int mode, String key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(HEX.parseHex(key), "AES"), new IvParameterSpec(new byte[16]));
        return cipher;
    }

    record Session(String userId, String signKey, String contentKey) {
    }
}
