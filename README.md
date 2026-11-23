# BYD Vehicle Binding

This binding reads telemetry from a BYD ATTO 3 linked to a German BYD owner account.
It connects directly to the European BYD cloud over HTTPS.
The Java bundle includes its protocol codec and runs inside openHAB.

## Installation

1. Download the [tested binding JAR](dist/org.openhab.binding.bydvehicle-5.3.0-SNAPSHOT.jar?raw=true) and copy it to the openHAB `addons` folder. On a Docker or QNAP installation, use the host folder mounted at `/openhab/addons`.
1. Open **Settings → Things → + → BYD Vehicle → BYD ATTO 3**.
1. Enter the email address and password you use in the German BYD app. Leave **Vehicle Identification Number** empty if the account contains exactly one vehicle.
1. Wait for the Thing to become **ONLINE**, open its **Channels** tab and link Items. Compare the battery level and the last vehicle report with the app.

## Supported Things and configuration

Add the `atto3` Thing manually. The binding uses the authenticated account's vehicle list to select the car.
The supported account type is an email/password owner account registered in Germany.

| Parameter | Required | Meaning |
| --- | --- | --- |
| `email` | Yes | BYD account email |
| `password` | Yes | BYD account password |
| `vin` | For multiple vehicles | VIN of the intended ATTO 3 |
| `refreshInterval` | No | Minutes between requests, default 15, minimum 1 |

## Channels

| Channel | Item type | Meaning |
| --- | --- | --- |
| `battery` | Number:Dimensionless | Traction battery state of charge, in percent |
| `range` | Number:Length | Estimated electric range, in kilometres |
| `odometer` | Number:Length | Total distance driven, in kilometres |
| `chargingStatus` | String | `CHARGING`, `NOT_CHARGING`, `SCHEDULED` or `IDLE` |
| `lastReport` | DateTime | Vehicle sample time, when supplied by BYD |

All channels are read-only.
BYD can report `IDLE` with the cable connected or disconnected.
Missing measurements in a usable sample produce `UNDEF`.
When the vehicle is asleep or the service is unreachable, the Thing goes **OFFLINE** and keeps the last successful values.
Use `lastReport` and the Thing status when using readings in rules.

## File-based example

```java
Thing bydvehicle:atto3:car "BYD ATTO 3" [ email="owner@example.invalid", password="YOUR_APP_PASSWORD", refreshInterval=15 ]
```

```java
Number:Dimensionless AttoBattery "Battery [%.0f %unit%]" { channel="bydvehicle:atto3:car:battery", unit="%" }
Number:Length AttoRange "Electric range [%.0f %unit%]" { channel="bydvehicle:atto3:car:range", unit="km" }
String AttoCharging "Charging [%s]" { channel="bydvehicle:atto3:car:chargingStatus" }
```

## Polling and troubleshooting

The binding signs in, selects the vehicle and requests a telemetry sample.
If the cloud returns a request serial, it checks the result up to ten times at 1.5-second intervals.
Linked Item refreshes reuse the latest local sample. A shorter polling interval increases cloud traffic and may wake the vehicle more frequently.

- **Login rejected** — verify the owner credentials in the BYD app and check the clock on the openHAB host.
- **Check the vehicle VIN** — verify that the car appears in this account and enter its VIN if the account contains multiple vehicles.
- **No usable sample** — check when the app last received a vehicle report. Automatic polling resumes after the configured interval.
- **Session expired** — the binding logs in again on the next scheduled poll.

## Development

The module targets Java 21 and uses the openHAB Add-ons Maven reactor.
Place the module in `bundles/org.openhab.binding.bydvehicle` of the 5.3.x add-ons checkout, register it in `bundles/pom.xml` and run the following from the repository root.

```sh
mvn -pl :org.openhab.binding.bydvehicle clean verify
```

JUnit tests use synthetic vehicle responses and independent ciphertext fixtures produced with pyBYD 0.0.90.
The included lookup table comes from that release, which describes it as extracted from the mobile client.
Review the table's redistribution provenance with the openHAB maintainers before upstream submission.

[openHAB developer guide](https://www.openhab.org/docs/developer/) · [pyBYD protocol reference](https://github.com/jkaberg/pyBYD/tree/0.0.90) · [Third-party notice](NOTICE)
