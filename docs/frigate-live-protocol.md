**English** | [Русский](frigate-live-protocol.ru.md)

# Frigate PWA: API specification of the "Live" tab

## 1. Purpose and scope

The document describes the network contract and display rules of the PWA "Live" tab: the list of cameras, still images, activity and objects, starting live, the still ↔ video transition, connection recovery and return from the background.

The specification is reconstructed from the Frigate and go2rtc sources, including the calling dashboard/grid players and the selected-camera screen. It describes existing behaviour, not a proposal of a new API. Research date: 2026-10-29. The checkout that was studied is not matched to the version of the installed server; differences between releases are possible. Sources — in appendix A.

Scope: viewing cameras and the feed of the latest alerts. PTZ control, configuration changes, two-way audio and the archive are not covered by this document.

**The camera state, the still and the live video are independent channels.** An object message contains no video; an open media socket does not mean that the first frame is ready.

## 2. Addressing and authorisation

Notation:

- `BASE` — the external HTTP(S) URL of Frigate with a trailing `/`, including the deployment prefix;
- `WS_BASE` — the same URL with `https` → `wss` and `http` → `ws` replaced;
- `camera` — the technical camera name, not the `friendly_name`;
- `stream` — a value from `cameras[camera].live.streams`, not necessarily the camera name.

For example, with `BASE = https://nvr.example/frigate/` the configuration is available at `https://nvr.example/frigate/api/config`, and the state at `wss://nvr.example/frigate/ws`.

The PWA uses the session of the current origin. The browser passes the suitable session cookies in the HTTP and WebSocket upgrade. Login/password and the cookie are not query parameters of media URLs. On a protected server a valid session is needed before Live loads. The backend also supports Bearer authorisation, but the PWA's browser WebSocket does not set an arbitrary `Authorization` header.

The server checks the rights to cameras and WS topics. Connecting to `/ws` does not grant access to all cameras. An authorisation refusal is not an empty snapshot and not `motion=OFF`.

### 2.1. API map

Paths are relative to `BASE` or `WS_BASE`:

| Purpose | Transport | Endpoint |
|---|---|---|
| Cameras, configuration, stream mapping | HTTP GET | `api/config` |
| Latest still | HTTP GET | `api/{camera}/latest.webp` |
| Initial statistics, including offline | HTTP GET | `api/stats` |
| Runtime state, motion, objects | WebSocket | `ws` |
| Restream metadata | HTTP GET | `api/go2rtc/streams/{stream}` |
| MSE live | WebSocket | `live/mse/api/ws?src={stream}` |
| WebRTC signaling | WebSocket | `live/webrtc/api/ws?src={stream}` |
| JSMpeg live | WebSocket | `live/jsmpeg/{camera}` |
| Latest unreviewed alerts | HTTP GET | `api/review?limit=10&severity=alert&reviewed=0&cameras={camera-list}` |

When building URLs, path segments and query values must be encoded correctly. `camera` must not be substituted for `stream` without checking.

## 3. Configuration and stream selection

### 3.1. `GET api/config`

Response: `200`, JSON. Below is the **projection of the fields for Live**, not the full configuration response; the values are illustrative:

```json
{
  "cameras": {
    "entrance": {
      "name": "entrance",
      "friendly_name": "Вход",
      "enabled": true,
      "detect": {"enabled": true, "width": 1280, "height": 720},
      "record": {"enabled": true},
      "live": {
        "streams": {
          "Основной": "entrance_main",
          "Обзор": "entrance_sub"
        }
      }
    }
  },
  "go2rtc": {
    "streams": {"entrance_main": [], "entrance_sub": []},
    "webrtc": {"ice_servers": []}
  },
  "camera_groups": {},
  "birdseye": {"enabled": false, "restream": false}
}
```

Rules:

1. The camera key/`name` is the identifier for the API and WS. `friendly_name` is the label.
2. `live.streams` is a mapping **label → stream ID**. Snapshots and object events are addressed by camera; MSE/WebRTC — by stream ID.
3. The dashboard selects the stream saved for the camera/group if it is among the values of `live.streams`. Otherwise — the first value, or an empty string if there are no streams.
4. The group and the user's rights determine the composition of the dashboard. The user's streaming preferences do not replace the server configuration.
5. `record.enabled` is the recording permission — not the current activity and not a condition for automatically starting live.
6. Runtime feature states are refined through `/ws`. The old configuration must not be re-applied on top of every new object event.

### 3.2. Restream metadata

```http
GET api/go2rtc/streams/entrance_main
```

The response is the JSON description of the go2rtc stream, including producers/consumers and media/codecs. The PWA uses the metadata to determine the available audio and the capabilities of the selected stream. It is neither video data nor an activity signal. The dashboard loads the metadata lazily, without blocking the first still on it.

## 4. Still images

### 4.1. Request

```http
GET api/entrance/latest.webp?height=360&cache=1793221200123
```

| Parameter | Type | Meaning |
|---|---|---|
| `height` | integer | Image height; the width keeps the proportions |
| `cache` | value in the URL | The PWA's cache-buster; not the time of the requested frame and not a history cursor |
| `store` | integer | At `1` allows a private HTTP cache of 60 s; without it the response is `no-store` |
| `quality` | integer | Encoding quality; backend default `70`, the regular Live tile does not set it |

The backend also accepts `bbox`, `timestamp`, `zones`, `mask`, `motion`, `paths`, `regions` for diagnostic layers. A regular still in Live does not request them.

The PWA chooses a height no greater than `detect.height`: roughly the container height × 1.1 on desktop or × 1.25 on mobile; before measurement — `360`.

With periodic cache enabled, the first request contains `store=1` and `cache`, rounded to a 10-minute interval of Unix time in seconds. Subsequent updates use the current `Date.now()` in milliseconds. These values do not set the time of a video frame.

### 4.2. Response

Success: `200`, `Content-Type: image/webp`, a binary image.

- By default `Cache-Control: no-store`.
- With `store=1`: `Cache-Control: private, max-age=60`.
- When the camera is offline the backend may return the last preview with `X-Frigate-Offline: true`.
- Unknown camera: `404`, JSON `{"success":false,"message":"Camera not found"}`.
- Invalid computed dimensions: `400`; inability to obtain a usable frame: `500`, JSON `{"success":false,"message":"Unable to get valid frame"}`.
- HTTP 200 and the presence of a picture do not prove that the camera is online: a fallback to a preview/error image is possible.

The still is the current frame of the camera's processing path — not a snapshot of a specific object event and not a frame of the selected go2rtc stream. The proportions and resolution of still and live may differ.

### 4.3. Dashboard update frequency

The first matching rule from top to bottom applies:

| Condition | Interval, ms |
|---|---:|
| Tile/window invisible, camera offline, or a mode without still | `-1`: stop updates |
| Live is ready, activity has already ended | `300` |
| Live is ready | `60000` |
| There is motion/tracking, auto-live is on, live is not ready yet | `200` |
| There is motion/tracking, auto-live is off | `59000` |
| Other cases | `30000` |

This is client-side polling, not server FPS. After a successful load the PWA waits another `interval` ms; if the load itself took longer than `interval`, the next request is scheduled after `1` ms. So the usual period between request starts includes both the load time and the delay after it. On error, the retry for a running loop is no more often than `max(interval, 1000)` ms. `-1` disables repeated updates; it does not set a negative delay.

## 5. Frigate state WebSocket

### 5.1. Connection and envelope

```text
WS_BASE + ws
```

One shared WebSocket serves the runtime state of the cameras. The PWA does not send a separate subscription command for each camera.

Client → server, bootstrap:

```json
{"topic":"onConnect","message":"","retain":false}
```

Server → client:

```json
{"topic":"entrance/motion","payload":"ON"}
```

| Field | Direction | Purpose |
|---|---|---|
| `topic` | both | Name of the command/channel |
| `message` | client → server | A field of the PWA's outgoing envelope; for `onConnect` an empty string |
| `retain` | client → server | A flag of the envelope, not a request to replay WS history |
| `payload` | server → client | The value of the topic; complex messages usually contain a JSON string |

`onConnect` is handled by its topic name; its value is not used. The outgoing envelope shown here must not be automatically carried over to any other control commands.

**A real text frame** with a complex payload has two levels of JSON:

```json
{"topic":"camera_activity","payload":"{\"entrance\":{\"motion\":false,\"objects\":[],\"config\":{\"enabled\":true,\"record\":true,\"detect\":true}}}"}
```

First the envelope is parsed, then the JSON inside `payload`. Further examples of complex messages show the **decoded payload**, without the outer envelope.

### 5.2. `onConnect`: requesting the current state

Opening the socket by itself does not request a snapshot. After `open` the PWA sends `onConnect`.

The server publishes a runtime snapshot, including `camera_activity`; `audio_detections`, `birdseye_layout`, `model_state`, `profile/state` and other service topics may also arrive. These are several messages, not a single atomic response.

Properties:

- There is no request ID and no separate ACK.
- The snapshot is sent to connected clients with rights filtering; it may be triggered by another tab/client.
- There is no sequence number, revision boundary, cursor or replay of missed events.
- The snapshot and parallel events do not form a transaction.
- The **current state** is restored, not the history for the time of the disconnection.

### 5.3. `camera_activity`: camera snapshot

Decoded payload:

```json
{
  "entrance": {
    "motion": true,
    "objects": [
      {
        "id": "1793221200.123456-ab12cd",
        "label": "person",
        "stationary": false,
        "area": 32000,
        "ratio": 0.45,
        "score": 0.87,
        "sub_label": ""
      }
    ],
    "audio_detections": [],
    "config": {
      "enabled": true,
      "detect": true,
      "snapshots": true,
      "record": true,
      "audio": false,
      "audio_transcription": false,
      "notifications": false,
      "notifications_suspended": 0,
      "autotracking": false,
      "alerts": true,
      "detections": true,
      "object_descriptions": false,
      "review_descriptions": false
    }
  }
}
```

| Field | Type | Meaning |
|---|---|---|
| Top-level key | string | Camera name |

| `motion` | boolean | Motion in the snapshot, not a historical interval |
| `objects` | array | The current view of the tracked objects |
| `objects[].id` | string | Identity of the object within the camera |
| `objects[].label` | string | Class/label |
| `objects[].stationary` | boolean | The object is considered stationary |
| `objects[].area`, `ratio`, `score` | number | Object attributes, not conditions for starting live |
| `objects[].sub_label` | string | An additional label displayed in the snapshot |
| `config.*` | boolean, except the next row | Runtime states of the features |
| `config.notifications_suspended` | number | The value of notification suspension, not a boolean |
| `audio_detections` | array | Audio detections; a separate audio topic also updates them |

In the backend studied, the runtime snapshot is collected for all configured cameras and is limited by the recipient's rights. This is **not a documented stream of partial per-frame patches**. A camera without accumulated observations may contain only `config`, without `motion`/`objects`.

Application in the PWA:

1. Expand the snapshot into the local states `camera_activity/{camera}` and the feature state topics.
2. For a camera that is present, replace objects with `objects`, or with `[]` if the field is absent. Do not merge with the old list of objects.
3. An empty top-level object is ignored. The absence of a camera in the map is not treated as a command to end all of its objects.
4. A repeated snapshot reconciles the state, even if it matches the previous one: objects could have changed in between via `events`.
5. The snapshot is applied on receipt, not on every subsequent UI render.

### 5.4. `events`: changes of tracked objects

Decoded payload, a projection of the fields for Live:

```json
{
  "type": "update",
  "before": {
    "id": "1793221200.123456-ab12cd",
    "camera": "entrance",
    "label": "person",
    "stationary": false
  },
  "after": {
    "id": "1793221200.123456-ab12cd",
    "camera": "entrance",
    "label": "person",
    "stationary": true,
    "sub_label": null,
    "area": 32000,
    "ratio": 0.45,
    "score": 0.87
  }
}
```

- `type`: `new`, `update`, `end` in the publisher studied. `start` is not its regular type.
- `before`: the previously published view of the object, not necessarily the previous video frame.
- `after`: the new view; a full event contains additional fields of time, geometry, zones, media flags and recognition.
- `sub_label` in the event is `null` or an array with a label and confidence. This is **not the same type** as the `sub_label` string in the displayed snapshot.

Rules:

1. Route by `after.camera`, find the object by `after.id`, not by `label`.
2. `end` removes only that object; an unknown ID removes nothing.
3. For an unknown non-stationary object, `new`/`update` adds the object. The PWA does not add an unknown stationary object.
4. For a known object, update the displayed label and `stationary`. Keep the other IDs and cameras.
5. For `sub_label` the PWA uses it as the label if it is a known attribute label; otherwise it displays the verified variant of the original class. The additional label is taken from the first element of `sub_label`.
6. `activeTracking = there is at least one object with stationary=false`. Stationary objects may remain in the list, but do not enable tracking.

Not every frame produces an `update`. The topic is neither a heartbeat nor a reliable journal of all transitions. After a disconnect a new snapshot is required.

### 5.5. Motion and feature states

Full envelope, without a second JSON parse:

```json
{"topic":"entrance/motion","payload":"ON"}
```

| Topic | Payload | Meaning |
|---|---|---|
| `{camera}/motion` | `"ON"` / `"OFF"` | Motion activity |
| `{camera}/enabled/state` | `"ON"` / `"OFF"` | Camera enabled/disabled |
| `{camera}/detect/state` | `"ON"` / `"OFF"` | Detection allowed/disabled |
| `{camera}/recordings/state` | `"ON"` / `"OFF"` | Recording allowed/disabled |

The motion topic publishes changes, not a periodic heartbeat. `OFF` may be delayed according to `motion.mqtt_off_delay`. The motion in the snapshot reflects the instantaneous processing state and may differ from the delayed OFF topic.

For `activeMotion` the PWA uses the received motion topic; if it is absent, `camera_activity.motion === true`. A disabled camera suppresses the displayed motion/tracking/objects.

`{camera}/motion` and `{camera}/motion/state` are different channels: activity and feature setting. `recordings/state=ON` does not mean "a recording segment is being written right now".

### 5.6. Statistics and audio

Initial statistics: `GET api/stats`. Subsequent updates — the `stats` topic, JSON payload. For offline the following projection is essential:

```json
{"cameras":{"entrance":{"camera_fps":0}},"service":{"uptime":125}}
```

Offline in the PWA: `camera_fps == 0` and `service.uptime > 60` seconds. Until stats are received, the absence of data by itself does not declare the camera offline.

The `audio_detections` topic contains a JSON map `camera → detections`; the elements have `id`, `label`, `score`. Audio is updated separately. The auto-live formula uses motion/tracking, not the presence of audio detections.

## 6. Display rules

### 6.1. Dashboard and the selected camera

| Mode | Auto-live | Still without activity | Behaviour |
|---|---|---|---|
| Default dashboard | On by default, can be turned off | Yes | Live on motion/tracking of a visible camera |
| `no-streaming` | No | Yes | Still polling, no media player |
| `continuous` | Yes | No | Live regardless of motion/tracking |
| Selected camera | After preferences load | No | Continuous live of the selected stream |

On the dashboard `windowVisible` takes into account the visibility of the document **and** whether the camera is in the visible area. This is a client-side policy, not a server topic. On the selected-camera screen the activity gate is disabled; background playback is set separately.

### 6.2. Start formula

```text
cameraActive = !showStillWithoutActivity
               OR (windowVisible AND (activeMotion OR activeTracking))

playerExists = autoLive AND streamName is non-empty AND cameraEnabled
playbackEnabled (MSE/WebRTC) = cameraActive OR liveReady
```

`record.enabled`/`recordings/state` do not appear in the formula. Disabling recording is not the same as disabling live. The end of one object does not switch off other cameras or another object of the same camera.

### 6.3. Transition still → video

An ordinary activity-driven tile:

```text
STILL → STARTING_LIVE → LIVE → RETURNING_TO_STILL → STILL
```

| State | Display and action |
|---|---|
| STILL | The still is visible and updated periodically; media is not played |
| STARTING_LIVE | Media starts; the still stays visible and is updated at a 200 ms interval |
| LIVE | The readiness callback sets `liveReady=true`; the video is shown, the still is hidden |
| RETURNING_TO_STILL | When activity disappears, live is kept for up to 500 ms, the still is updated at a 300 ms interval; then `liveReady=false` and playback is turned off |

If activity returns within 500 ms, the deferred reset is cancelled. This is a delay of **stopping**, not of starting.

For MSE the readiness callback is invoked on `loadeddata`; there is a fallback on progress when there is more than 3 s of buffer. An open socket or a received MIME does not yet mean that visible video is ready.

When the stream changes, the MSE/WebRTC player is created anew; `liveReady` is reset, and the new video stays hidden until it is ready. JSMpeg is bound to the camera, not to the go2rtc stream, so changing the stream ID does not switch it.

The seamless still overlay applies to the activity-driven still mode. On the selected-camera screen `showStillWithoutActivity=false`; such an overlay is not guaranteed there.

### 6.4. Indicators

- The motion marker reflects `activeMotion`, not the state of the media socket.
- Object icons/labels are built from the current objects; identical labels do not mean one ID.
- Disabled and offline are independent states with overlays.
- The recording permission does not confirm the current recording of a file. Motion/tracking must not be presented as a reliable signal of a segment being recorded.
- Playback, motion, tracking, the recording permission and camera availability are stored independently.

## 7. MSE: go2rtc media WebSocket

### 7.1. Endpoint

```text
WS_BASE + live/mse/api/ws?src=entrance_main
```

A connection for the stream, separate from `/ws`. Frigate proxies it to go2rtc. There is no `topic`, `payload` or `onConnect` here.

### 7.2. Negotiation

Client → server, text frame:

```json
{"type":"mse","value":"avc1.640029,hvc1.1.6.L153.B0,mp4a.40.2,opus"}
```

`value` is the proposed codecs separated by commas. The PWA checks browser support and offers suitable variants from: `avc1.640029`, `avc1.64002A`, `avc1.640033`, `hvc1.1.6.L153.B0`, `mp4a.40.2`, `mp4a.40.5`, `flac`, `opus`.

Server → client, text frame, example:

```json
{"type":"mse","value":"video/mp4; codecs=\"avc1.640029,mp4a.40.2\""}
```

The response contains the MIME of the selected stream, not an echo of the offer. The client creates a `SourceBuffer` with this MIME. The PWA allows audio; the contract is not limited to the video-only implementation of another client.

The codec parser of the go2rtc studied recognises `avc1.640029`, `hvc1.1.6.L153.B0`, `mp4a.40.2`, `flac`, `opus` separately. Therefore the PWA's list of candidates is not equal to the list of profiles the server supports independently; adding a token to the offer by itself does not guarantee negotiation or transcoding.

On an error go2rtc uses a text message of type `error` with the description in `value`; an upgrade error and closing of the socket are also possible. A JSON error must not be fed to the MP4 decoder.

### 7.3. Binary media

After the MIME, binary frames with MP4 bytes arrive:

1. An initialization segment with the description of tracks/codecs.
2. A sequence of fragmented MP4 media (`moof`/`mdat`).

Data is fed to the decoder in the order received. This is neither JPEG/WebP nor a Frigate envelope. When the `SourceBuffer` is busy, the PWA uses a queue of up to 2 MiB — a client limit, not a segment size set by the protocol.

A new video consumer waits for a suitable keyframe. Init/MIME does not guarantee an immediately decodable frame. The protocol does not contain a mandatory wait of 5–10 s and does not guarantee the same start time for all cameras. It depends on the source, keyframes, delivery, buffer and decoder.

### 7.4. Lifecycle in the PWA

- On opening, a fresh MediaSource is created and a new offer is sent.
- `playbackEnabled=false` closes the media connection.
- A hidden document turns MSE off if background playback is not allowed; on return a new connection is made.
- While the playback request persists, the player uses reconnect: a base interval of 10 s is counted from the start of the previous connection, so after a late disconnect the retry may be immediate; an explicitly set retry timeout overrides the calculation.
- The startup/fallback deadline is set by the user's `liveFallbackTimeout`, default 3 s. This is a reaction deadline for a problem, not a mandatory playback delay.
- The retained buffer is limited to about 15 s; the PWA catches up to the live edge, including at a speed of up to 2×. Details differ for Safari.
- Closing the media socket does not stop recording and does not end object events.

## 8. Transport selection and alternatives

### 8.1. Dashboard selection

1. Restreamed stream + browser MSE → MSE.
2. Without MSE, if restream and WebRTC are available → WebRTC.
3. Otherwise → JSMpeg.
4. The user's saved choice applies if the transport is available.

On `mse-decode`/`mse-codec` the dashboard switches to WebRTC if it is available. For other handled errors — to JSMpeg. Ending activity may reset the preferred mode to the original choice.

It must not be assumed that the PWA always uses MSE in all browsers and settings.

### 8.2. WebRTC

```text
WS_BASE + live/webrtc/api/ws?src=entrance_main
```

JSON text signaling, without the Frigate topic envelope:

| Direction | `type` | `value` |
|---|---|---|
| Client → server | `webrtc/offer` | SDP offer as a string |
| Server → client | `webrtc/answer` | SDP answer as a string |
| Both | `webrtc/candidate` | ICE candidate as a string |

The client creates a PeerConnection, recvonly video/audio transceivers, a local offer, and passes the SDP. The answer sets the remote description; candidates are added to ICE. The `go2rtc.webrtc.ice_servers` settings take part in building the PeerConnection.

Video travels over the WebRTC media transport, not as binary MP4 through the signaling socket. An SDP answer does not prove that ICE was established or that a frame is decoded.

### 8.3. JSMpeg

```text
WS_BASE + live/jsmpeg/entrance

```

The **camera** is selected, not a value of `live.streams`. Binary MPEG-TS/MPEG-1 video for the JSMpeg decoder arrives over the socket. There is no MSE codec offer. This is an alternative path, not the same H.264/H.265 fMP4 at a different URL.

### 8.4. Birdseye

A composite view, not an ordinary camera with an object map. The dashboard selects MSE when `birdseye.restream=true`, otherwise JSMpeg; the source ID is `birdseye`. It must not be started only on the condition `events.after.camera == birdseye`.

## 9. Bootstrap, reconnect and return

### 9.1. Sequence

```text
1. A valid session for the required origin.
2. GET api/config → cameras, labels, stream IDs.
3. Open /ws → on open send onConnect.
4. camera_activity → reconcile objects and feature states.
5. GET api/stats → initial availability; then the stats topic.
6. latest.webp for the visible still tiles.
7. Apply motion/events/feature states independently of images.
8. If needed, live → a separate media socket of the camera/stream.
9. Until live is ready, keep the still wherever still mode is on.
```

HTTP, images and opening `/ws` may run in parallel. The numbering shows dependencies, not a requirement to serialise all requests.

### 9.2. Recovery

After a shared `/ws` is closed, the PWA uses exponential reconnect: roughly 1, 2, 4, 8, 16, then up to 30 s between attempts. After `open` it sends `onConnect` again, invalidating the camera activity deduplication cache.

On document visibility → `visible` the camera state hooks request `onConnect` again; simultaneous requests are merged. This is needed even without an explicit disconnect: the browser may have suspended the page.

A repeated snapshot must not be discarded just because it matches the previous one: objects could have changed in between via `events`.

There is no WS replay. Missed `new`/`end` cannot be requested by offset. A new snapshot replaces the current view of the objects of the cameras present. Historical alerts are read through the REST review, not through a replay of object events.

### 9.3. Guarantees and limitations

- Messages of one active socket are ordered by the transport.
- There is no common order between `/ws`, HTTP stills and media sockets.
- There is no ACK of each event by the client.
- A slow client may be disconnected on queue overflow/send timeout.
- There is no atomic snapshot+cursor: exactly-once and gap-free recovery are not guaranteed.
- The absence of new motion messages does not prove a broken connection: this is an edge topic.
- The presence of an old still does not prove that the runtime state is current.

## 10. Dashboard alert feed

This is a contract separate from the tile activity:

```http
GET api/review?limit=10&severity=alert&reviewed=0&cameras=entrance,yard
```

`cameras` is a comma-separated list of technical names, taking the group/rights into account.

The response is an array of review segments; projection:

```json
[
  {
    "id": "1793221200.123456-review",
    "camera": "entrance",
    "severity": "alert",
    "start_time": 1793221200.123456,
    "end_time": null,
    "thumb_path": "<server thumbnail path>",
    "has_been_reviewed": false,
    "data": {"objects": ["person"]}
  }
]
```

Times are Unix seconds; an ongoing segment has no end time. The full object contains additional review data.

Updates arrive on the `reviews` topic, **not** `events`. The payload has `type`, `before`, `after`, where `after` is the review segment. The dashboard re-requests REST, not treating the WS as an exhaustive copy of the list:

| Change of alert | Revalidation delay |
|---|---:|
| `end` | 1 s |
| `new`/`genai` | 6 s |
| Growth in the number of objects | 5 s |

These are delays for updating the feed, not for starting live. An object event, a review segment and a file recording are different entities.

## 11. Control sequences

### 11.1. Two objects on one camera

```text
camera_activity: entrance.objects=[]; motion=false
entrance/motion: ON
  → the visible activity-driven tile starts live; the still remains
events: new, entrance/person-A, stationary=false
  → add A; activeTracking=true
media: MIME → init → fragments → browser playback-ready
  → show video
events: new, entrance/car-B, stationary=false
events: end, entrance/person-A
  → remove only A; B keeps activeTracking=true
entrance/motion: OFF
  → live remains while B is active
events: end, entrance/car-B
  → activeTracking=false; activeMotion=false
  → 500 ms grace → still; turn playback off
```

### 11.2. Break during an object

```text
/ws disconnect; the object ended during the break
reconnect → onConnect
camera_activity: entrance.objects=[]
  → replace the old list, remove the stale object
```

The historical `end` is not repeated. Media is restored separately, only if the current mode/state requires playback.

### 11.3. Continuous

```text
continuous or a selected camera
motion=false; objects=[]; record=false
  → live is allowed when enabled=true and a stream is present
```

### 11.4. Return from the background

```text
document hidden → media paused according to the background policy
document visible → onConnect resync
snapshot + new topics → current state
media if needed → new negotiation and new readiness
```

Dimming the screen by itself is not an event of the Frigate API. Recovery is tied to the lifecycle/visibility and the state of the client's connections.

---

## Appendix A. Sources

The main contract above does not require reading the sources. The appendix records the origin of the described behaviour.

Repositories: [Frigate](https://github.com/blakeblackshear/frigate), [go2rtc](https://github.com/AlexxIT/go2rtc). Paths are relative to the respective repository. The exact commit/tag of the checkout studied is not established; the document does not claim a match with the deployed server.

| Part of the contract | Frigate source |
|---|---|
| Dashboard/grid, mode parameters, stream selection, alerts, fallback | `web/src/views/live/LiveDashboardView.tsx`, `web/src/views/live/DraggableGridLayout.tsx` |
| Selected-camera mode | `web/src/views/live/LiveCameraView.tsx` |
| Activity formulas, still intervals, liveReady and transitions | `web/src/components/player/LivePlayer.tsx` |
| Image URL, resize and refresh timing | `web/src/components/camera/CameraImage.tsx`, `web/src/components/camera/AutoUpdatingCameraImage.tsx` |
| Shared WS, envelope, resync, snapshot expansion | `web/src/api/WsProvider.tsx`, `web/src/api/ws.ts` |
| Object identity, stationary, labels, motion/offline | `web/src/hooks/use-camera-activity.ts` |
| Initial and subsequent statistics | `web/src/hooks/use-stats.ts` |
| Transport selection, stream metadata | `web/src/hooks/use-camera-live-mode.ts`, `web/src/hooks/use-deferred-stream-metadata.ts` |
| Wire interaction MSE/WebRTC/JSMpeg | `web/src/components/player/MsePlayer.tsx`, `web/src/components/player/WebRTCPlayer.tsx`, `web/src/components/player/JSMpegPlayer.tsx` |
| Birdseye | `web/src/components/player/BirdseyeLivePlayer.tsx` |
| Data shapes | `web/src/types/ws.ts`, `web/src/types/frigateConfig.ts`, `web/src/types/review.ts` |
| WS routing/auth/permissions/delivery | `frigate/comms/ws.py`, `frigate/api/auth.py`, nginx configuration |
| Runtime snapshot, feature states | `frigate/comms/dispatcher.py` |
| Object/motion publishers | `frigate/track/object_processing.py`, `frigate/camera/state.py`, `frigate/track/tracked_object.py` |
| Still endpoint and query | `frigate/api/media.py`, `frigate/api/defs/query/media_query_parameters.py` |

| Part of the media contract | go2rtc source |
|---|---|
| WS API, text/binary | `internal/api/ws/ws.go`, `internal/api/ws/README.md` |
| MSE handler, codecs | `internal/mp4/ws.go`, `pkg/mp4/helpers.go` |
| Init/fragments, keyframe gate | `pkg/mp4/consumer.go`, `pkg/mp4/muxer.go` |
| Consumer attachment | `internal/streams/streams.go`, `internal/streams/add_consumer.go` |

Local reference checkouts: `frigate` and `go2rtc` in `<upstream-checkout>/`. These are temporary research materials, not a dependency of the app and not part of the API.
