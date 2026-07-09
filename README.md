# Psychonautica Journal

Privacy-first, multi-device psychedelic session journal.  
**No cloud. No accounts. LAN-only P2P sync.**

## Stack

| Layer | Technology |
|---|---|
| Shared UI | Compose Multiplatform 1.7.3 |
| Targets | Android + JVM Desktop + iOS |
| Database | Kotbase 3.2.1 (Couchbase Lite KMP) |
| HTTP / Ingest | Ktor 3.0.3 |
| Serialization | kotlinx.serialization 1.7.3 |
| P2P Sync | CBL URLEndpointListener + Replicator (EE) |

## Project Layout

```
composeApp/src/
  commonMain/kotlin/app/journal/
    model/          — 12 @Serializable VaultDocument types
    data/           — VaultRepository interface, DatabaseProvider (expect)
    sync/           — ConflictResolvers, PairingManager, LanDiscovery, SyncEngine
    ingest/         — PsychonautWikiIngestor (confirmed GraphQL schema), ManualImportAdapter
    ui/             — App.kt + Dashboard/Sessions/Substances/Sync screens

  androidMain/      — MainActivity, DatabaseProviderActual (Android KeyStore), TimeUtils
  desktopMain/      — Main.kt, DatabaseProviderActual (PKCS12/JVM), TimeUtils
  iosMain/          — MainViewController, DatabaseProviderActual (Keychain/ObjC), TimeUtils
```

## Build & Run

```bash
# Android
./gradlew :composeApp:installDebug

# Desktop (JVM)
./gradlew :composeApp:run

# iOS
# Open composeApp/iosApp/iosApp.xcodeproj in Xcode
# Ensure CouchbaseLite XCFramework is added via CocoaPods (see kotbase.dev/current/platforms/)
```

## P2P Sync

`URLEndpointListener` **requires Couchbase Lite Enterprise Edition**.

Default dependency (`kotbase-couchbase-lite`) = Community Edition:
- ✅ All SQL++ queries  ✅ FTS  ✅ Blobs  ✅ Observer flows  ✅ Local storage

To enable P2P:
1. Switch `libs.kotbase.couchbase.lite` → `libs.kotbase.ee` in `composeApp/build.gradle.kts`
2. Obtain Enterprise license (free 60-day eval at couchbase.com)

### Pairing Flow (token + TLS fingerprint, no passwords)
```
HOST                                        JOINER
────                                        ──────
generatePairingOffer()                      scan QR / enter token
→ shows QR + 6-char token (120s TTL)       consumePairingOffer(offer, token)
                                            → verifies token + expiry
Listener: ListenerCertificateAuthenticator  Replicator: pinnedServerCertificate
  (checks client cert fingerprint in DB)      (pins host fingerprint from offer)
Both persist Device record with fingerprint
```

### Conflict Resolution

| Collection | Strategy | Notes |
|---|---|---|
| session, person | FIELD_LEVEL_MERGE | Field-granularity; remote wins ties |
| dose, timelineEvent | APPEND_ONLY | Device-keyed IDs; no structural conflict |
| note | SIBLING_AND_FLAG | Keep local; embed remote as sibling; UI banner |
| substance, effect, interaction | REMOTE_SOURCE_WINS | Reference data; preserve userAnnotations |
| link | SET_UNION_MERGE | Union of edges; deduplicate by (fromId, toId, type) |
| attachment | LOCAL_WINS_RETAIN_BOTH | Keep local blob; flag remote for review |
| device, syncConfig | LOCAL_WINS_NEVER_MERGE | Security-sensitive; never propagate remotely |

`syncConfig` collections are **always filtered out of outbound replication** via a pushFilter.

## PsychonautWiki API

```
POST https://api.psychonautwiki.org/
Content-Type: application/json
Body: { "query": "{ substances(query: \"psilocybin\") { name roas { ... } } }" }
```

No API key required. Full confirmed schema in `ingest/PsychonautWikiIngestor.kt`.

## Platform TLS Key Storage

| Platform | Mechanism |
|---|---|
| Android | Android System KeyStore (hardware-backed, API 28+) |
| JVM Desktop | Java PKCS12 KeyStore / CNG Key Storage Provider |
| iOS / macOS | Keychain services (kSecAttrLabel-tagged) |
| Linux JVM | User-managed PKCS12 file (no OS standard) |
