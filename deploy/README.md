# Deploy TransitClock on MicroK8s

The image is built from this repository, so it includes the Scania rFMS module. It runs one container: PostgreSQL schema and GTFS import, then the RMI registry, Core, Tomcat, and nginx. Nginx publishes the feeds on port 8080.

| Path | Response |
| --- | --- |
| `/gtfs-rt/trip-updates` | GTFS-RT TripUpdates |
| `/gtfs-rt/vehicle-positions` | GTFS-RT VehiclePositions |
| `/api/` | TransitClock API |
| `/web/` | TransitClock web app |
| `/health` | Process is up |
| `/ready` | Core and Tomcat are serving |

Inside the cluster the service is `http://transitime.mdinamapper.svc.cluster.local:8080`.

Scania authentication stays inside the running process. The access token is refreshed on the next poll after 50 minutes, and that refresh also replaces the 24-hour refresh token. No cron is required. Run one replica: Scania allows 10 requests per minute, and the tokens live only in this process.

The same manifests are copied to `MdinaMapperServer/k8s/transitime.yaml` and `MdinaMapperServer/k8s/transitime-ingress.yaml` so they can be applied with the other services.

## 1. Build and push the image

The MicroK8s nodes need `linux/amd64`. Docker Desktop needs about 6 GB of memory for the Maven build.

```bash
cd transitime
docker login
PUSH=1 ./deploy/build-and-push.sh
```

`PUSH=1` sends `transitloop/transitime:latest` to Docker Hub. Omit `PUSH=1` to build the tag locally only. The cluster already pulls through the `docker-registry-secret` in the `mdinamapper` namespace.

## 2. Set the agency id

Edit `transitclock.core.agencyId` in `deploy/k8s/transitime.yaml`. It has to be the `agency_id` from the GTFS file you will import. The database name and user stay `transitclock` and `postgres`, matching the secret below.

`transitclock.avl.feedPollingRateSecs` is 15. Scania's limit is 10 requests per minute, and a faster poll makes the hourly token refresh receive HTTP 429.

Add VIN lines to `scania-vin-map.properties` in that same ConfigMap when the GTFS vehicle id is a fleet number. A VIN that is not listed is reported as the VIN.

Leave the client id, secret key, and database password out of this file. The container passes them as system properties, which override the file:

- `SCANIA_CLIENT_ID` becomes `-Dtransitclock.avl.scania.clientId`
- `SCANIA_SECRET_KEY` becomes `-Dtransitclock.avl.scania.secretKey`
- `PGPASSWORD` becomes `-Dtransitclock.db.dbPassword`

`TRANSITCLOCK_CONFIG_FILES` is the mounted file, passed as `-Dtransitclock.configFiles`.

## 3. Create the secret

On the MicroK8s host, in the namespace that already runs MdinaMapper:

```bash
microk8s kubectl -n mdinamapper create secret generic transitime-secrets \
  --from-literal=SCANIA_CLIENT_ID='your client id' \
  --from-literal=SCANIA_SECRET_KEY='your secret key' \
  --from-literal=POSTGRES_USER='postgres' \
  --from-literal=POSTGRES_PASSWORD='a strong password without spaces' \
  --from-literal=POSTGRES_DB='transitclock' \
  --dry-run=client -o yaml | microk8s kubectl apply -f -
```

The database password is also the PostgreSQL password. Avoid spaces, quotes, and `$` in that password. `POSTGRES_DB` must stay `transitclock` unless you change `transitclock.db.dbName` to the same value.

## 4. Apply the workload

```bash
microk8s kubectl apply -f deploy/k8s/transitime.yaml
microk8s kubectl -n mdinamapper rollout status statefulset/transitime-db
microk8s kubectl -n mdinamapper get pods -l app=transitime
```

From the MdinaMapperServer checkout the same file is `k8s/transitime.yaml`.

The pod stays running and not Ready until a GTFS zip is present. `/health` returns 200 while it waits. `/ready` returns 503.

## 5. Load the GTFS zip

```bash
POD=$(microk8s kubectl -n mdinamapper get pod -l app=transitime -o jsonpath='{.items[0].metadata.name}')
microk8s kubectl -n mdinamapper cp google_transit.zip "$POD:/data/gtfs/gtfs.zip"
microk8s kubectl -n mdinamapper logs -f deploy/transitime
```

The zip is stored on the `transitime-gtfs-pvc` volume. The entrypoint imports it when the file size stops changing, creates the API key and WebAgency, and starts Core. The first import can take several minutes. A later pod start skips the import when the zip checksum is unchanged.

To publish the feeds outside the cluster, point `transitime.blassty-transitloop.net` at the ingress address and apply:

```bash
microk8s kubectl apply -f deploy/k8s/transitime-ingress.yaml
```

Change the host in that file if the DNS name is different. Skip this file when only other pods in `mdinamapper` should call TransitClock.

## 6. Check that it is serving

```bash
microk8s kubectl -n mdinamapper exec deploy/transitime -- curl -fsS http://127.0.0.1:8080/ready
microk8s kubectl -n mdinamapper logs deploy/transitime | grep 'Scania access token'
```

`Scania access token created` is the first handshake. Later polls log `Scania access token refreshed`. A 401 makes the client refresh and, if that fails, repeat the challenge handshake. Restart the pod after you rotate the Scania secret.

## Change the config or the secret

Edit the ConfigMap or replace the secret with the same `kubectl create secret ... --dry-run=client` command, then:

```bash
microk8s kubectl -n mdinamapper rollout restart deployment/transitime
microk8s kubectl -n mdinamapper rollout status deployment/transitime
```

Core reads `transitclock.configFiles` at startup. A ConfigMap edit does not affect the running process until the pod restarts.

## Memory

The container limit is 4 GiB. Core's heap is `CORE_XMX` (1536m) and Tomcat's heap is 512m. Raise both in the Deployment if the GTFS import runs out of memory, and keep the limit above the two heaps combined.
