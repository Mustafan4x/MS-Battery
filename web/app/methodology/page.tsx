import type { Metadata } from "next"

export const metadata: Metadata = {
  title: "Methodology - BaselineMS gait pipeline",
}

export default function MethodologyPage() {
  return (
    <article className="prose-doc px-6 py-16">
      <h1>Methodology</h1>

      <p>
        BaselineMS is an Android app for people with Multiple Sclerosis (MS). Its
        gait module turns 30 seconds of phone-pocket IMU data into a set of
        spatiotemporal gait features (cadence, mean stride length, step time
        variability, stride asymmetry, double-support time, quality score). The
        pipeline is plain Kotlin, runs on-device, and depends on no cloud or
        network calls.
      </p>

      <h2>Pipeline</h2>

      <ol>
        <li>
          <strong>Orientation estimation.</strong> A from-scratch Madgwick
          complementary filter fuses 100 Hz gyroscope and accelerometer streams
          to recover a device-to-world quaternion (Madgwick 2010). The filter
          pre-warms with 5000 virtual iterations on the static gravity vector
          before the first walking sample is processed.
        </li>
        <li>
          <strong>Gravity removal.</strong> The first second of accelerometer
          samples (assumed stationary per the capture protocol) gives the
          gravity vector in the device frame. Subtracting it yields gravity-
          removed linear acceleration.
        </li>
        <li>
          <strong>World-frame projection.</strong> The Madgwick quaternion
          rotates the linear acceleration into the world frame, giving
          vertical, forward, and lateral components regardless of how the
          device is sitting in the pocket.
        </li>
        <li>
          <strong>Step detection.</strong> A peak finder on the low-pass-filtered
          vertical world-frame acceleration. Prominence floor 30 percent of the
          trial median peak amplitude; minimum inter-peak distance 250 ms;
          maximum 800 ms.
        </li>
        <li>
          <strong>Feature extraction.</strong> Cadence is 60 / mean inter-step
          interval. Stride length uses a Zero-Velocity-Update (ZUPT) integrator:
          forward acceleration is integrated between mid-stance instants (local
          minima of the linear acceleration magnitude), with velocity reset to
          zero at each mid-stance to bound drift.
        </li>
      </ol>

      <h2>Datasets</h2>

      <p>
        Four public datasets are replayed end-to-end through the same pipeline.
        Per-dataset details below; full metric definitions follow.
      </p>

      <h3>Santos et al. 2022</h3>
      <p>
        25 healthy adults, 499 walking trials across two sessions, leg-strap
        mounted Nexus 5 smartphone at 100 Hz. Motion capture provides per-trial
        ground truth (Vicon, heel-marker-derived heel-strike events). Citation:
        Santos et al., A multi-sensor human gait dataset, Sci Data 2022 (DOI{" "}
        <code>10.6084/m9.figshare.14727231</code>).
      </p>

      <h3>NONAN GaitPrint young adults</h3>
      <p>
        35 healthy young adults, 18 walking trials per subject across two days
        one week apart, Noraxon Ultium IMUs at 10 body locations at 200 Hz.
        Pelvis sensor stands in for the BaselineMS front-pocket production mount.
        The dataset publishes per-stride cadence and stride length directly.
        Citation: Wiles et al., NONAN GaitPrint, Sci Data 2023 (DOI{" "}
        <code>10.1038/s41597-023-02704-z</code>).
      </p>

      <h3>MAREA</h3>
      <p>
        20 healthy adults, 11 on an indoor protocol and 9 outdoors, Shimmer3
        accelerometers (no gyroscope) at waist, wrist, and both feet at 128 Hz.
        Foot-mounted force-sensitive resistors provide heel-strike events;
        cadence is derived from inter-strike intervals. Stride length is not
        reported (no spatial ground truth, and the Madgwick orientation filter
        degrades to accelerometer-only attitude on this dataset). Citation:
        Khandelwal and Wickström, Gait Posture 2017.
      </p>
      <p>
        Only walking is replayed. The three MAREA activities whose names end in
        Run append a running bout to the corresponding walk and are excluded.
        Level walking (treadmill, indoor, outdoor) is reported separately from
        inclined treadmill walking, because inclined walking appears in no other
        dataset here and would otherwise be 44 percent of the sample.
      </p>
      <p>
        Trials are 30 second windows matching the capture length the application
        actually uses. Two limits belong with the headline number. At 30 seconds
        the pipeline&apos;s own cadence output is quantized to 2 steps per minute,
        about 1.77 percent of median cadence, so a perfect step counter would
        still register roughly 0.43 percent mean absolute error; replaying the
        same recordings at full segment length, where quantization is negligible,
        gives 0.32 percent on level walking and 1.20 percent on inclined. And the
        error is systematically positive, a mean of 0.76 percent on level and
        1.66 percent on inclined walking, meaning the pipeline slightly over
        counts steps. Because cadence error depends on trial length through that
        quantization, comparing this row against the others is only sound where
        trial lengths match. MAREA is used here for measurement only and has not
        been allowed to tune any pipeline parameter.
      </p>

      <h3>Luo et al. 2020 (irregular surfaces)</h3>
      <p>
        30 healthy adults walking 9 outdoor surfaces (flat-even, cobble stone,
        stairs up and down, slopes up and down, banks left and right, grass) at
        self-selected pace, Xsens MTw IMUs at trunk, wrist, both thighs, both
        shanks at 100 Hz. The dataset does NOT publish per-trial spatiotemporal
        ground truth; the paper&rsquo;s Discussion section acknowledges this as a
        limitation. Luo is therefore validated via{" "}
        <em>cross-sensor agreement</em>: the same pipeline is run twice per
        trial, once on the trunk IMU (production-analog mount) and once on the
        left shank IMU (independent body location). Cross-sensor cadence
        agreement (Bland-Altman limits of agreement, Pearson correlation) is
        reported as the validation metric. Citation: Luo et al., A database of
        human gait performance on irregular and uneven surfaces, Sci Data 2020
        (DOI <code>10.1038/s41597-020-0563-y</code>).
      </p>

      <h2>Statistical methods</h2>

      <h3>Error metrics (Santos, NONAN, MAREA)</h3>
      <p>
        For each trial with published ground truth, percent error is computed as{" "}
        <code>100 * (recovered - truth) / truth</code>. The dataset-level
        headline is the mean absolute percent error across all trials; the
        median and 95th percentile of the absolute error are also reported as
        outlier-aware summary statistics.
      </p>

      <h3>Test-retest reliability (Santos, NONAN)</h3>
      <p>
        Datasets with repeated-session structure support ICC(3,1) absolute
        agreement (Shrout and Fleiss 1979; Koo and Li 2016 for the variant
        interpretation), where the rater is the pipeline running on a single
        device. Values above 0.75 are considered good test-retest reliability
        for clinical gait measures.
      </p>

      <h3>Cross-sensor agreement (Luo)</h3>
      <p>
        For each trial, the same pipeline is run on two body-distinct IMU
        streams (trunk and left shank). The per-trial cadence difference
        (trunk minus shank) is summarized as mean difference, 95 percent
        Bland-Altman limits of agreement (mean ± 1.96 SD), and mean absolute
        difference. Pearson correlation across trials is reported as a
        complementary measure of co-variation. Cross-sensor agreement is a
        published gait-pipeline validation methodology when ground truth is
        unavailable.
      </p>

      <h2>Limitations</h2>

      <ul>
        <li>
          <strong>Mount specificity of stride length.</strong> The ZUPT
          integration is calibrated for front-pocket mount. On Santos&rsquo;s
          leg-strap mount and NONAN&rsquo;s pelvis mount, the pipeline
          under-reads stride length by a large factor while preserving high
          test-retest reliability (the bias is reproducible across sessions,
          not noisy). Cadence transfers cleanly across mounts.
        </li>
        <li>
          <strong>No MS-specific dataset in the validation table.</strong> All
          four datasets are healthy adult cohorts. Galati et al. 2024 and the
          msense_ms_adls SimTK dataset provide clinical context but lack
          per-trial spatiotemporal ground truth suitable for direct comparison.
          Generalization to MS gait is an open question that real-world
          deployment will address.
        </li>
        <li>
          <strong>MAREA is accelerometer-only.</strong> The Madgwick filter
          degrades to accelerometer-only attitude on this dataset, and stride
          length is omitted accordingly.
        </li>
        <li>
          <strong>Luo lacks per-trial ground truth.</strong> Cross-sensor
          agreement validates pipeline consistency across mount positions but
          does not establish absolute accuracy. The Santos and NONAN results
          carry that burden.
        </li>
      </ul>

      <h2>References</h2>

      <ul>
        <li>
          Madgwick S. 2010. An efficient orientation filter for inertial and
          inertial/magnetic sensor arrays. Internal report, University of
          Bristol.
        </li>
        <li>
          Shrout PE, Fleiss JL. 1979. Intraclass correlations: uses in
          assessing rater reliability. Psychological Bulletin 86(2): 420-428.
        </li>
        <li>
          Koo TK, Li MY. 2016. A guideline of selecting and reporting
          intraclass correlation coefficients for reliability research.
          Journal of Chiropractic Medicine 15(2): 155-163.
        </li>
        <li>
          Santos G, Wanderley MM, Tavares T, Rocha A. 2022. A multi-sensor
          human gait dataset captured through an optical system and inertial
          measurement units. Scientific Data 9, DOI 10.1038/s41597-022-01638-2;
          dataset DOI 10.6084/m9.figshare.14727231.
        </li>
        <li>
          Wiles TM, Mangalam M, Sommerfeld JH et al. 2023. NONAN GaitPrint: An
          IMU gait database of healthy young adults. Scientific Data 10, DOI{" "}
          10.1038/s41597-023-02704-z.
        </li>
        <li>
          Khandelwal S, Wickström N. 2017. Evaluation of the performance of
          accelerometer-based gait event detection algorithms in different real-
          world scenarios using the MAREA gait database. Gait and Posture 51:
          84-90, DOI 10.1016/j.gaitpost.2016.09.023.
        </li>
        <li>
          Luo Y et al. 2020. A database of human gait performance on irregular
          and uneven surfaces collected by wearable sensors. Scientific Data 7,
          DOI 10.1038/s41597-020-0563-y.
        </li>
      </ul>
    </article>
  )
}
