# Sync transport TLS

Status: JVM and Android hosts serve a pinned TLS connector. The iOS client pins those
hosts too. The iOS host itself still uses plain HTTP; that is the accepted scope for this
release. The HTTP connector stays up for iOS hosts and for the first pairing bootstrap.

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

The iOS client reads `HostInfo.tlsPort`, pins the fingerprint with the Ktor Darwin
challenge handler, and stores the port with the trusted peer. Pairing verifies the
certificate on its first HTTPS request; sync and the host challenge use the stored pin.
[IosTlsPinningTest](../composeApp/src/iosTest/kotlin/app/journal/sync/IosTlsPinningTest.kt)
covers the URL choice and the fingerprint encoding on the simulator.

The iOS host cannot use `sslConnector` (JVM-only API), and the Ktor CIO server engine on
Kotlin/Native has no TLS connector. `HostInfo.tlsPort` therefore stays 0 on iOS, and the
iOS transport continues to host over HTTP. **Scope decision (2026-11-21):** client-only
iOS TLS is accepted for this release. The iOS host stays HTTP; a native TLS listener is
future work. The JVM host keeps the HTTP connector so an iOS host can still pair and
sync. This is a downgrade window on the LAN: an active attacker can strip `tlsPort` from
the HTTP `HostInfo`. The pairing token and ECDH secret remain the authenticity check
until iOS host TLS lands.

## Threat model and limits

- A passive observer of the HTTPS link learns nothing about the journal: the bodies stay
  AES-256-GCM encrypted, and HMAC covers the ciphertext.
- An active attacker on the HTTP bootstrap can force the plaintext path by removing
  `tlsPort`. Compare the fingerprint shown by both devices during pairing; a mismatched
  or unexpected value is the warning.
- A trusted peer cannot silently change certificates: the stored fingerprint is checked
  before the first request, and the pinned trust manager checks every handshake.
- The iOS client path is covered. The iOS host and any JVM client talking to an iOS host
  use HTTP. Do not describe the feature as LAN-wide TLS.

## Tests

- `TlsSyncIntegrationTest` covers the advertised TLS port, a pinned request, a wrong pin,
  the HTTP-to-TLS bootstrap, and a full pairing plus push over TLS.
- `IosTlsPinningTest` covers the iOS URL choice and fingerprint encoding, and runs in the
  `iosSimulatorArm64Test` job.
- `SyncTransportLifecycleTest` covers starting the host twice, which exposed the password
  zeroing defect in Netty.

## Future work

- Add a Network.framework TLS listener to the iOS host so it can advertise a certificate
  and a real TLS port.
- When every supported platform speaks TLS, remove the HTTP connector or restrict it to
  the pairing bootstrap, and make `tlsPort` mandatory in `HostInfo`.
