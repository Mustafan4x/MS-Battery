package com.mustafanazeer.baselinems.validation

import com.mustafanazeer.baselinems.dsp.GaitPipeline
import com.mustafanazeer.baselinems.dsp.ImuSample
import com.mustafanazeer.baselinems.dsp.Madgwick
import com.mustafanazeer.baselinems.dsp.Quaternion
import com.mustafanazeer.baselinems.dsp.Vector3
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Replays the MAREA gait database (Khandelwal and Wickström 2017, Gait and Posture
 * 51, pages 84 to 90, DOI 10.1016/j.gaitpost.2016.09.023) through `GaitPipeline`
 * and emits per trial accuracy results to a CSV. Configuration mirrors
 * `NonanReplayTest`: env var `BASELINEMS_MAREA_PATH` (or system property
 * `baselinems.marea.path`) points at the normalized dataset root produced by
 * `scripts/marea-preprocess.py`.
 *
 * MAREA is the waist mounted accelerometer row of the validation table, and it
 * differs from the other three datasets in two ways that this test has to respect.
 *
 * It is accelerometer only. No sensor placement in MAREA carries a gyroscope, so
 * the normalized CSVs hold a zero gyroscope vector and Madgwick degrades to
 * gravity alignment from the accelerometer alone. `Madgwick.kt` documents that
 * this converges at beta = 0.1 and it is the same path the synthetic fixtures
 * exercise, but it is still a methodological deviation. It is the reason this
 * test reports cadence only and never touches stride length, and the reason the
 * ground truth CSV carries no `mean_stride_length_meters` column at all.
 *
 * Its ground truth comes from foot mounted force sensitive resistors, which give
 * explicit heel strike indices rather than motion capture derived spatiotemporal
 * variables. Cadence truth is therefore direct rather than inferred.
 *
 * Each participant contributes one session, so MAREA supports cadence error
 * reporting but not the test retest ICC that NONAN's two sessions support.
 *
 * Level and inclined walking are summarized separately. `treadIncline` is the
 * single longest recording per subject and would otherwise be roughly 44 percent
 * of the sample, which would make the headline number describe a walking regime
 * that appears in no other dataset in the table.
 *
 * Expected dataset layout:
 *   $BASELINEMS_MAREA_PATH/
 *   ├── participant-NN/
 *   │   └── session-1/
 *   │       ├── trial-NNN/
 *   │       │   ├── smartphone.csv     (timestamp_ns,ax,ay,az,gx,gy,gz at 128 Hz, gyro all zero)
 *   │       │   └── ground-truth.csv   (cadence_steps_per_minute,...,activity,steps,duration_s)
 *   │       └── ...
 *   └── participant-NN/...
 *
 * Sample rate is 128 Hz; the pipeline is rate agnostic so no rate conversion is
 * applied, but the gravity window and the Madgwick pre warm `dt` are sized for it.
 */
class MareaReplayTest {

    private companion object {
        const val SAMPLE_RATE_HZ = 128.0
        const val NOMINAL_DT = 1.0 / SAMPLE_RATE_HZ
        const val GRAVITY_WINDOW_SAMPLES = 128 // 1 s at 128 Hz
        val INCLINE_ACTIVITIES = setOf("treadIncline")
    }

    @Test
    fun `replay MAREA gait database and emit per-trial cadence accuracy CSV`() {
        val datasetRoot = System.getProperty("baselinems.marea.path")
            ?: System.getenv("BASELINEMS_MAREA_PATH")
        assumeNotNull(
            "set system property baselinems.marea.path or env BASELINEMS_MAREA_PATH to enable",
            datasetRoot
        )
        val root = File(datasetRoot!!)
        assumeTrue("dataset root does not exist: ${root.absolutePath}", root.exists() && root.isDirectory)

        val outputCsv = File(
            System.getProperty("baselinems.marea.output", "build/validation/marea-replay-results.csv")!!
        )
        outputCsv.parentFile?.mkdirs()

        val pipeline = GaitPipeline()
        val results = mutableListOf<TrialResult>()

        outputCsv.bufferedWriter().use { out ->
            out.append("participant,session,trial,activity,")
            out.append("cadence_recovered,cadence_truth,cadence_pct_error,")
            out.append("quality_score,detected_step_count\n")

            val participantDirs = root.listFiles { f -> f.isDirectory && f.name.startsWith("participant") }
                ?.sortedBy { it.name }
                ?: emptyList()
            assumeTrue("no participant-* directories under ${root.absolutePath}", participantDirs.isNotEmpty())

            for (participantDir in participantDirs) {
                val sessionDirs = participantDir.listFiles { f -> f.isDirectory && f.name.startsWith("session") }
                    ?.sortedBy { it.name } ?: continue
                for (sessionDir in sessionDirs) {
                    val trialDirs = sessionDir.listFiles { f -> f.isDirectory && f.name.startsWith("trial") }
                        ?.sortedBy { it.name } ?: continue
                    for (trialDir in trialDirs) {
                        val imuCsv = File(trialDir, "smartphone.csv")
                        val truthCsv = File(trialDir, "ground-truth.csv")
                        if (!imuCsv.exists() || !truthCsv.exists()) {
                            System.err.println("skip ${trialDir.relativeTo(root)}: missing input")
                            continue
                        }
                        val raw = parseSmartphoneCsv(imuCsv)
                        if (raw.isEmpty()) continue
                        val samples = preprocessTrial(raw)
                        val truth = parseGroundTruth(truthCsv)
                        val recovered = try {
                            pipeline.process(samples)
                        } catch (e: Exception) {
                            System.err.println("FAIL ${trialDir.relativeTo(root)}: ${e.message}")
                            continue
                        }

                        val cadenceErr = pctError(recovered.cadenceStepsPerMinute, truth.cadenceStepsPerMinute)

                        out.append(participantDir.name).append(',')
                        out.append(sessionDir.name).append(',')
                        out.append(trialDir.name).append(',')
                        out.append(truth.activity).append(',')
                        out.append(recovered.cadenceStepsPerMinute.toString()).append(',')
                        out.append(truth.cadenceStepsPerMinute.toString()).append(',')
                        out.append(cadenceErr.toString()).append(',')
                        out.append(recovered.qualityScore.toString()).append(',')
                        out.append(recovered.detectedStepCount.toString()).append('\n')

                        results += TrialResult(
                            participant = participantDir.name,
                            trial = trialDir.name,
                            activity = truth.activity,
                            cadencePctError = cadenceErr,
                            qualityScore = recovered.qualityScore
                        )
                    }
                }
            }
        }

        assumeTrue("no trials processed", results.isNotEmpty())

        val level = results.filter { it.activity !in INCLINE_ACTIVITIES }
        val incline = results.filter { it.activity in INCLINE_ACTIVITIES }
        val meanCadenceErr = results.map { abs(it.cadencePctError) }.average()
        val levelCadenceErr = if (level.isEmpty()) Double.NaN else level.map { abs(it.cadencePctError) }.average()

        println("MAREA replay summary:")
        println("  trials processed: ${results.size} across ${results.map { it.participant }.distinct().size} participants")
        println("  level walking:  ${level.size} trials, mean absolute cadence error ${"%.2f".format(levelCadenceErr)}%")
        if (incline.isNotEmpty()) {
            val inclineErr = incline.map { abs(it.cadencePctError) }.average()
            println("  inclined walking: ${incline.size} trials, mean absolute cadence error ${"%.2f".format(inclineErr)}%")
        }
        for ((activity, rows) in results.groupBy { it.activity }.toSortedMap()) {
            println("    ${activity.padEnd(13)} ${rows.size} trials, ${"%.2f".format(rows.map { abs(it.cadencePctError) }.average())}%")
        }
        println("  pooled (level plus incline): ${"%.2f".format(meanCadenceErr)}%")
        println("  per-trial results written to: ${outputCsv.absolutePath}")

        // The headline figure for this row is level walking, so the sanity ceiling
        // guards that rather than the pooled value. As with the other replay tests,
        // 50 percent catches a miscalibrated parser without freezing an accuracy
        // threshold into the test; the reported numbers belong in the validation
        // report, not here.
        assertTrue(
            "level walking mean absolute cadence error $levelCadenceErr exceeds the 50% sanity ceiling",
            levelCadenceErr < 50.0
        )
    }

    private data class TrialResult(
        val participant: String, val trial: String, val activity: String,
        val cadencePctError: Double, val qualityScore: Double
    )

    private data class GroundTruth(
        val cadenceStepsPerMinute: Double,
        val activity: String
    )

    private data class RawImu(
        val timestampNanos: Long, val accelerometer: Vector3, val gyroscope: Vector3
    )

    private fun pctError(recovered: Double, truth: Double): Double =
        if (truth == 0.0) 0.0 else 100.0 * (recovered - truth) / truth

    private fun parseSmartphoneCsv(csv: File): List<RawImu> {
        val out = ArrayList<RawImu>(4096)
        csv.bufferedReader().use { reader ->
            reader.readLine() ?: return emptyList()
            for (line in reader.lineSequence()) {
                if (line.isBlank()) continue
                val p = line.split(',')
                if (p.size < 7) continue
                val parsed = try {
                    RawImu(
                        timestampNanos = p[0].toLong(),
                        accelerometer = Vector3(p[1].toDouble(), p[2].toDouble(), p[3].toDouble()),
                        gyroscope = Vector3(p[4].toDouble(), p[5].toDouble(), p[6].toDouble())
                    )
                } catch (_: NumberFormatException) {
                    continue
                }
                out += parsed
            }
        }
        return out
    }

    private fun parseGroundTruth(csv: File): GroundTruth {
        val lines = csv.readLines().filter { it.isNotBlank() }
        require(lines.size >= 2)
        val header = lines[0].split(',').map { it.trim() }
        val cadenceIdx = header.indexOf("cadence_steps_per_minute")
        val activityIdx = header.indexOf("activity")
        require(cadenceIdx >= 0) { "ground-truth.csv is missing cadence_steps_per_minute: ${csv.absolutePath}" }
        val row = lines[1].split(',').map { it.trim() }
        return GroundTruth(
            cadenceStepsPerMinute = row[cadenceIdx].toDouble(),
            activity = if (activityIdx >= 0) row[activityIdx] else "unknown"
        )
    }

    private fun preprocessTrial(raw: List<RawImu>): List<ImuSample> {
        if (raw.isEmpty()) return emptyList()
        val gravityWindow = minOf(GRAVITY_WINDOW_SAMPLES, raw.size)
        var gx = 0.0; var gy = 0.0; var gz = 0.0
        for (i in 0 until gravityWindow) {
            gx += raw[i].accelerometer.x
            gy += raw[i].accelerometer.y
            gz += raw[i].accelerometer.z
        }
        val gravity = Vector3(gx / gravityWindow, gy / gravityWindow, gz / gravityWindow)

        val madgwick = Madgwick(beta = 0.1)
        repeat(5000) { madgwick.update(Vector3.ZERO, gravity, NOMINAL_DT) }

        val out = ArrayList<ImuSample>(raw.size)
        var prevTimestampNanos = 0L
        for (r in raw) {
            val dt = if (prevTimestampNanos == 0L) NOMINAL_DT
            else (r.timestampNanos - prevTimestampNanos) / 1_000_000_000.0
            prevTimestampNanos = r.timestampNanos
            val safeDt = if (dt <= 0.0) NOMINAL_DT else dt
            // Gyroscope is identically zero for every MAREA sample; Madgwick reduces
            // to accelerometer gravity alignment on this path.
            madgwick.update(r.gyroscope, r.accelerometer, safeDt)
            val orientation: Quaternion = madgwick.orientation()
            val linear = Vector3(
                r.accelerometer.x - gravity.x,
                r.accelerometer.y - gravity.y,
                r.accelerometer.z - gravity.z
            )
            out += ImuSample(
                timestampNanos = r.timestampNanos,
                accelerometer = r.accelerometer,
                gyroscope = r.gyroscope,
                linearAcceleration = linear,
                rotationVector = orientation
            )
        }
        return out
    }
}
