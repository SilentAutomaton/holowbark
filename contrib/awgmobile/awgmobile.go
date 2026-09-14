// Package awgmobile provides a gomobile-compatible wrapper around amneziawg-go.
//
// Architecture:
//   - chanTUN: virtual TUN for the encrypted inner tunnel (plaintext IP in/out)
//   - chanBind: virtual UDP transport for WireGuard outer protocol
//     Instead of real UDP sockets, WG packets are exchanged via Go channels.
//     Kotlin bridges these through the Yggdrasil overlay:
//       RecvWGPacket() → outbound WG packet → wrap in IPv6 UDP → ygg.send()
//       ygg.recv() → unwrap IPv6 UDP → SendWGPacket() → AWG decrypts
package awgmobile

import (
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// Backend is the gomobile entry type.
type Backend struct {
	mu   sync.Mutex
	dev  *device.Device
	virt *chanTUN
	bind *chanBind
}

// Start creates an AmneziaWG device with a virtual (channel-backed) TUN and
// a channel-backed UDP Bind (no real sockets — WG packets are bridged through
// Yggdrasil by the Kotlin layer via RecvWGPacket / SendWGPacket).
// settings is a UAPI config string. mtu is the interface MTU (use 1280).
func (b *Backend) Start(settings string, mtu int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.dev != nil {
		return errors.New("already started")
	}

	virt := newChanTUN(mtu)
	bind := newChanBind()
	logger := device.NewLogger(device.LogLevelError, "awg: ")
	dev := device.NewDevice(virt, bind, logger)

	if err := dev.IpcSet(settings); err != nil {
		dev.Close()
		virt.Close()
		bind.close()
		return err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		virt.Close()
		bind.close()
		return err
	}

	b.dev = dev
	b.virt = virt
	b.bind = bind
	return nil
}

// Stop shuts down the AWG device.
func (b *Backend) Stop() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.dev == nil {
		return
	}
	b.dev.Close()
	b.virt.Close()
	b.bind.close()
	b.dev = nil
	b.virt = nil
	b.bind = nil
}

// SendPacket delivers a plaintext IP packet into the AWG stack for encryption.
// The encrypted result is sent via WireGuard to the server.
func (b *Backend) SendPacket(p []byte) error {
	b.mu.Lock()
	v := b.virt
	b.mu.Unlock()
	if v == nil {
		return errors.New("not started")
	}
	cp := make([]byte, len(p))
	copy(cp, p)
	select {
	case v.inbound <- cp:
	default: // drop if full
	}
	return nil
}

// RecvPacket returns the next decrypted IP packet from the AWG stack.
// Blocks until a packet is available or the device is closed (returns nil).
func (b *Backend) RecvPacket() []byte {
	b.mu.Lock()
	v := b.virt
	b.mu.Unlock()
	if v == nil {
		return nil
	}
	p, ok := <-v.outbound
	if !ok {
		return nil
	}
	return p
}

// RecvWGPacket returns the next outbound WireGuard protocol packet (encrypted)
// that AWG wants to send to the server.
// Kotlin should wrap this in an IPv6 UDP packet and send via Yggdrasil.
// Blocks until a packet is available or the device is stopped (returns nil).
func (b *Backend) RecvWGPacket() []byte {
	b.mu.Lock()
	bind := b.bind
	b.mu.Unlock()
	if bind == nil {
		return nil
	}
	log.Printf("awg: RecvWGPacket waiting, channel has %d packet(s)", len(bind.toSend))
	p, ok := <-bind.toSend
	if !ok {
		return nil
	}
	log.Printf("awg: RecvWGPacket got %d bytes, channel now has %d packet(s)", len(p), len(bind.toSend))
	return p
}

// SendWGPacket injects a WireGuard protocol packet (encrypted) received from
// the server via Yggdrasil into the AWG device for decryption.
// Kotlin should call this with the UDP payload from the server's Yggdrasil packets.
func (b *Backend) SendWGPacket(p []byte) {
	b.mu.Lock()
	bind := b.bind
	b.mu.Unlock()
	if bind == nil {
		return
	}
	cp := make([]byte, len(p))
	copy(cp, p)
	select {
	case bind.toRecv <- cp:
	default: // drop if full
	}
}

// ─── channel-backed virtual TUN ──────────────────────────────────────────────

type chanTUN struct {
	inbound  chan []byte
	outbound chan []byte
	events   chan tun.Event
	mtu      int
	done     chan struct{}
	once     sync.Once
}

func newChanTUN(mtu int) *chanTUN {
	c := &chanTUN{
		inbound:  make(chan []byte, 64),
		outbound: make(chan []byte, 64),
		events:   make(chan tun.Event, 4),
		mtu:      mtu,
		done:     make(chan struct{}),
	}
	c.events <- tun.EventUp
	return c
}

func (c *chanTUN) Close() error {
	c.once.Do(func() {
		close(c.done)
		close(c.outbound)
	})
	return nil
}

func (c *chanTUN) File() *os.File               { return nil }
func (c *chanTUN) MTU() (int, error)             { return c.mtu, nil }
func (c *chanTUN) Name() (string, error)         { return "awg0", nil }
func (c *chanTUN) Events() <-chan tun.Event      { return c.events }
func (c *chanTUN) BatchSize() int                { return 1 }

func (c *chanTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case <-c.done:
		return 0, os.ErrClosed
	case p, ok := <-c.inbound:
		if !ok {
			return 0, os.ErrClosed
		}
		n := copy(bufs[0][offset:], p)
		sizes[0] = n
		return 1, nil
	}
}

func (c *chanTUN) Write(bufs [][]byte, offset int) (int, error) {
	for _, buf := range bufs {
		if offset >= len(buf) {
			continue
		}
		pkt := make([]byte, len(buf)-offset)
		copy(pkt, buf[offset:])
		select {
		case <-c.done:
			return 0, os.ErrClosed
		case c.outbound <- pkt:
		}
	}
	return len(bufs), nil
}

// ─── channel-backed Bind (WireGuard outer UDP transport) ─────────────────────

// chanBind routes WireGuard protocol packets through Go channels instead of
// real UDP sockets. The Kotlin layer bridges these through Yggdrasil.
//
// Lifecycle: AWG calls Close() + Open() on every BindUpdate (e.g. during dev.Up()).
// Each Open() creates a fresh cycleDone channel so the new recv goroutines can be
// stopped by the next Close(). Send() never checks cycleDone — it just queues
// outbound packets, dropping if the channel is full (cap 64, >>11 per cycle).
type chanBind struct {
	toSend    chan []byte   // outbound: AWG → Kotlin → Yggdrasil → server
	toRecv    chan []byte   // inbound:  server → Yggdrasil → Kotlin → AWG
	mu        sync.Mutex
	cycleDone chan struct{} // closed by Close() to stop current recv goroutines
	stopping  bool         // true after final close() from Backend.Stop
}

func newChanBind() *chanBind {
	return &chanBind{
		toSend:    make(chan []byte, 64),
		toRecv:    make(chan []byte, 64),
		cycleDone: make(chan struct{}),
	}
}

// close permanently shuts down the bind (called from Backend.Stop only).
// Closing toSend unblocks any RecvWGPacket call.
func (b *chanBind) close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.stopping {
		b.stopping = true
		select {
		case <-b.cycleDone:
		default:
			close(b.cycleDone)
		}
		close(b.toSend)
	}
}

// conn.Bind interface

// Open is called by AWG's BindUpdate to (re)start receive goroutines.
// Each call creates a fresh cycleDone so the goroutines from this cycle
// can be stopped independently when Close() is called next.
func (b *chanBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	b.cycleDone = make(chan struct{})
	cycleDone := b.cycleDone
	b.mu.Unlock()

	recv := func(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
		select {
		case <-cycleDone:
			return 0, net.ErrClosed
		case p, ok := <-b.toRecv:
			if !ok {
				return 0, net.ErrClosed
			}
			n := copy(packets[0], p)
			sizes[0] = n
			eps[0] = &chanEndpoint{}
			return 1, nil
		}
	}
	return []conn.ReceiveFunc{recv}, port, nil
}

// Close is called by AWG's BindUpdate to stop the current recv goroutines.
// It does NOT permanently disable Send — a subsequent Open() restores recv.
func (b *chanBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.stopping {
		select {
		case <-b.cycleDone:
		default:
			close(b.cycleDone)
		}
	}
	return nil
}

func (b *chanBind) SetMark(mark uint32) error { return nil }

// Send queues outbound WireGuard packets (junk + WG handshake/data) for the
// Kotlin bridge to read via RecvWGPacket. Drops silently if the channel is full.
// Does NOT check cycleDone — BindUpdate's Close/Open cycle must not interrupt sends.
func (b *chanBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	nonEmpty := 0
	for _, buf := range bufs {
		if len(buf) > 0 {
			nonEmpty++
		}
	}
	log.Printf("awg: chanBind.Send called with %d packet(s) (%d non-empty), channel has %d/%d slots used",
		len(bufs), nonEmpty, len(b.toSend), cap(b.toSend))
	queued := 0
	for _, buf := range bufs {
		if len(buf) == 0 {
			continue
		}
		cp := make([]byte, len(buf))
		copy(cp, buf)
		select {
		case b.toSend <- cp:
			queued++
		default: // drop if full (cap 64, typically only ~11 packets per cycle)
		}
	}
	log.Printf("awg: chanBind.Send queued %d/%d packets, channel now has %d", queued, nonEmpty, len(b.toSend))
	return nil
}

func (b *chanBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	addrPort, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return &chanEndpoint{addrPort: addrPort}, nil
}

func (b *chanBind) BatchSize() int { return 1 }

// chanEndpoint implements conn.Endpoint for the channel-based bind.
type chanEndpoint struct {
	addrPort netip.AddrPort
}

func (e *chanEndpoint) ClearSrc()           {}
func (e *chanEndpoint) SrcToString() string { return "" }
func (e *chanEndpoint) DstToString() string { return e.addrPort.String() }
func (e *chanEndpoint) DstIP() netip.Addr   { return e.addrPort.Addr() }
func (e *chanEndpoint) SrcIP() netip.Addr   { return netip.Addr{} }
func (e *chanEndpoint) DstToBytes() []byte {
	b := e.addrPort.Addr().As16()
	p := e.addrPort.Port()
	return append(b[:], byte(p>>8), byte(p))
}

// ─── UAPI helpers ────────────────────────────────────────────────────────────

// BuildUAPISettings assembles a UAPI config string from individual parameters,
// matching the full official amneziawg-go UAPI spec.
//
// Keys are hex-encoded (lowercase, 64 chars). allowedIPs is comma-separated CIDRs.
// Pass 0/empty for any parameter to omit it (safe for plain WireGuard mode).
//
// h1–h4: magic header specs as strings ("N" or "N-M"); empty string = omit.
// s3/s4: cookie/transport padding (0 = omit).
// i1–i5: obfuscation chain specs (e.g. "<r 8><d>"); empty string = omit.
func BuildUAPISettings(
	privateKeyHex, publicKeyHex, presharedKeyHex string,
	endpoint, allowedIPs string,
	persistentKeepalive int,
	jc, jmin, jmax int,
	s1, s2, s3, s4 int,
	h1, h2, h3, h4 string,
	i1, i2, i3, i4, i5 string,
) string {
	var sb strings.Builder
	fmt.Fprintf(&sb, "private_key=%s\n", privateKeyHex)
	// Junk params (jc/jmin/jmax are interdependent; jc must be > 0 per UAPI validation)
	if jc > 0 {
		fmt.Fprintf(&sb, "jc=%d\n", jc)
		if jmin > 0 {
			fmt.Fprintf(&sb, "jmin=%d\n", jmin)
		}
		if jmax > 0 {
			fmt.Fprintf(&sb, "jmax=%d\n", jmax)
		}
	}
	// Padding params (independent of jc)
	if s1 > 0 {
		fmt.Fprintf(&sb, "s1=%d\n", s1)
	}
	if s2 > 0 {
		fmt.Fprintf(&sb, "s2=%d\n", s2)
	}
	if s3 > 0 {
		fmt.Fprintf(&sb, "s3=%d\n", s3)
	}
	if s4 > 0 {
		fmt.Fprintf(&sb, "s4=%d\n", s4)
	}
	// Magic header params (independent of jc; stored as raw spec strings)
	if h1 != "" {
		fmt.Fprintf(&sb, "h1=%s\n", h1)
	}
	if h2 != "" {
		fmt.Fprintf(&sb, "h2=%s\n", h2)
	}
	if h3 != "" {
		fmt.Fprintf(&sb, "h3=%s\n", h3)
	}
	if h4 != "" {
		fmt.Fprintf(&sb, "h4=%s\n", h4)
	}
	// Obfuscation chain params (i1–i5)
	if i1 != "" {
		fmt.Fprintf(&sb, "i1=%s\n", i1)
	}
	if i2 != "" {
		fmt.Fprintf(&sb, "i2=%s\n", i2)
	}
	if i3 != "" {
		fmt.Fprintf(&sb, "i3=%s\n", i3)
	}
	if i4 != "" {
		fmt.Fprintf(&sb, "i4=%s\n", i4)
	}
	if i5 != "" {
		fmt.Fprintf(&sb, "i5=%s\n", i5)
	}
	// Do NOT put a blank line here: IpcSetOperation treats blank line as end-of-config
	// and returns before processing the peer section. public_key= signals peer start.
	fmt.Fprintf(&sb, "public_key=%s\n", publicKeyHex)
	if presharedKeyHex != "" {
		fmt.Fprintf(&sb, "preshared_key=%s\n", presharedKeyHex)
	}
	fmt.Fprintf(&sb, "endpoint=%s\n", endpoint)
	for _, cidr := range strings.Split(allowedIPs, ",") {
		cidr = strings.TrimSpace(cidr)
		if cidr != "" {
			fmt.Fprintf(&sb, "allowed_ip=%s\n", cidr)
		}
	}
	if persistentKeepalive > 0 {
		fmt.Fprintf(&sb, "persistent_keepalive_interval=%d\n", persistentKeepalive)
	}
	return sb.String()
}
