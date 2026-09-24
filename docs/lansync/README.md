# LAN Sync (fork feature) — protocol v2

> **⚠ Fork-specific.** anki-plus addition; upstream AnkiDroid has no local-network sync. The
> normal sync path (AnkiWeb or a custom sync server via `AnkiServer`) is untouched — this is a
> separate, self-contained feature in `com.ichi2.anki.lansync` that talks HTTP directly between
> devices on the same network, with no server in between (except the optional desktop *hub*).
>
> **v2** implements `docs/lan-sync/SPEC-v2.md` (authoritative; ADR 0001): an encrypted, paired
> *control plane* plus two alternative *data planes* — `apkg` (whole-collection merge, as in v1)
> and `hub` (real rslib sync against a desktop `SimpleServer`). The desktop half of the interface
> is implemented separately; both sides must keep matching the spec exactly.

## What it is

Settings → **局域网同步 / LAN sync** (`LanSyncFragment`). Turn the switch on and the device:

1. serves the v2 HTTP API on the Wi-Fi interface,
2. announces itself (`ANKI-LAN/2` + `ANKIPLUS-LAN/1` UDP, `_ankisync._tcp` + `_ankiplus-sync._tcp`
   NSD) so other v2/v1 devices find it,
3. pairs with a peer — a 6-digit code typed over, then a 4-hex security code shown on both screens
   *after* commit so the two can be eyeballed against each other (SPEC-v2 §4.1: it is a hash of the
   shared key, so before commit there is nothing honest to display),
4. syncs with paired peers in whichever data plane both sides support, on a schedule and on
   events (SPEC-v2 §8), not only when the user taps Sync.

## Data planes and their semantics — read this before expecting "sync"

`LanEngine.selectMode()` picks exactly one plane per round. Mode negotiation is the intersection
of both sides' advertised `modes`, preferring `hub` over `apkg`; `hub` is only chosen when the
*peer* advertises the `hub` **role** (Android is hub client only, never hub server).

### `apkg` — authenticated whole-collection push + pull (Android ↔ Android default)

Same data model as v1: export the collection as `.apkg` (`withScheduling`, `withDeckConfigs`,
`withMedia`), transfer it, merge it with `mergeNotetypes = true` and
`updateNotes = updateNotetypes = IF_NEWER`. A round is push then pull.

**The package bytes are not encrypted** (SPEC-v2 §4.3, deliberately): the *authorization* is
enveloped and AEAD-authenticated, the up-to-1-GiB payload rides as raw `.apkg`, so confidentiality
rests on "same LAN segment" — the same posture as hub mode's plaintext rslib sync. The screen says
so in a standing warning. Framed AEAD is the upgrade path, not a v2 feature.

| Situation | apkg result | hub result (rslib) |
|---|---|---|
| Note added on A only | appears on B ✔ | appears on B ✔ |
| Note edited on A only | B takes A's version ✔ | merges by revision history ✔ |
| Same note edited on both | `IF_NEWER` picks one; other edit lost | merged properly, AnkiWeb semantics ✔ |
| Note deleted on A | **comes back from B. Deletions never propagate ✘** | **propagates via graves ✔** |
| Review log on both | schedules merge; card reviewed on both resolves by import order | revlog merges ✔ |
| Same deck name, different deck | two decks (decks are not GUID-matched) | handled by sync protocol |
| Media | filenames merged; same names overwrite | `SyncMediaWorker` |

Because apkg imports are cumulative merges, repeated rounds converge upward: to actually remove
something, delete it on *every* device — or sync via a hub, where graves make deletes real.

### `hub` — official rslib sync through a desktop sync server

The desktop (the `hub`-role device, per SPEC-v2 §6.2) hands out `{endpoint, username, password,
profile}` through an enveloped `POST /hub/grant`. `LanHubClient` writes that into the **existing**
custom-sync-server preferences (`syncBaseUrl` + `syncBaseUrl_switch`, and — like the settings
screen itself — clears `currentSyncUri`, which `Sync.getEndpoint()` would otherwise prefer), then
lets the official path sync: `syncLogin` → `syncCollection` → on `NO_CHANGES` start
`SyncMediaWorker`; on `FULL_UPLOAD`/`FULL_DOWNLOAD` do backup → `close(forFullSync)` →
`fullUploadOrDownload` → `reopen(afterFullSync)` on the *same* manager-owned collection. Nothing in
`lansync` speaks the sync protocol itself.

**Profile routing (v1 defect, now guarded):** grants and `.apkg` imports carry the profile name;
a payload announced for a profile other than the open one is refused (403 / round error) instead
of silently landing wherever. `lan_sync` storage remains app-global on purpose (below).

## Pairing and envelope crypto (SPEC-v2 §4)

- **Pairing**: `POST /pair/begin` (loopback only) mints a 6-digit decimal `pair_code`,
  **5-minute TTL, one-shot**; `security_code` is `null` in that answer — it is `sha256(shared)`
  truncated, and the shared key does not exist until *both* contributions are in, so any code shown
  before commit would be a number the user could not trust. The initiator sends
  `POST /pair/commit {pair_code, peer_info, envelope}`; the envelope wraps its fresh 32-byte
  contribution under `pair_wrap_key = HKDF(ikm=code, salt="anki-lan-sync/2/pair", info="wrap")`
  **and then the §4.2 route key on top of that, with route `pair/wrap` and `kid = "pair"`** —
  skipping that second derivation is self-consistent on one device and unreadable on the other,
  which is why `LanInteropVectorsTest` replays the desktop's own wrapped envelope.
  Both sides then compute one and the same key: `combine_secrets` orders the two halves by
  `device_id` and runs `HKDF(salt="anki-lan-sync/2/kx", info="pair")`. **One key, one kid per
  pairing** — there is no kid-in/kid-out, and the commit answer deliberately carries no key id, so
  `LanCrypto.kidOf(shared)` is the only way either side knows its own kid.
  The code is consumed *before* the unwrap attempt (§4.1, r3): a matching code may not be re-used
  to try a second ciphertext, since the wrap key has ~20 bits of entropy behind it.
  Errors: 401 `pair_invalid` (wrong code and unknown code are the same answer), 409
  `pair_consumed`, 410 `pair_expired`, 429 `pair_throttled`.
- **Envelopes**: every protected request carries `X-Anki-Sync: ANKI-LAN/2` and `X-Anki-Kid`, plus
  `X-Anki-Peer`/`X-Anki-Peer-Id` (URL-encoded display name and device id) for the peer's activity
  log. **The envelope is the request body itself** on the
  JSON routes (there is no `X-Anki-Ts` header; `ts` lives inside the envelope);
  `apkg/import` is the one route where the package owns the body, so its envelope rides in
  `X-Anki-Envelope` as **urlsafe**-padded base64 of the same JSON, whose own `nonce`/`ct` are
  *standard* base64 with padding. Key = `HKDF-SHA256(ikm=shared_secret, salt="anki-lan-sync/2",
  info=<route>)` — route names carry no leading slash (`devices/sync`), one key per route, so a
  ciphertext moved to another route cannot even be attempted. AAD = ASCII `"<kid>|<ts>"`, whole
  seconds, the `|` is load-bearing. Responses are envelopes too, keyed by the *peer's* kid.
  Rejection: ±300 s (`stale_ts`), nonce de-dup over 600 s per `(kid, nonce)` (`replay`), plus
  `kid_mismatch` / `decrypt_failed` / `bad_envelope` / `bad_payload`.
- **Bulk streams** (`.apkg` legs) are authenticated, **not** encrypted (§4.3): `POST /apkg/export`
  answers raw `.apkg` bytes and echoes the *request envelope's nonce* in `X-Anki-Xfer`, which binds
  those bytes to one verified request; `POST /apkg/import` needs `Content-Length` (411 without,
  507 over 1 GiB). Encryption of the stream was implemented here once and dropped: it made the two
  platforms' byte formats differ and bought only "someone sniffing the segment cannot read cards",
  which the standing UI warning states as an assumption instead of a guarantee.
- **Default-deny**: an unpaired caller never reaches a data-plane route (403 `not_paired`).
  The legacy plaintext `/export` + `/import` pair is refused until the user explicitly flips
  **允许明文 v1** on the screen; every round through it is tagged `SECURITY: plaintext-v1` in the
  activity log. Hand-written HKDF on `javax.crypto` only — no new dependencies (SPEC-v2 §2).
- **HTTP/1.1 keep-alive** (§5.1): a response produced before the caller's body was read must hang
  up, or the leftover bytes become the next request's status line and the client sees a 400 out of
  nowhere. `serve()` tracks that with a per-request `bodyRead` flag instead of sprinkling
  `closeConnection()` over the refusal sites — and the old "read the body and throw it away"
  fallback is gone, because on an import leg that means reading a gigabyte to reject it.

## Discovery

- **NSD / mDNS** (primary): v2 **registers both** service types — `_ankisync._tcp` as
  `<name>-<id8>` with TXT `v/id/kind/roles`, and the legacy `_ankiplus-sync._tcp` (`AnkiPlus-*`)
  — and browses both, so un-upgraded fork phones keep showing up and an old phone can still see a
  new one. Resolved peers are verified via `GET /info` before they enter the list.
- **UDP announce**: each cycle sends **two packets**, `ANKI-LAN/2 <json>` and the
  `ANKIPLUS-LAN/1` projection of the same info, to global + subnet-directed broadcast on port
  46000 every 5 s. A receiver requires magic and `protocol` to agree (a v2 packet claiming to be v1
  is dropped), and `encodeAnnounce` always stamps its own version, so lying about `protocol` is not
  possible from this build. The peer address always comes from the receiving socket, never the
  packet. Multicast lock held.
- **`GET /info` is version-negotiated by header**: a caller sending `X-Anki-Sync: ANKI-LAN/2` gets
  the v2 DTO (`device_id`/`kind`/`ts`/`modes`/`roles`/`kids`), a caller sending nothing — which is
  all an un-upgraded fork can do — gets the v1 DTO (`id`/`platform`/`appVersion`). One merged
  payload cannot serve both, because the two disagree on `protocol`, the one field that must not
  lie.

Protocol-1 peers appear labelled "v1, unpaired"; they can only sync if the plaintext opt-in is on.

## HTTP API (v2 routes)

Bound to `0.0.0.0`, port 5600 with fallbacks up to 5610, advertised via NSD/`/info`.

| Route | Purpose | Auth |
|---|---|---|
| `GET /` | liveness (names both magics) | none |
| `GET /info` | identity + `modes`/`roles`/`kids`/`profile` — no key material; v1 DTO without the magic header | none |
| `POST /pair/begin` | open a pairing session (code only; `security_code` is null) | **loopback only** |
| `POST /pair/commit` | consume code, swap wrapped contributions | pair code |
| `POST /devices/sync` | trust-list exchange, public fields only | envelope (body) |
| `POST /round/notify` | "I changed things" → peer pulls | envelope (body) |
| `POST /apkg/export` | raw `.apkg` bytes + `X-Anki-Xfer` nonce echo (§4.3) | envelope (body) |
| `POST /apkg/import` | merge, profile guard, ≤1 GiB else 507, no `Content-Length` → 411 | envelope (`X-Anki-Envelope` header) |
| `POST /hub/grant` | hub credentials — Android serves hub *client* only, so always 403 `hub_off` here | envelope (body) |
| `GET /state` | busy flag, progress, last 50 log + round rows | **loopback only**, plaintext |
| `GET /export`, `POST /import` | v1 plaintext downgrade | magic header + explicit opt-in |

Everything that carries an envelope is `POST`, even `apkg/export` and `hub/grant`: the route name
*is* the envelope's HKDF `info`, so method would be a second axis for the two platforms to
disagree on. The only GETs are the two unauthenticated reads (`/`, `/info`) and `/state`, which is
a loopback plaintext read. Refusals are always JSON in the SPEC-v2 §5 vocabulary —
`stale_ts` `replay` `kid_mismatch` `decrypt_failed` `bad_envelope` `bad_payload` `xfer_mismatch`
`pair_invalid` (401), `need_magic` `not_paired` `hub_off` `v1_disabled` `loopback_only`
`profile_mismatch` (403), `not_found` (404), `BUSY` `pair_consumed` `hub_seed_required` (409),
`pair_expired` (410), `length_required` (411), `pair_throttled` (429), `too_large` (507),
`internal_error` (500). `profile_mismatch` is the one code only a phone emits.

`LanServer` carries two NanoHTTPD-specific notes that are protocol-visible in practice: its header
map is keyed by lowercased name *with the hyphen kept*, and its status line is built from
`IStatus.getDescription()`, so a custom status must carry its own number (`"403 not paired"`) or
clients fail before they ever see the body. Both fail closed, i.e. as "everything is unpaired".

## Scheduling and self-heal (SPEC-v2 §8)

Periodic rounds at 10 s / 5 min / 30 min (or off). Plus "don't wait" triggers: enabling the
feature, a peer flipping offline→online, and local writes (5 s debounce, ≥15 s spacing).
Self-heal: offline peers are re-probed every 30 s; a failed probe opens a 6 s rediscovery window
(DHCP change), rate-limited to one per 30 s. All of this lives in `LanScheduler`.

**Keep-online** is an explicit opt-in: the default is unchanged — ports close when the screen
leaves the foreground. Enabling it starts `LanKeepAliveService` (a `dataSync` foreground service)
with a standing notification, and the screen warns that aggressive OEM battery managers may kill
the listener anyway.

## Lifecycle

`show()` on resume / `hide()` on pause; with keep-online on, `hide()` is a no-op and the service
owns the runtime. `syncAll()` walks every online *usable* (paired, or v1 with the opt-in) peer.

## Where the state lives

Two SharedPreferences files, split by sensitivity (SPEC-v2 §7):

- `lan_sync` — identity, name, switches, schedule, peer list, log (200), round details (200),
  `pair_codes` sessions. Everything `/devices/sync` may talk about.
- `lan_sync_secrets` — every `device_secret`, in and out, keyed by `kid`. **Never** serialized
  into any HTTP payload; pairing is the only way key material moves.

Both are deliberately app-global (unwrapped `baseContext`): `ProfileContextWrapper` would make
every profile announce itself as a separate device. On disk:
`/data/data/<pkg>/shared_prefs/lan_sync.xml` and `lan_sync_secrets.xml`.

A one-shot migration marks v1 trust-table entries unpaired (they were auto-trusted); they stay
syncable only through the explicit plaintext opt-in until paired again.

## Code map

`AnkiDroid/src/main/java/com/ichi2/anki/lansync/`

| File | Responsibility |
|---|---|
| `LanProtocol.kt` | v2 wire constants + v1 compat, `LanPeerInfo`/`LanDevice`/`LanRoundDetail`, announce encode/decode (dual magic), mode negotiation, IPv4 helpers. Android-free on purpose. |
| `LanCrypto.kt` | HKDF-SHA256 (RFC 5869), per-route AES-256-GCM envelopes, pairing wrap + `pair/wrap` route, derived kid/security code, replay window |
| `LanPairing.kt` | pairing-code state machine (TTL 5 min, one-shot, no existence oracle) |
| `LanAddresses.kt` | interface enumeration / "which address is reachable by peers" |
| `LanStore.kt` | app-global persistence incl. `lan_sync_secrets`, round rows, v1 migration |
| `LanEngine.kt` | rounds: mode selection, apkg push/pull legs, plaintext v1 legs, pairing client, enveloped requests |
| `LanHubClient.kt` | hub grant → custom-sync prefs → official rslib sync (no second Collection handle) |
| `LanServer.kt` | NanoHTTPD v2 routes + gated v1 downgrade + the §5.1 "answered before reading the body → hang up" guard |
| `LanDiscovery.kt` | NSD v2+v1, UDP announce/listen v2 (decodes v1), multicast lock |
| `LanScheduler.kt` | period loops, run-soon triggers, write debounce, self-heal |
| `LanKeepAliveService.kt` | optional foreground "keep online" with standing notification |
| `LanSyncManager.kt` | owns server/discovery/scheduler lifetime, probe/verify, pairing API, notify-pull |
| `LanSyncViewModel.kt` / `LanSyncScreen.kt` / `LanSyncFragment.kt` | Compose UI: pairing dialogs, mode badges, schedule, round details, plaintext warning |

Strings: `res/values/21-lansync.xml`, all `translatable="false"`. Entry point unchanged
(`pref_lansync_screen_key` + `HeaderPreference`).

### Why the collection never blocks a socket

Unchanged from v1: `LanEngine.exclusive()` holds the collection only for local export/import work,
never across HTTP; contention answers 409 `BUSY`.

## Testing

`./gradlew :AnkiDroid:testPlayDebugUnitTest --tests "com.ichi2.anki.lansync.*"` — JVM/Robolectric,
no device, no emulator.

- `LanProtocolTest` — v2 announce round-trip incl. modes/roles/kids, v1 packets still decode,
  magic/protocol must agree, the v1 projection is a *separate* packet (`encodeV1Announce`), unknown
  fields tolerated, mode negotiation (hub>apkg, role gate, v1→null), subnet/IPv4 math.
- `LanCryptoTest` — HKDF against the RFC 5869 A.1/A.3 vectors (not self-generated expectations),
  envelope round-trip, tamper / wrong-AAD (kid, ts, route, secret) / ts out-of-window /
  replayed-nonce rejection, pair wrap + wrong code.
- **`LanInteropVectorsTest`** — replays `src/test/resources/lansync/vectors.json`, the file
  `anki-desktop/tools/lansync_vectors.py` generates from the *desktop* implementation: combines the
  two contributions, derives kid + security code + pair wrap key, unwraps a byte-exact wrapped
  envelope, derives a route key, and opens a desktop-sealed envelope incl. its urlsafe header
  form. This is the only test on either side that can catch "self-consistent here, unreadable
  there" — 6 tests, all of them interop.
- `LanPairingTest` — 5-min TTL, one-shot consumption, wrong code is uniform, begin replaces, prune.
- `LanStoreTest` — v2 switches default-safe, secrets isolated to their own file and dropped by
  `forget()`, derived kid + security code persist, pairing sessions and round rows persist, v1
  migration is one-shot.
- `LanServerTest` — a real `LanSyncServer` on a real socket, driven by OkHttp: default-deny on data
  routes, `GET /info` v2-vs-v1 DTO, §5 JSON error bodies for 404 and for malformed requests,
  the whole pairing handshake run from both sides in one process (including that the commit answer
  carries *no* kid, so the test must derive it), envelope negatives (cross-route ciphertext,
  tampering, foreign kid, non-JSON plaintext), the plaintext v1 gate closed and open-but-headerless,
  and the §5.1 regression: a refusal that left the body unread must answer `Connection: close`
  **and** the next request on that client must still work.
- `LanHubClientTest` — a grant lands on `syncBaseUrl` / `syncBaseUrl_switch`, clears
  `currentSyncUri`, endpoint visibility follows the switch, custom sync stays off by default.
- `LanAddressesTest` — which interface is the one peers can actually reach.

## Not verified on real hardware

Everything below is compiled + unit-tested but has **not** run on devices (two phones on one
Wi-Fi are needed; also SPEC-v2 §11's checklist):

- NSD/UDP discovery across OEM skins, AP client isolation, and with a VPN active.
- **NSD registering both service types at once** — the calls succeed, but whether a given OEM stack
  publishes and resolves `_ankisync._tcp` *and* `_ankiplus-sync._tcp` without producing two rows
  for one phone is a device question.
- **The `/info` version negotiation against a genuinely old fork build** — simulated by omitting
  the magic header in tests, not by an actual pre-v2 phone.
- **`/pair/begin` and `/state` refusing a LAN caller** — the gate is a remote-address check, and
  every JVM test reaches the server over loopback, so only two devices can show it biting.
- **`Connection: close` under a phone's real HTTP stack** — the OkHttp side is reproduced in JVM
  tests; an OEM proxy or traffic-analytics layer in between is not.
- Actual `.apkg` transfers and merges over the wire, incl. media and convergence over repeated
  rounds.
- A hub round against a live desktop `SimpleServer` (grant → prefs → first `FULL_UPLOAD` →
  media worker), incl. the full-sync backup path.
- Foreground keep-online surviving OEM power managers, and the 30 s re-probe/6 s rediscovery
  behaviour on flaky networks.
- UI correctness (per local policy, no screenshot automation was used).
