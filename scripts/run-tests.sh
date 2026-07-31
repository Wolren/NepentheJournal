#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

ulimit -u 16384 2>/dev/null || true
pkill -f java 2>/dev/null || true
pkill -f gradle 2>/dev/null || true
sleep 1

FAST_TESTS=(
  app.journal.data.JournalRepositoryTest
  app.journal.data.EntityStoreTest
  app.journal.data.AppJsonTest
  app.journal.data.FuzzSeedTest
  app.journal.data.DataMigrationTest
  app.journal.data.InteractionCheckerTest
  app.journal.data.ClassInteractionCheckerTest
  app.journal.model.ModelSerializationTest
  app.journal.model.ToleranceCalculatorTest
  app.journal.ingest.ManualImportAdapterTest
  app.journal.ingest.SubstanceClassNormalizerTest
  app.journal.util.CsvExporterTest
  app.journal.util.ExportImportTest
  app.journal.export.obsidian.ObsidianNoteRendererTest
  app.journal.ui.substances.SubstanceScreenViewModelTest
  app.journal.ui.session.SessionListViewModelTest
  app.journal.sync.SyncContractTest
  app.journal.sync.SyncEngineTest
  app.journal.sync.SyncPayloadTest
  app.journal.sync.SyncWebSocketTest
  app.journal.data.JournalStoreTest
)

ALL_TESTS=(
  "${FAST_TESTS[@]}"
  app.journal.export.obsidian.ObsidianExportManagerTest
  app.journal.export.obsidian.ObsidianNoteImporterTest
  app.journal.ingest.DoseWikiIngestorTest
  app.journal.sync.DeviceTrustStoreTest
  app.journal.sync.IosSyncTransportIntegrationTest
  app.journal.sync.KtorSyncServerIntegrationTest
  app.journal.sync.SyncAuthenticatorTest
  app.journal.sync.SyncTransportTest
  app.journal.sync.SyncValidatorsTest
  app.journal.sync.TlsIdentityManagerTest
)

case "${1:-}" in
  --all)
    echo "Running ALL ${#ALL_TESTS[@]} test classes in one invocation..."
    args=(); for cls in "${ALL_TESTS[@]}"; do args+=(--tests "$cls"); done
    ./gradlew composeApp:desktopTest --no-daemon "${args[@]}"
    ;;
  --list)
    echo "Fast (${#FAST_TESTS[@]}): ${FAST_TESTS[*]}"
    echo "All (${#ALL_TESTS[@]}): ${ALL_TESTS[*]}"
    ;;
  *)
    echo "Running ${#FAST_TESTS[@]} fast test classes in one invocation..."
    args=(); for cls in "${FAST_TESTS[@]}"; do args+=(--tests "$cls"); done
    ./gradlew composeApp:desktopTest --no-daemon "${args[@]}"
    ;;
esac
