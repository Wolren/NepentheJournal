# Android benchmarks

## Suite

`macrobenchmark` holds two Macrobenchmark tests against the shipped app:

- `ScrollBenchmark.flingSubstanceList` measures cold startup and frame timing while the
  substance list flings five times. It is the longest scrolling surface in the app.
- `JourneyBenchmark.tabJourney` measures frame timing across the three main tabs:
  dashboard, substances, settings. Each step waits for a screen marker, so broken
  navigation fails the benchmark.

Both use `CompilationMode.Partial(BaselineProfileMode.Require)`. A missing baseline
profile fails the run instead of silently measuring an unoptimized build.

## Local run

Generate the profile first, then run the suite:

```powershell
.\gradlew.bat :baselineprofile:collectNonMinifiedReleaseBaselineProfile -Pnepenthe.emulatorAbi=x86_64
.\gradlew.bat :macrobenchmark:connectedBenchmarkReleaseAndroidTest -Pnepenthe.emulatorAbi=x86_64
```

The emulator must be API 26 or newer and x86_64. `nepenthe.emulatorAbi=x86_64` adds the
emulator ABI to the release build, which otherwise ships only ARM libraries.

Results land in:

- `macrobenchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/`
  with one Perfetto trace per iteration and a benchmark JSON file.
- `macrobenchmark/build/outputs/androidTest-results/connected/benchmarkRelease/`.

## CI

The `android-macrobenchmark` job in `.github/workflows/ci.yml` starts an API 35 emulator,
regenerates the baseline profile, runs the suite, and uploads the traces and report. The
emulator is a flow guard, not a performance number source: frame times on a shared CI
emulator vary enough that only a gross regression is visible.
