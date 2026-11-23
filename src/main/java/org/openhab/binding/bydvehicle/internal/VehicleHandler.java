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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

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
    private final Map<String, State> last = new HashMap<>();
    private static final Set<String> CHARGING_CHANNELS = Set.of("cableConnected", "timeToFull", "scheduleEnabled",
            "scheduleStart", "scheduleEnd", "scheduleRepeat");
    private @Nullable ScheduledFuture<?> gpsJob;
    private @Nullable ScheduledFuture<?> commandJob;
    private String controlKey = "";
    private int temperature;
    private int duration;

    public static class Configuration {
        public String email = "";
        public String password = "";
        public String vin = "";
        public int refreshInterval = 15;
        public boolean enableControl;
        public String controlPin = "";
        public int climateTemperature = 21;
        public int climateDuration = 15;
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
            if (config.enableControl) {
                if (!config.controlPin.matches("[0-9]{6}") || config.climateTemperature < 15
                        || config.climateTemperature > 31 || config.climateDuration < 10 || config.climateDuration > 30
                        || config.climateDuration % 5 != 0) {
                    updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/control-config-invalid");
                    return;
                }
                controlKey = BydProtocol.md5(config.controlPin);
            } else {
                controlKey = "";
            }
            temperature = config.climateTemperature;
            duration = config.climateDuration;
            BydClient active = new BydClient(config.email.strip(), config.password, config.vin.strip());
            client = active;
            interval = config.refreshInterval;
            publish("commandResult", new StringType(config.enableControl ? "READY" : "DISABLED"));
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
                    Telemetry.states(data).forEach(this::publish);
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
            if (Telemetry.ready(data)) {
                readExtras(active);
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

    private void publish(String channel, State state) {
        last.put(channel, state);
        updateState(channel, state);
    }

    private void readExtras(BydClient active) throws InterruptedException {
        synchronized (this) {
            if (!active.equals(client)) {
                return;
            }
            if (isLinked("location") && gpsJob == null) {
                gpsJob = scheduler.schedule(() -> gps(active, "", 0), 0, TimeUnit.SECONDS);
            }
        }
        if (CHARGING_CHANNELS.stream().noneMatch(this::isLinked)) {
            return;
        }
        Map<String, State> values;
        try {
            values = Telemetry.charging(active.charging());
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            values = Telemetry.charging(new JsonObject());
        }
        synchronized (this) {
            if (active.equals(client)) {
                values.forEach(this::publish);
            }
        }
    }

    private void gps(BydClient active, String serial, int attempt) {
        State fix = UnDefType.UNDEF;
        String next = "";
        try {
            JsonObject response = active.gps(serial);
            fix = Telemetry.location(response);
            String received = Telemetry.text(response, "requestSerial");
            next = received.isEmpty() ? serial : received;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
            // Location failures do not invalidate the vehicle telemetry.
        }
        synchronized (this) {
            if (!active.equals(client)) {
                return;
            }
            if (fix == UnDefType.UNDEF && !next.isEmpty() && attempt < 10 && isLinked("location")) {
                String token = next;
                gpsJob = scheduler.schedule(() -> gps(active, token, attempt + 1), 1500, TimeUnit.MILLISECONDS);
            } else {
                gpsJob = null;
                publish("location", fix);
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
        BydClient active = client;
        if (active != null && "remoteCommand".equals(channel.getId()) && command instanceof StringType requested) {
            if (controlKey.isEmpty() || commandJob != null) {
                return;
            }
            RemoteAction action;
            try {
                action = RemoteAction.valueOf(requested.toString());
            } catch (IllegalArgumentException ignored) {
                return;
            }
            String key = controlKey;
            int degrees = temperature;
            int minutes = duration;
            publish("commandResult", new StringType(action.name() + ": PENDING"));
            commandJob = scheduler.schedule(() -> send(active, action, key, degrees, minutes, "", 0), 0,
                    TimeUnit.SECONDS);
            return;
        }
        State cached = last.get(channel.getId());
        if (command == RefreshType.REFRESH && cached != null) {
            updateState(channel, cached);
        }
    }

    private void send(BydClient active, RemoteAction action, String key, int degrees, int minutes, String serial,
            int attempt) {
        String outcome = "UNCONFIRMED";
        String next = "";
        try {
            JsonObject response = active.control(action, key, degrees, minutes, serial);
            String received = Telemetry.text(response, "requestSerial");
            if (serial.isEmpty() || received.isEmpty() || serial.equals(received)) {
                outcome = RemoteAction.outcome(response);
                next = received.isEmpty() ? serial : received;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
            // A lost response can occur after execution. Never replay a trigger.
        }
        synchronized (this) {
            if (!active.equals(client)) {
                return;
            }
            if ("PENDING".equals(outcome) && !next.isEmpty() && attempt < 10) {
                String token = next;
                commandJob = scheduler.schedule(() -> send(active, action, key, degrees, minutes, token, attempt + 1),
                        3, TimeUnit.SECONDS);
                return;
            }
            commandJob = null;
            publish("commandResult",
                    new StringType(action.name() + ": " + ("PENDING".equals(outcome) ? "UNCONFIRMED" : outcome)));
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
        ScheduledFuture<?> command = commandJob;
        commandJob = null;
        if (command != null) {
            command.cancel(true);
        }
        ScheduledFuture<?> gps = gpsJob;
        gpsJob = null;
        if (gps != null) {
            gps.cancel(true);
        }
        controlKey = "";
        last.clear();
        super.dispose();
    }
}
