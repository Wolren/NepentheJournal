# Nepenthe Journal — Privacy Policy

**Last updated:** 2026-07-10

## Summary

Nepenthe Journal is an offline-first journal app. Your data stays on your device. We do not collect, transmit, or sell any personal information.

## What data the app stores

- **Journal entries** — session logs you create, including substance names, doses, routes of administration, timestamps, notes, and ratings
- **Substance reference data** — public data fetched from PsychonautWiki (cached locally)
- **Custom substances** — any substances you create and add to your personal library
- **Theme preferences** — your color scheme and display settings
- **Sync configuration** — network settings for optional P2P sync (see below)

## Where data is stored

All data is stored **locally on your device** in the app's private data directory. No data is uploaded to any cloud server, third-party service, or remote database.

## P2P sync (optional)

If you enable Device Sync, your journal data is transmitted **directly between your own devices over your local network (LAN)**. This uses:

- mDNS/DNS-SD (JmDNS) for peer discovery on the local network
- Ktor HTTP server/client for encrypted data transfer between devices

**No data passes through any external relay, cloud server, or third-party service.** Sync is entirely peer-to-peer and limited to your local network. You control when sync is active and which devices participate.

## No analytics, no tracking

The app contains **no analytics SDKs, no telemetry, no crash reporting, and no advertising**. No data about your usage, device, or journal entries is sent anywhere.

## Permissions

The app requests the following Android permissions only when needed for optional features:

| Permission | Purpose | Required? |
|---|---|---|
| `INTERNET` | P2P sync between your devices over LAN | Yes, for sync |
| `ACCESS_WIFI_STATE` | Discover other devices for P2P sync | Yes, for sync |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS discovery for P2P sync | Yes, for sync |
| `ACCESS_NETWORK_STATE` | Check network availability for sync | Yes, for sync |

These permissions are never used to transmit data to third parties.

## Data portability and deletion

You can export all your journal data as a JSON file from **Settings > Data > Export**. You can delete individual entries or all data at any time. Since all data is local, deleting the app removes all associated data from your device.

## Children's privacy

This app is not intended for users under 18 years of age. The app references psychoactive substances and is designed for adult personal record-keeping and harm reduction purposes only.

## Medical disclaimer

**This app is not a medical device and does not diagnose, treat, cure, or prevent any medical condition.** The substance reference data is sourced from PsychonautWiki and is provided for harm reduction and informational purposes only. Always consult a qualified healthcare professional for medical advice.

## Changes to this policy

If this privacy policy changes, the "Last updated" date at the top will be revised. Since the app does not phone home, you will see updated policies when you visit this URL.

## Business model pledge

This app will **never** include:
- Advertisements of any kind
- Subscription tiers or paid features
- Telemetry, analytics, or crash reporting
- Account requirements or cloud dependency

All functionality is and will always be free. This is a firm commitment, not a current-state description.

## Contact

For questions about this privacy policy, open an issue at:
https://github.com/Wolren/nepenthe-journal
