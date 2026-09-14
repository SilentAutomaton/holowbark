# Client registration — an idea, not a plan

Deferred. Nothing in the app or the server references this document yet; it exists
so the design does not have to be rediscovered.

## The problem

Today a new device is enrolled by hand: generate a WireGuard key pair on the
server, write a `[Peer]` block into `wg0.conf`, allow-list the device's Yggdrasil
key, then carry a `.conf` file to the phone. Every step is a place to make a typo,
and the Yggdrasil half is easy to forget entirely.

## The shape

The phone generates both identities and asks the server for an address.

```
phone                                   server (reg-api)
  │  POST /register {wg_pub, ygg_pub}     │
  ├──────────────────────────────────────►│
  │                                       │  allocate IP, add WG peer,
  │                                       │  allow-list the ygg key, persist it
  │◄──────────────────────────────────────┤
  │  {wg_ip, ygg_ip, server}              │
```

The phone already owns a Yggdrasil identity (`Prefs.yggPrivateKey`), so only the
WireGuard pair is new: 32 random bytes, clamped the way X25519 requires
(`p[0] &= 248; p[31] &= 127; p[31] |= 64`), public key by scalar multiplication of
the base point. That is a few lines of Kotlin — it needs no addition to the AAR.

## What the server has to get right

These are the failure modes the design has to answer for. They are not
hypothetical: they are what a working implementation of this scheme ran into.

- **Idempotency by key.** The same `wg_pub` must return the address it was already
  given. A phone that retries a timed-out request must not consume a second
  address.
- **Address pool.** Read the allocated addresses from `wg show wg0 allowed-ips`.
  There is no `wg show wg0 public-keys` subcommand; the one that lists keys is
  `wg show wg0 peers`. Never hand out the server's own address.
- **Rollback.** If the Yggdrasil key cannot be allow-listed, remove the WireGuard
  peer that was just added. A half-registered client looks connected and is not.
- **`yggdrasilctl addallowedkey` only writes to the running process.** The key is
  gone after the daemon restarts, and the device it belongs to silently stops
  connecting. The key must also be written into `/etc/yggdrasil/yggdrasil.conf`
  (back the file up first). Adding it to the file does not require restarting a
  live daemon, and restarting one to apply a key is the wrong trade.
- **Address lifetime.** An allocation should expire, or the pool fills with devices
  that were reinstalled and re-registered under a new key.

## What it would change here

- `AllowedPublicKeys` becomes mandatory on the server, and the README has to say
  so — it does not mention the setting at all today. Without it the registration
  endpoint allow-lists into a list nobody enforces.
- The registration endpoint is a service on a public address, which is exactly the
  thing this project exists to avoid. It needs its own answer — reachable only
  through the overlay, or authenticated, or both — before it is worth building.
