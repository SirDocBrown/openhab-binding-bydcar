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
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Runs one bounded telemetry request sequence at a time.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public class VehicleHandler extends BaseThingHandler {
    private @Nullable BydClient client;
    private @Nullable ScheduledFuture<?> job;
    private int interval;
    private Map<String, State> last = Map.of();

    public static class Configuration {
        public String email = "";
        public String password = "";
        public String vin = "";
        public int refreshInterval = 15;
    }

    VehicleHandler(Thing thing) {
        super(thing);
    }

    @Override
    public synchronized void initialize() {
        Configuration config = getConfigAs(Configuration.class);
        if (config.email.isBlank() || config.password.isEmpty() || config.refreshInterval < 1) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/config-invalid");
            return;
        }
        try {
            BydClient active = new BydClient(config.email.strip(), config.password, config.vin.strip());
            client = active;
            interval = config.refreshInterval;
            updateStatus(ThingStatus.UNKNOWN);
            job = scheduler.schedule(() -> poll(active, "", 0), 0, TimeUnit.SECONDS);
        } catch (IOException | GeneralSecurityException e) {
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/codec-unavailable");
        }
    }

    private void poll(BydClient active, String serial, int attempt) {
        try {
            JsonObject data = active.read(serial);
            synchronized (this) {
                if (!active.equals(client)) {
                    return;
                }
                if (Telemetry.ready(data)) {
                    last = Telemetry.states(data);
                    last.forEach(this::updateState);
                    updateStatus(ThingStatus.ONLINE);
                } else {
                    JsonElement value = data.get("requestSerial");
                    String next = value != null && value.isJsonPrimitive() ? value.getAsString() : serial;
                    if (!next.isBlank() && attempt < 10) {
                        job = scheduler.schedule(() -> poll(active, next, attempt + 1), 1500, TimeUnit.MILLISECONDS);
                        return;
                    }
                    updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/vehicle-unavailable");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BydClient.SetupException e) {
            reportFailure(active, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
        } catch (IOException e) {
            reportFailure(active, ThingStatusDetail.COMMUNICATION_ERROR,
                    e.getClass() == IOException.class ? e.getMessage() : "@text/request-failed");
        } catch (GeneralSecurityException | RuntimeException e) {
            reportFailure(active, ThingStatusDetail.COMMUNICATION_ERROR, "@text/request-failed");
        }
        synchronized (this) {
            if (active.equals(client)) {
                job = scheduler.schedule(() -> poll(active, "", 0), interval, TimeUnit.MINUTES);
            }
        }
    }

    private synchronized void reportFailure(BydClient active, ThingStatusDetail detail, @Nullable String message) {
        if (active.equals(client)) {
            updateStatus(detail, message);
        }
    }

    @Override
    public synchronized void handleCommand(ChannelUID channel, Command command) {
        State cached = last.get(channel.getId());
        if (command == RefreshType.REFRESH && cached != null) {
            updateState(channel, cached);
        }
    }

    @Override
    public synchronized void dispose() {
        BydClient active = client;
        client = null;
        ScheduledFuture<?> current = job;
        job = null;
        if (current != null) {
            current.cancel(true);
        }
        if (active != null) {
            active.stop();
        }
        last = Map.of();
        super.dispose();
    }
}
