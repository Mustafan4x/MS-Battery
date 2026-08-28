# BaselineMS

A native Android application that lets people living with Multiple Sclerosis self administer a short, sensor backed set of five tests once a week, track results longitudinally on device, and share a clinician facing PDF report.

The technical centerpiece is a gait analysis pipeline that turns 30 seconds of phone IMU data into stride length, cadence, step time variability, and stride asymmetry. Pipeline accuracy is validated against a measured walking course; methodology and error numbers will appear in the Validation section below as the experiments complete.

## Problem

MS clinic visits typically happen every three to six months. Between visits, people living with MS often want a simple, self directed way to keep an objective record of how their walking, hand dexterity, vision, cognition, and speech are changing, so that they can bring something concrete to their next neurology appointment. Existing research apps in this space are study only and not available as a self administered self tracking application for the broader community. BaselineMS aims to fill that gap as a personal record keeping tool, not as a diagnostic instrument.

## Solution

A weekly five to ten minute session at home runs five short tests on the user's phone: a bilateral tap test, a 30 second walk, a low contrast vision test, a Symbol Digit Modalities Test, and a 30 second voice reading. Each test design mirrors a published clinical instrument; the application is not a substitute for any of those instruments and does not produce clinical interpretations. Results are stored on device. The user can generate a PDF report on demand and share it with their neurologist. Nothing leaves the device unless the user explicitly chooses to share an export.

## What this is not

This application is not a medical device. It does not diagnose or treat any condition. Results are not validated for any clinical use. Do not start, stop, or change any treatment based on these results. Share results with your neurologist for clinical decisions.

## Status

The data layer, the test module abstraction, the weekly battery orchestrator, and the Compose UI shell are in place. All five tests are implemented and wired into the weekly battery: the bilateral tap test, the gait analysis pipeline (IMU capture, orientation tracking, world frame projection, feature extraction), the low contrast vision test, the Symbol Digit Modalities Test smartphone variant, and the 30 second voice reading test with Praat compatible acoustic features. A longitudinal Reports screen surfaces per test trends with drill in detail screens for each instrument. PDF and CSV export through the Android Share Intent ships from that same Reports screen on demand. Remaining work is the accessibility audit, the beta cohort recruitment and run, and the Play Store internal testing track polish pass.

## How it is built

The application is a native Android codebase in Kotlin and Jetpack Compose, organized into separated layers: Compose UI, weekly battery orchestrator, test modules, IMU signal capture, signal processing (filters, orientation tracking, feature extraction), Room data store, and a future reporting and export layer.

### Running

Open `~/src/BaselineMS` in Android Studio Iguana or later, select an Android 12 or later emulator (or attach a physical device with USB debugging enabled), and press Run.

From the command line:

```
./gradlew :app:installDebug
adb shell am start -n com.mustafanazeer.baselinems/.MainActivity
```

### Testing

```
./gradlew :app:testDebugUnitTest
```

This runs every JVM unit test, including Robolectric Room repository tests, orchestrator tests, and the gait pipeline integration tests.

## Privacy

All data is stored on device. The application does not declare the `android.permission.INTERNET` permission. There is no cloud sync, no account, no analytics SDK, and no third party telemetry. Microphone audio is processed in memory and discarded after feature extraction unless the user explicitly opts in to retention. Camera frames from the vision test ambient brightness check are read once and discarded. Export through Android's Share Intent is the only outward facing action and it is fully user initiated.

## Export

From the Reports screen, the user can generate a PDF report and a long format CSV on demand. The PDF cover mirrors the disclaimer language in SPEC Section 10 (this is not a medical device, results are not validated for any clinical use, share with a neurologist for clinical decisions) and is followed by per test trend charts and a summary table of recent sessions. The CSV is long format with one row per feature value per test result, which suits spreadsheet pivots and direct ingestion into R or Python. Both files are written to the application's private `cacheDir` and offered through the Android Share Intent via a scoped `FileProvider`. Nothing is written to shared or external storage automatically.

## Live demo

A web showcase of the gait pipeline running on multiple public datasets, with the full validation table and methodology page, is live at https://web-pi-one-62.vercel.app. The page reads directly from the same per-dataset replay results that drive the table below; sources are at `web/` in this repository.

## Validation

The gait pipeline is validated against three publicly downloadable IMU gait datasets covering 90 unique participants across three independent research groups (a fourth dataset, MAREA, is pending an email release form). Each dataset is replayed end to end through the production `GaitPipeline` and the resulting per trial numbers are aggregated into `web/public/validation-summary.json` by `scripts/aggregate-validation-results.py`.

### Against published ground truth

| Dataset | Mount | N | Trials | Cadence MAE | Stride MAE | Cadence ICC(3,1) | Stride ICC(3,1) |
|---|---|---|---|---|---|---|---|
| Santos et al. 2022 | Leg strap (smartphone) | 25 | 499 | 19.53% | 66.36% | 0.573 | 0.806 |
| NONAN GaitPrint (Wiles et al. 2023) | Pelvis (pocket analog) | 35 | 609 | **0.53%** | 86.83% | **0.946** | 0.650 |
| MAREA (Khandelwal and Wickström 2017) | Waist (accel only) | 20 | pending | pending | n/a | n/a | n/a |

### Cross sensor agreement

| Dataset | Mounts compared | N | Trials | Mean diff | 95% LoA | Mean abs diff | Pearson r |
|---|---|---|---|---|---|---|---|
| Luo et al. 2020 (irregular surfaces) | Trunk vs Left shank | 30 | 1620 | -2.03 spm | [-21.78, +17.72] spm | 6.22 spm | 0.529 |

### What these numbers say

Cadence is the headline. On NONAN's pelvis sensor (the closest publicly available analog to the BaselineMS front pocket production mount), the pipeline reproduces published per stride cadence within 0.53 percent on average across 35 participants and 609 trials, with a test retest ICC(3,1) of 0.946 (excellent reliability per Koo and Li 2016). On Luo's outdoor surfaces dataset, where no per trial ground truth exists, cadence agreement between trunk and shank running the same pipeline sits inside Bland-Altman limits of [-21.78, +17.72] steps per minute across 30 participants and 1620 trials across nine surface types.

Stride length is mount specific. The Zero Velocity Update integrator is calibrated for front pocket mounting; on Santos's leg strap and NONAN's pelvis the pipeline under reads stride length significantly. The high ICC(3,1) of 0.806 (Santos) and 0.650 (NONAN) on stride length tells us the bias is reproducible across sessions, not noise, which is what we want for a calibrated pipeline on a non production mount.

Full methodology (pipeline design, statistical methods, per dataset notes, limitations) is on the live demo `/methodology` page. Per trial results CSVs land under `app/build/validation/` after the env var gated replay tests run.

### Dataset citations

Each dataset below is the work of an independent research group and is used here
under its own terms. Please cite the original authors, not this repository, when
referring to any of these numbers.

- **Santos G, Wanderley MM, Tavares T, Rocha A.** 2022. A multi-sensor human gait dataset captured through an optical system and inertial measurement units. *Scientific Data* 9. DOI [10.1038/s41597-022-01638-2](https://doi.org/10.1038/s41597-022-01638-2). Dataset: DOI [10.6084/m9.figshare.14727231](https://doi.org/10.6084/m9.figshare.14727231).
- **Wiles TM, Mangalam M, Sommerfeld JH, et al.** 2023. NONAN GaitPrint: An IMU gait database of healthy young adults. *Scientific Data* 10:867. DOI [10.1038/s41597-023-02704-z](https://doi.org/10.1038/s41597-023-02704-z).
- **Khandelwal S, Wickström N.** 2017. Evaluation of the performance of accelerometer-based gait event detection algorithms in different real-world scenarios using the MAREA gait database. *Gait and Posture* 51:84-90. DOI [10.1016/j.gaitpost.2016.09.023](https://doi.org/10.1016/j.gaitpost.2016.09.023). ISSN 0966-6362. The MAREA gait database is released by Halmstad University under terms that permit non commercial research use and require this citation; it is not redistributed here in whole or in part.
- **Luo Y, et al.** 2020. A database of human gait performance on irregular and uneven surfaces collected by wearable sensors. *Scientific Data* 7:219. DOI [10.1038/s41597-020-0563-y](https://doi.org/10.1038/s41597-020-0563-y).

## Retention

Reserved for the beta cohort retention numbers. Day 1, day 7, day 14, and day 30 retention curves with reminders enabled will appear here. The empirical floor used to size the design goal is the Galati et al. 2024 (JMIR Human Factors 11:e57033) day 30 figure of 30.8 percent with reminders versus 9.7 percent without; the design goal is to meet or exceed that floor.

## Acknowledgments

The retention design and the gait pipeline rationale draw on two published analyses of the Floodlight Open dataset:

- Oh et al. 2024, *Scientific Reports*, "Use of smartphone-based remote assessments of multiple sclerosis in Floodlight Open, a global, prospective, open-access study" (DOI 10.1038/s41598-023-49299-4).
- Galati et al. 2024, *JMIR Human Factors* 11:e57033, on retention and engagement in the Floodlight Open US cohort.

## Repository

https://github.com/MustafaNazeer/BaselineMS
