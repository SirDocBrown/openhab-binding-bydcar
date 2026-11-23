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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Codec for the BYD transport envelope using the indexed pyBYD tables.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class Bangcle {
    private final byte[][] tables = new byte[8][];

    Bangcle() throws IOException {
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/bangcle_tables.bin"))) {
            byte[] data = input.readAllBytes();
            ByteBuffer index = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int[] sizes = { 163840, 245760, 4096, 163840, 245760, 4096, 8, 8 };
            if (index.getInt() != 0x42544742 || index.getShort() != 1 || index.getShort() != sizes.length) {
                throw new IOException("Unsupported BYD table format");
            }
            for (int i = 0; i < sizes.length; i++) {
                int start = index.getInt();
                int size = index.getInt();
                if (size != sizes[i] || start < 72 || start > data.length - size) {
                    throw new IOException("Invalid BYD table index");
                }
                tables[i] = Arrays.copyOfRange(data, start, start + size);
            }
        }
    }

    String encode(String json) {
        byte[] plain = json.getBytes(UTF_8);
        int padding = 16 - plain.length % 16;
        byte[] data = Arrays.copyOf(plain, plain.length + padding);
        Arrays.fill(data, plain.length, data.length, (byte) padding);
        for (int offset = 0; offset < data.length; offset += 16) {
            if (offset > 0) {
                for (int j = 0; j < 16; j++) {
                    data[offset + j] ^= data[offset - 16 + j];
                }
            }
            block(data, offset, true);
        }
        return "F" + Base64.getEncoder().encodeToString(data);
    }

    String decode(String envelope) throws IOException {
        String clean = envelope.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
        if (!clean.startsWith("F")) {
            throw new IOException("Invalid BYD envelope");
        }
        byte[] data = Base64.getDecoder().decode(clean.substring(1));
        if (data.length == 0 || data.length % 16 != 0) {
            throw new IOException("Invalid BYD ciphertext length");
        }
        for (int offset = data.length - 16; offset >= 0; offset -= 16) {
            block(data, offset, false);
            if (offset > 0) {
                for (int j = 0; j < 16; j++) {
                    data[offset + j] ^= data[offset - 16 + j];
                }
            }
        }
        int padding = data[data.length - 1];
        if (padding < 1 || padding > 16) {
            throw new IOException("Invalid BYD padding");
        }
        for (int i = data.length - padding; i < data.length; i++) {
            if (data[i] != padding) {
                throw new IOException("Invalid BYD padding");
            }
        }
        return new String(data, 0, data.length - padding, UTF_8);
    }

    private void block(byte[] data, int offset, boolean encrypt) {
        int base = encrypt ? 3 : 0;
        byte[] roundTable = tables[base];
        byte[] xorTable = tables[base + 1];
        byte[] permutation = tables[encrypt ? 7 : 6];
        byte[] state = new byte[16];
        byte[] expanded = new byte[64];
        System.arraycopy(data, offset, state, 0, 16);
        for (int step = 0; step < 9; step++) {
            int round = encrypt ? step : 9 - step;
            for (int column = 0; column < 4; column++) {
                for (int row = 0; row < 4; row++) {
                    int sourceRow = (permutation[column * 2] + row) & 3;
                    int source = column + 4 * sourceRow;
                    int index = ((16 * round + source) * 256 + (state[source] & 255)) * 4;
                    System.arraycopy(roundTable, index, expanded, 16 * column + row * 4, 4);
                }
            }
            for (int cell = 0; cell < 16; cell++) {
                int value = expanded[cell] & 255;
                int table = (round * 96 + cell * 6) * 256;
                for (int column = 1; column < 4; column++) {
                    int next = expanded[cell + 16 * column] & 255;
                    int low = xorTable[table + ((next & 15) << 4) + (value & 15)] & 15;
                    int high = xorTable[table + 256 + (next & 240) + (value >>> 4)] & 15;
                    value = (high << 4) | low;
                    table += 512;
                }
                state[cell] = (byte) value;
            }
        }
        for (int cell = 0; cell < 16; cell++) {
            int column = cell % 4;
            int row = ((cell / 4) + (encrypt ? column : -column)) & 3;
            int source = row * 4 + column;
            data[offset + cell] = tables[base + 2][source * 256 + (state[source] & 255)];
        }
    }
}
