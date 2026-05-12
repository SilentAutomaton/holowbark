# Holowbark

[Русский](README.md)

Android VPN app combining the [Yggdrasil](https://yggdrasil-network.github.io/) overlay network and [WireGuard](https://www.wireguard.com/) in a single userspace `VpnService`. No root required.

> **Always-On VPN**: the app does not work correctly in this mode. Make sure it is disabled: Settings → Network → VPN → Holowbark → ⚙.

Pre-built APKs are available in [Releases](https://github.com/SilentAutomaton/holowbark/releases).

## Quick start

1. Install the APK from the Releases page.
2. On the **WG** tab, import your WireGuard `.conf` file (see the Server setup section below).
3. On the **Peers** tab, select the country closest to your server and add at least 10 peers — the more you add, the more resilient the overlay.
4. Go back to the home screen and tap **Connect**.

Holowbark first establishes the Yggdrasil overlay through the selected peers, then routes all traffic through the WireGuard tunnel on top of it.

## Build

### Requirements

| Tool | Version |
|---|---|
| Go | 1.21+ |
| gomobile | latest (`go install golang.org/x/mobile/cmd/gomobile@latest`) |
| Android SDK | platform-35, build-tools-35 |
| Android NDK | r27 (`ndk;27.2.12479018`) |
| Java | 17+ |

### Build and install

```bash
make setup   # first-time setup: SDK components, gomobile, Go repo clones
make all     # build holowbark.aar + debug APK
make install # install to connected device
```

### Other targets

```bash
make aar            # build holowbark.aar (Yggdrasil + WireGuard/AmneziaWG via gomobile)
make apk            # debug APK (requires aar)
make apk-release    # unsigned release APK
make install        # adb install debug APK
make rebuild        # clean-aar + all (full rebuild from scratch)
```

Or directly via Gradle once `app/libs/holowbark.aar` exists:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Go library (`holowbark.aar`)

The AAR bundles Yggdrasil and AmneziaWG (WireGuard works; AmneziaWG obfuscation parameters are supported but untested) and is **not stored** in the repository — build it once with `make aar`. The gomobile entry point is `contrib/awgmobile/awgmobile.go`; `make clone-deps` copies it into the yggdrasil-go tree and adds the required Go dependencies.

## Features

- Import `.conf` file (WireGuard; AmneziaWG obfuscation parameters supported but untested)
- Browse public Yggdrasil peers by country, select and connect
- Access to Yggdrasil network resources including public DNS

---

## Server setup — manual

WireGuard exit node reachable **only through the Yggdrasil overlay**. The WireGuard UDP port is closed from the public internet — the server's Yggdrasil address is the sole endpoint.

### 1. Yggdrasil

[yggdrasil-network/yggdrasil-go](https://github.com/yggdrasil-network/yggdrasil-go) — packages at [yggdrasil-network.github.io/installation](https://yggdrasil-network.github.io/installation.html).

```bash
curl -o /etc/apt/trusted.gpg.d/yggdrasil.gpg \
  https://neilalexander.s3.eu-west-2.amazonaws.com/deb/key.gpg
echo "deb https://neilalexander.s3.eu-west-2.amazonaws.com/deb/ debian yggdrasil" \
  > /etc/apt/sources.list.d/yggdrasil.list
apt update && apt install yggdrasil
yggdrasil -genconf > /etc/yggdrasil/yggdrasil.conf
systemctl enable --now yggdrasil
```

Add public peers to the `Peers` array in `/etc/yggdrasil/yggdrasil.conf` (list: [publicpeers.neilalexander.dev](https://publicpeers.neilalexander.dev/)). Get the server's overlay address:

```bash
yggdrasilctl getSelf | grep '"address"'
# "address": "200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx"
```

### 2. WireGuard

```bash
apt install wireguard-tools
echo "net.ipv4.ip_forward=1"          >> /etc/sysctl.d/99-forward.conf
echo "net.ipv6.conf.all.forwarding=1" >> /etc/sysctl.d/99-forward.conf
sysctl -p /etc/sysctl.d/99-forward.conf
```

Generate keys:

```bash
SERVER_PRIV=$(wg genkey); SERVER_PUB=$(echo "$SERVER_PRIV" | wg pubkey)
CLIENT_PRIV=$(wg genkey); CLIENT_PUB=$(echo "$CLIENT_PRIV" | wg pubkey)
```

Find the outbound interface: `ip route | awk '/^default/{print $5}'`

Create `/etc/wireguard/wg0.conf`:

```ini
[Interface]
Address    = 10.100.0.1/24
ListenPort = 51820
PrivateKey = <SERVER_PRIV>

# NAT; replace eth0 with your outbound interface
PostUp  = iptables -t nat -A POSTROUTING -s 10.100.0.0/24 -o eth0 -j MASQUERADE
PreDown = iptables -t nat -D POSTROUTING -s 10.100.0.0/24 -o eth0 -j MASQUERADE

# Block WireGuard port: IPv4 fully, IPv6 except from Yggdrasil
PostUp  = iptables  -I INPUT -p udp --dport 51820 -j DROP; \
          ip6tables -I INPUT -p udp --dport 51820 ! -s 200::/7 -j DROP
PreDown = iptables  -D INPUT -p udp --dport 51820 -j DROP; \
          ip6tables -D INPUT -p udp --dport 51820 ! -s 200::/7 -j DROP

[Peer]
PublicKey  = <CLIENT_PUB>
AllowedIPs = 10.100.0.2/32
```

```bash
systemctl enable --now wg-quick@wg0
```

Verify firewall from an external machine:

```bash
# should be silently dropped
nc -u -z -w2 <server-public-ipv4> 51820; echo $?

# should be reachable from inside Yggdrasil
nc -u -z -w2 200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx 51820; echo $?
```

### 3. Client `.conf` for Holowbark

```ini
[Interface]
PrivateKey = <CLIENT_PRIV>
Address    = 10.100.0.2/24
DNS        = 1.1.1.1

[Peer]
PublicKey           = <SERVER_PUB>
Endpoint            = [200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx]:51820
AllowedIPs          = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
```

Set `Endpoint` to the server's Yggdrasil address from step 1. Import via the WG tab in the app.

---

## Server setup — wg-easy (Docker)

[wg-easy](https://github.com/wg-easy/wg-easy) — WireGuard web UI.

Follow the [official installation guide](https://wg-easy.github.io/wg-easy/latest/getting-started/). The only change for Holowbark: set `WG_HOST` to the **server's Yggdrasil address** (not the public IP):

```
WG_HOST=[200:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx]
```

All generated client configs will automatically use the correct `Endpoint`. Download the `.conf` and import it into Holowbark via the WG tab.

After initial setup, lock down the ports from the public internet:

```bash
# WireGuard: block IPv4 fully, IPv6 except from Yggdrasil
iptables  -I INPUT -p udp --dport 51820 -j DROP
ip6tables -I INPUT -p udp --dport 51820 ! -s 200::/7 -j DROP

# Web UI: same
iptables  -I INPUT -p tcp --dport 51821 -j DROP
ip6tables -I INPUT -p tcp --dport 51821 ! -s 200::/7 -j DROP

apt install iptables-persistent -y && netfilter-persistent save
```

After closing port 51821, the web UI is accessible from within Yggdrasil at `http://[200:xxxx:...]:51821`, or via SSH tunnel:

```bash
ssh -L 51821:localhost:51821 user@<server>
# then open http://localhost:51821
```

---

## Packet routing

| Destination | Path |
|---|---|
| `200::/7` (Yggdrasil overlay) | YggdrasilManager → overlay |
| Everything else | AwgManager → WireGuard tunnel |

Yggdrasil peer IPs (resolved at VPN start) are excluded from tunnel routes and reach the physical network directly. WireGuard uses `chanBind` — no real UDP socket is opened; outbound packets are wrapped in IPv6/UDP frames and forwarded through Yggdrasil, inbound frames are unwrapped and injected back into the stack.
