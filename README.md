# BYD Vehicle Binding

This binding reads telemetry from a BYD ATTO 3 linked to a German BYD owner account.
Optional PIN-protected commands control supported vehicle functions.
It connects directly to the European BYD cloud over HTTPS.
The Java bundle includes its protocol codec and runs inside openHAB.

## Installation

1. Download the [tested binding JAR](dist/org.openhab.binding.bydvehicle-5.3.0-SNAPSHOT.jar?raw=true) and copy it to the openHAB `addons` folder. On a Docker or QNAP installation, use the host folder mounted at `/openhab/addons`.
1. Open **Settings → Things → + → BYD Vehicle → BYD ATTO 3**.
1. Enter the email address and password you use in the German BYD app. Leave **Vehicle Identification Number** empty if the account contains exactly one vehicle.
1. Wait for the Thing to become **ONLINE**, open its **Channels** tab and link Items. Compare the battery level and the last vehicle report with the app.

### Updating an existing Thing

Replace the old binding JAR in the add-ons folder with the new JAR, keeping the same filename.
The Thing upgrade adds the new channels and keeps existing Item links.
Once the Thing is online, link the measurements you want.
Remote commands are disabled after upgrading until explicitly enabled in the Thing configuration.

## Supported Things and configuration

Add the `atto3` Thing manually. The binding uses the authenticated account's vehicle list to select the car.
The supported account type is an email/password owner account registered in Germany.

| Parameter | Required | Meaning |
| --- | --- | --- |
| `email` | Yes | BYD account email |
| `password` | Yes | BYD account password |
| `vin` | For multiple vehicles | VIN of the intended ATTO 3 |
| `refreshInterval` | No | Minutes between requests, default 15, minimum 5 |
| `enableControl` | No | Enable vehicle commands, default false |
| `controlPin` | For control | Six-digit operation PIN from the BYD app, entered locally in the Thing settings |
| `climateTemperature` | No | HVAC start target in °C, 15–31, default 21 |
| `climateDuration` | No | HVAC run duration in minutes, 10–30 in steps of five, default 15 |

## Channels

| Channel | Item type | Meaning |
| --- | --- | --- |
| `battery` | Number:Dimensionless | Traction battery state of charge, in percent |
| `range` | Number:Length | Estimated electric range, in kilometres |
| `odometer` | Number:Length | Total distance driven, in kilometres |
| `chargingStatus` | String | `CHARGING`, `NOT_CHARGING`, `SCHEDULED` or `IDLE` |
| `lastReport` | DateTime | Vehicle sample time, when supplied by BYD |
| `<position>Door`, `<position>Window` | Contact | Door or window open/closed at each position |
| `<position>Lock` | Switch | Lock feedback per door, ON means locked |
| `<position>Pressure` | Number:Pressure | Tire pressure with the unit supplied by BYD |
| `trunk` | Contact | Tailgate open/closed |
| `location` | Location | Last available GPS fix |
| `cableConnected` | Switch | Charging connection status from the smart charging endpoint |
| `timeToFull` | Number:Time | Reported hours and minutes remaining, expressed in minutes |
| `scheduleEnabled`, `scheduleStart`, `scheduleEnd`, `scheduleRepeat` | Switch, String, String, String | Simple charge window. End may be `full`. Repeat uses `s` for once, `e` for daily or a list of weekdays (Monday is 0). |
| `remoteCommand` | String | Accepts one of the six explicit commands listed below |
| `commandResult` | String | Most recent command and execution outcome |

Replace `<position>` with `leftFront`, `rightFront`, `leftRear` or `rightRear`.
For example, `leftFrontPressure` is the front-left tire pressure.
Doors, windows, locks and tires reuse the vehicle telemetry response.
Linking the location channel enables GPS requests. Linking any smart charging channel enables one additional charging request per successful telemetry cycle.
Unavailable measurements are reported as `UNDEF`. Optional endpoint failures leave the primary telemetry available.

BYD can report `IDLE` with the cable connected or disconnected.
Missing measurements in a usable sample produce `UNDEF`.
When the vehicle is asleep or the service is unreachable, the Thing goes **OFFLINE** and keeps the last successful values.
Use `lastReport` and the Thing status when using readings in rules.

## Remote commands

Set the operation PIN in the BYD app, then enable **Enable Remote Commands** and enter the PIN in the Thing settings.
Link `remoteCommand` and `commandResult` to separate String Items.
Send one of these commands to the first Item and monitor the second.

| Command | Requested operation |
| --- | --- |
| `START_CLIMATE` | Precondition the cabin using the Thing target temperature and duration |
| `STOP_CLIMATE` | Stop cabin preconditioning |
| `LOCK`, `UNLOCK` | Lock or unlock the vehicle |
| `CLOSE_WINDOWS` | Close the windows |
| `FLASH_LIGHTS` | Flash the vehicle lights |

The binding verifies the PIN before the first command in a session. Only one command can be in progress.
The result changes from `PENDING` to `CONFIRMED` on a successful vehicle acknowledgement, `REJECTED` on an explicit negative acknowledgement or `UNCONFIRMED` on timeout or communication failure.
An unconfirmed command may already have executed. Check the vehicle or the BYD app before sending it again.
A disabled control reports `DISABLED`, an overlapping command or BYD code 6024 reports `BUSY`, and unknown commands report `INVALID: <value>`.
A cloud rejection before dispatch reports `REJECTED (code X)`. Only an explicit expired-session rejection permits one repeat of the trigger after login.
After receiving a serial, the binding retries only the result query. Door lock and other telemetry Items show measured state on the next poll.
Vehicle support varies by equipment, account permissions and BYD service availability.

## File-based example

```java
Thing bydvehicle:atto3:car "BYD ATTO 3" [ email="owner@example.invalid", password="YOUR_APP_PASSWORD", refreshInterval=15 ]
```

```java
Number:Dimensionless AttoBattery "Battery [%.0f %unit%]" { channel="bydvehicle:atto3:car:battery", unit="%" }
Number:Length AttoRange "Electric range [%.0f %unit%]" { channel="bydvehicle:atto3:car:range", unit="km" }
String AttoCharging "Charging [%s]" { channel="bydvehicle:atto3:car:chargingStatus" }
String AttoCommand { channel="bydvehicle:atto3:car:remoteCommand", autoupdate="false" }
String AttoCommandResult { channel="bydvehicle:atto3:car:commandResult" }
```

## Polling and troubleshooting

The binding signs in, selects the vehicle and requests a telemetry sample.
If the cloud returns a request serial, it checks the result up to ten times at 1.5-second intervals.
Linked Item refreshes reuse the latest local sample. Polling can wake the T-Box and draw power from the 12 V battery. After upgrading, raise any existing interval below five minutes.

- **Login rejected** — verify the owner credentials in the BYD app and check the clock on the openHAB host.
- **Check the vehicle VIN** — verify that the car appears in this account and enter its VIN if the account contains multiple vehicles.
- **No usable sample** — check when the app last received a vehicle report. Automatic polling resumes after the configured interval.
- **BYD code 1003** — check the system time on the openHAB host. The client uses the server date to compensate for clock differences over 30 seconds. Login and command triggers are not replayed on clock errors.
- **Session expired** — the binding logs in again and retries the rejected request once.

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
