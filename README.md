# Q50 Telemetry

A live gauge dashboard for the **Infiniti Q50 (2016, 3.7 VQ37VHR)** InTouch infotainment system (first generation). It reads the vehicle signals that the head unit already publishes through Android's `SensorManager` and shows them on the lower screen.

The app is **read-only**: it never writes to the CAN bus, changes vehicle settings or calls any control API.

## What it shows

One 800×480 screen. Values refresh 4 times per second, and the RPM ring sweeps between readings at 12 frames per second:

| Area     | Signals                                                                                                                                        |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| RPM ring | Engine RPM with shift lights, redline at 7500 and peak marker                                                                                  |
| Center   | Vehicle speed (km/h) and gear position (P/R/N/D, M1–M7)                                                                                        |
| Pedals   | Accelerator and brake position                                                                                                                 |
| Bars     | Coolant and oil temperature (with cold / warning / danger levels), torque (N·m, negative when engine braking) and power (kW, with peak marker) |
| Bottom   | Tire pressures (psi), distance to empty and a G-force ball with peak                                                                           |

**RPM fallback.** On some ignition cycles the head unit reports engine RPM as 0 for the whole session. In that case the dashboard estimates RPM from the power and torque signals, which carry the same reading, and labels it **EST**.

Missing or stale readings show `--` instead of a misleading value. Hold the RPM ring for about a second to reset the peaks. Labels follow the system language: English, Spanish or French.

## Project layout

| Module       | Package                   | Contents                                                                                            |
| ------------ | ------------------------- | --------------------------------------------------------------------------------------------------- |
| `core`       | `com.q50.core`            | Sensor capture on a background thread and thread-safe state, including the RPM estimate             |
| `dashboard`  | `com.q50.dashboard`       | **Q50 Telemetry**, the dashboard app                                                                |
| `app`        | `com.q50.sensorinspector` | **Q50 Sensor Inspector**, a diagnostic screen that lists every OEM sensor with its event statistics |
| `validation` | —                         | Dependency-free JVM tests                                                                           |

## Platform constraints

The InTouch runs an old Android build (API 10) on modest hardware, so the code deliberately stays simple:

- Java only, `minSdk 10` / `targetSdk 10`, **no runtime dependencies** (no AndroidX, AppCompat or Kotlin).
- Sensor capture, state and UI are separate; the UI refreshes at most 4 times per second.
- The dashboard draws its static parts once into a bitmap, and allocates nothing while drawing a frame.

## Installing

The InTouch installs third-party apps through **App Garage → Install Apps via USB**, which expects the APK packaged in the head unit's EPK format. The packaging tool is **not** part of this repository.

## Safety and disclaimer

- Set up and test the app with the vehicle **parked**. Do not interact with the screen while driving.
- Readings come from the head unit and may be missing, delayed or wrong. Do not rely on this app for safety-critical decisions.
- This is an independent hobby project. It is **not affiliated with, endorsed by or supported by** Infiniti, Nissan or the head-unit suppliers. All trademarks belong to their owners.
- Installing third-party software on your vehicle is at your own risk.

## License

The code is released under the [MIT License](LICENSE).

The bundled font, Barlow Condensed, is © The Barlow Project Authors and licensed under the SIL Open Font License 1.1 (`dashboard/src/main/assets/fonts/OFL.txt`).
