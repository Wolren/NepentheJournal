package app.journal.sync

// Wave2 file split (file-size-governor, 800-line limit): every declaration
// formerly in this file moved VERBATIM to focused files in sync/transport/:
//   SyncTransport.kt (class shell: state, observers, lifecycle, status),
//   SyncPeerOps.kt (pairWithPeer), SyncDiscovery.kt (probeHostInfo,
//   protocol-version warn), SyncContinuousSession.kt (WS session, heartbeat,
//   reconnect, ack FIFO, delta build/validate). SyncHosting.kt was NOT split:
//   startHosting/stopHosting/syncWith are SyncEngine overrides and must stay
//   members of SyncTransport, same for the public revokeDevice.
// Package and class names are unchanged, so every import keeps resolving.
