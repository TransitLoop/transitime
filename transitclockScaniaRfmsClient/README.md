# Scania rFMS 4.0 vehicle positions

This module reads vehicle positions from the Scania Data Access API (rFMS Standard 4.0) and can run on its own or as the AVL feed inside TransitClock.

TransitClock turns each fix into an `AvlReport`. The module that does that is `org.transitclock.custom.scania.ScaniaRfmsAVLModule`. It follows the same pattern as `TraccarAVLModule`: a poller started from `transitclock.modules.optionalModulesList`.

The production host is `https://dataaccess.scania.com`. Java 17 speaks TLS 1.2, which Scania requires.

## What you need from Scania

1. An account on the [Scania Developer Portal](https://developer.scania.com/). A dealer creates an **FMS Developer** user for a real fleet. A **Test user** can create a client, but that client only sees Scania's synthetic test vehicles.
2. A client created in the portal (**My Clients**). Creating it shows a **Client ID** and a **Secret key**. Store both. The portal will not show the secret again. Do not commit them or paste them into a log.
3. A subscription that this API accepts. rFMS 4.0 Vehicle Position requires **Data Access Location** or **Data Access Performance**. An older Data Access or Control package cannot call this endpoint. Scania returns HTTP 403 when the subscription is missing.

The vehicle position resource is:

```text
GET https://dataaccess.scania.com/rfms4/vehiclepositions
Accept: application/json; rfms=vehiclepositions.v4.0
Authorization: Bearer <token>
```

## Authentication

Scania does not use a password on the vehicle API, and the secret key is never sent over the wire. Each access token lasts about one hour. Each refresh token lasts about 24 hours. Refreshing returns a new refresh token and invalidates the one you just used. This client keeps both tokens in memory and refreshes the access token after 50 minutes. If refresh fails, it repeats the handshake below.

### 1. Ask for a challenge

```text
POST https://dataaccess.scania.com/auth/clientid2challenge
Content-Type: application/x-www-form-urlencoded

clientId=<client id>
```

The JSON body contains `challenge`.

### 2. Sign the challenge

Decode the secret key and the challenge as base64url. Compute HMAC-SHA256 with the secret as the key and the challenge as the message. Encode the digest as base64url without padding. That string is the challenge response. `ScaniaAuthClient.challengeResponse` does this.

### 3. Exchange it for a token

```text
POST https://dataaccess.scania.com/auth/response2token
Content-Type: application/x-www-form-urlencoded

clientId=<client id>&Response=<challenge response>
```

The form field is `Response` with a capital R. The JSON body contains `token` and `refreshToken`.

Send the access token on later calls:

```text
Authorization: Bearer <token>
```

### 4. Refresh

```text
POST https://dataaccess.scania.com/auth/refreshtoken
Content-Type: application/x-www-form-urlencoded

clientId=<client id>&RefreshToken=<refresh token>
```

The field name is `RefreshToken`. Replace the stored refresh token with the new one every time.

A 401 on a vehicle call drops the access token and retries once. If that still returns 401, the client repeats steps 1–3.

## Vehicle positions

Useful query parameters:

| Parameter | Role |
| --- | --- |
| `latestOnly=true` | Last position of each vehicle. This is the first poll. |
| `starttime` | UTC `YYYY-MM-DDThh:mm:ss`, inclusive. Required when `latestOnly` is not set. |
| `stoptime` | UTC upper bound, exclusive. Omit it to mean "now". |
| `datetype` | `received` (default here) or `created`. `received` is when Scania stored the report. `created` is when the vehicle produced it. |
| `vin` | One VIN. Omit it to receive every subscribed vehicle. |
| `triggerFilter` | Only reports caused by that trigger, for example `TIMER`. |
| `lastVin` | Next page when `latestOnly=true` and `moreDataAvailable` is true. The VIN itself is not repeated. |

The response lists `vin`, `triggerType`, `createdDateTime`, `receivedDateTime`, and `gnssPosition` (`latitude`, `longitude`, `heading`, `speed` in km/h, `positionDateTime`). `wheelBasedSpeed` is used when GNSS speed is absent. Heading is degrees clockwise from north. A value of 360 is stored as 0.

TransitClock speed is metres per second. The client multiplies km/h by `0.277778`.

`moreDataAvailable: true` means the page is incomplete. The client follows `moreDataAvailableLink` when Scania sends one, and only when that link stays on the configured host. Otherwise, with `latestOnly`, it sends `lastVin`. For a time range it sends the last report's `receivedDateTime` as the next `starttime`.

`requestServerDateTime` is Scania's clock when it accepted the request. After a complete poll the AVL module uses that value as the next `starttime`. That avoids gaps and overlaps caused by the TransitClock host's clock.

The AVL module then:

1. Polls with `latestOnly=true` so every vehicle gets a current fix.
2. On later polls, if `transitclock.avl.scania.incremental` is true (the default), requests positions received since `requestServerDateTime`.
3. Sorts fixes by GPS time and drops a fix that is not newer than the previous one for that vehicle.
4. Builds an `AvlReport` and passes it to the normal TransitClock AVL pipeline.

`positionDateTime` is the GPS time. If it is missing, `createdDateTime` is used, then `receivedDateTime`.

Scania allows **10 requests per minute per customer**. The client waits at least 6 seconds between HTTP calls (`transitclock.avl.scania.minIntervalMillis`). Set `transitclock.avl.feedPollingRateSecs` to **15 or more**. The project default of 5 seconds sits on the rate limit, and the hourly token refresh then receives HTTP 429. A 429 is logged and the next poll tries again.

Run one Core process for these credentials. Two pollers share the same 10-request budget.

## Match VINs to GTFS vehicle ids

TransitClock matches `AvlReport.vehicleId` to the GTFS `vehicle_id` / block assignment. Scania's position id is the VIN (17 characters, ISO 3779). That fits the 60-character vehicle id column.

- If the GTFS vehicle id **is** the VIN, leave the map unset.
- If the GTFS vehicle id is a fleet number, create a properties file:

```properties
# VIN = GTFS vehicle_id
YS2R4X20002123456=1201
YS2R4X20002123457=1202
```

Point `transitclock.avl.scania.vinMapFile` at that file. Lookup is case-insensitive. A VIN that is not in the file is reported under the VIN itself. The file is re-read when its modification time changes, including after a Core restart.

`transitclock.avl.scania.vins` limits the feed to a comma- or semicolon-separated VIN list. One VIN is sent to Scania as the `vin` parameter. Several VINs are filtered locally after reading the fleet, which uses fewer requests than one call per vehicle.

The tachograph driver id, when Scania includes it, is stored on the AVL report (60 characters maximum). The `source` column is 10 characters. The default is `Scania`.

Do not run a second AVL module for the same vehicles. Two feeds will fight over the same vehicle id.

## TransitClock configuration

Core reads `transitclock.configFiles`. In an XML file the element path is the property name. This block sets the properties the module reads:

```xml
<transitclock>
  <modules>
    <optionalModulesList>org.transitclock.custom.scania.ScaniaRfmsAVLModule</optionalModulesList>
  </modules>
  <avl>
    <feedPollingRateSecs>15</feedPollingRateSecs>
    <scania>
      <clientId>YOUR_CLIENT_ID</clientId>
      <secretKey>YOUR_SECRET_KEY</secretKey>
      <baseUrl>https://dataaccess.scania.com</baseUrl>
      <incremental>true</incremental>
      <dateType>received</dateType>
      <triggerFilter></triggerFilter>
      <vins></vins>
      <vinMapFile>/etc/transitclock/scania-vin-map.properties</vinMapFile>
      <source>Scania</source>
      <timeoutMillis>30000</timeoutMillis>
      <minIntervalMillis>6000</minIntervalMillis>
      <maxPages>20</maxPages>
    </scania>
  </avl>
</transitclock>
```

Join several optional modules with a semicolon. A `-Dtransitclock.avl.scania.clientId=...` system property overrides the file. The client id and secret are not written to the log.

Restart Core after changing the secret. Tokens live only in the module's memory.

| Property | Default | Meaning |
| --- | --- | --- |
| `transitclock.avl.scania.clientId` | empty, module will not start | Portal client id |
| `transitclock.avl.scania.secretKey` | empty, module will not start | Portal secret key |
| `transitclock.avl.scania.baseUrl` | `https://dataaccess.scania.com` | API host |
| `transitclock.avl.scania.incremental` | `true` | After the snapshot, poll from the last server time |
| `transitclock.avl.scania.dateType` | `received` | Which timestamp `starttime` is compared to |
| `transitclock.avl.scania.triggerFilter` | empty | Optional trigger name |
| `transitclock.avl.scania.vins` | empty | Limit to these VINs |
| `transitclock.avl.scania.vinMapFile` | empty | `VIN=vehicleId` file |
| `transitclock.avl.scania.source` | `Scania` | AVL source label, max 10 characters |
| `transitclock.avl.scania.timeoutMillis` | `30000` | Connect and read timeout |
| `transitclock.avl.scania.minIntervalMillis` | `6000` | Gap between HTTP calls |
| `transitclock.avl.scania.maxPages` | `20` | Pages per poll. A further page continues on the next poll |
| `transitclock.avl.feedPollingRateSecs` | `5` in core | Use 15 or more for this feed |

Build from the repository root:

```text
mvn -pl transitclock -am package -DskipTests
```

`Core.jar` includes this client. Start Core the way you already do, with `transitclock.configFiles` pointing at the file above.

## Try the API before starting Core

From the repository root, copy the environment template and fill in the portal values. Do not commit that file.

```text
cp transitclockScaniaRfmsClient/.env.example transitclockScaniaRfmsClient/.env
export SCANIA_CLIENT_ID='your client id'
export SCANIA_SECRET_KEY='your secret key'
mvn -pl transitclockScaniaRfmsClient exec:java
```

That prints the latest position of every subscribed vehicle. Useful filters:

```text
mvn -pl transitclockScaniaRfmsClient exec:java -Dexec.args="--vin YS2R4X20002123456"
mvn -pl transitclockScaniaRfmsClient exec:java -Dexec.args="--start 2024-06-01T00:00:00Z --stop 2024-06-01T01:00:00Z"
mvn -pl transitclockScaniaRfmsClient exec:java -Dexec.args="--trigger-filter TIMER --date-type received"
```

`--start` reads a time range instead of the latest fix. Times are UTC.

## Rotate or reset the secret

Rotation and reset are deliberate. The AVL module never calls them.

**Rotate** (`POST /auth/rotatekey`) returns a new `currentSecret` and keeps the previous secret working for 24 hours. Update the config and restart Core within that day.

**Reset** (`POST /auth/resetkey`) invalidates the current and previous secrets immediately and returns a new one. Use it when a secret has leaked. The running Core will fail authentication until you install the new secret and restart.

Both calls need a valid access token, and Scania allows a given client id to be rotated or reset at most once every 24 hours (HTTP 429 before that).

```text
mvn -pl transitclockScaniaRfmsClient exec:java -Dexec.args="--rotate-key"
```

The new secret is printed on the terminal. Put `currentSecret` into `transitclock.avl.scania.secretKey`. Do not leave it in shell history if you can avoid it; prefer the environment variables above and a private config file.

`--reset-key` is the immediate replacement.

## HTTP status codes

| Status | Meaning | What to do |
| --- | --- | --- |
| 200 | Success | |
| 400 | Missing field, often the `Authorization` header | Check the client id and secret |
| 401 | Token rejected | The client refreshes or signs in again. If it persists, the secret is wrong |
| 403 | Subscription does not include this API | Ask Scania for Data Access Location or Performance |
| 404 | None of the requested VINs belong to this customer | Check `vins` |
| 406 | `Accept` header is not the rFMS 4.0 vehicle-position type | Keep the header this client sends |
| 429 | More than 10 requests in a minute, or a key change inside 24 hours | Raise `feedPollingRateSecs`. Wait before rotating again |
| 500 | Scania error | The next poll tries again |

A log line includes the HTTP status and a short response body. It does not include the secret or the access token.

## Tests

```text
mvn -pl transitclockScaniaRfmsClient test
```

The tests cover the HMAC challenge, both JSON shapes Scania and the rFMS document use, the request URL, pagination links, and the VIN map. They do not call Scania.
