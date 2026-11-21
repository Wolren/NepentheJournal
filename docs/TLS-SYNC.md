# Sync transport TLS

Status: JVM and Android hosts serve a pinned TLS connector. iOS still uses plain HTTP.
The HTTP connector stays up for iOS peers and for the first pairing bootstrap.

## What runs today

- `TlsIdentityManager` already generated a self-signed RSA certificate and derived the
  device fingerprint from it. The JVM/Android host now binds a second connector with
  that certificate through Ktor `sslConnector`.
- The TLS connector uses an ephemeral port. `HostInfo.tlsPort` advertises it; `HostInfo`
  also carries the certificate fingerprint, which is the same value used as the device
  fingerprint.
- A client with a trusted peer and a stored `tlsPort` talks only HTTPS. The CIO engine
  installs `PinnedTrustManager`, which compares the SHA-256 of the leaf certificate with
  the expected fingerprint and rejects any other certificate.
- Pairing bootstraps over HTTP: the client reads `HostInfo`, checks the advertised
  fingerprint against a known one when present, adopts `tlsPort`, and pairs over HTTPS.
  The successful pairing stores the port in `trusted-devices.json` (`TrustedPeer.tlsPort`).
- A host restart reuses the same identity. The server passes a copy of the identity
  password to Netty because Netty zeroes the array it receives.

## iOS

The iOS host cannot use `sslConnector` (JVM-only API), and `PlatformSyncIos` has no
self-signed certificate generation. `HostInfo.tlsPort` therefore stays 0 on iOS, and the
iOS transport continues over HTTP. The JVM host keeps the HTTP connector so an iOS host
or client can still pair and sync. This is a downgrade window on the LAN: an active
attacker can strip `tlsPort` from the HTTP `HostInfo`. The pairing token and ECDH secret
remain the authenticity check until iOS TLS lands.

## Threat model and limits

- A passive observer of the HTTPS link learns nothing about the journal: the bodies stay
  AES-256-GCM encrypted, and HMAC covers the ciphertext.
- An active attacker on the HTTP bootstrap can force the plaintext path by removing
  `tlsPort`. Compare the fingerprint shown by both devices during pairing; a mismatched
  or unexpected value is the warning.
- A trusted peer cannot silently change certificates: the stored fingerprint is checked
  before the first request, and the pinned trust manager checks every handshake.
- Only the JVM/Android path is covered. Do not describe the feature as LAN-wide TLS until
  the iOS path lands.

## Tests

- `TlsSyncIntegrationTest` covers the advertised TLS port, a pinned request, a wrong pin,
  the HTTP-to-TLS bootstrap, and a full pairing plus push over TLS.
- `SyncTransportLifecycleTest` covers starting the host twice, which exposed the password
  zeroing defect in Netty.

## Next

- Add iOS client pinning in `IosSyncTransport` (Darwin challenge handler) and an iOS
  certificate path for hosting.
- When every supported platform speaks TLS, remove the HTTP connector or restrict it to
  the pairing bootstrap, and make `tlsPort` mandatory in `HostInfo`.
