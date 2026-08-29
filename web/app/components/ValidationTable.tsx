import type { DatasetSummary } from "@/lib/types"

interface Props {
  datasets: DatasetSummary[]
}

function fmtPct(x: number | undefined): string {
  return x === undefined ? "n/a" : `${x.toFixed(1)}%`
}

function fmtNum(x: number | undefined, digits = 2): string {
  return x === undefined ? "n/a" : x.toFixed(digits)
}

export function ValidationTable({ datasets }: Props) {
  const groundTruth = datasets.filter((d) => d.validation_kind === "ground_truth_error")
  const crossSensor = datasets.filter((d) => d.validation_kind === "cross_sensor_agreement")

  return (
    <section id="validation" className="py-20 px-6">
      <div className="max-w-5xl mx-auto">
        <h2 className="text-2xl font-semibold mb-2">Validation results</h2>
        <p className="text-sm text-[var(--color-ink-soft)] mb-10 max-w-3xl">
          Each row is one dataset replayed end-to-end through the production
          gait pipeline. Datasets with published per-trial spatiotemporal
          ground truth are reported as error against truth (top table);
          datasets without published ground truth are reported via cross-sensor
          agreement using two independent IMUs run through the same pipeline
          (bottom table).
        </p>

        <h3 className="text-sm uppercase tracking-widest text-[var(--color-ink-soft)] mb-4">
          Against published ground truth
        </h3>
        <div className="overflow-x-auto mb-12">
          <table className="w-full text-sm">
            <thead className="text-left border-b border-[var(--color-muted)]">
              <tr>
                <th className="py-3 pr-6">Dataset</th>
                <th className="py-3 pr-6">Mount</th>
                <th className="py-3 pr-6 numeric">N</th>
                <th className="py-3 pr-6 numeric">Trials</th>
                <th className="py-3 pr-6 numeric">Cadence MAE</th>
                <th className="py-3 pr-6 numeric">Stride MAE</th>
                <th className="py-3 pr-6 numeric">Cadence ICC(3,1)</th>
                <th className="py-3 pr-6 numeric">Stride ICC(3,1)</th>
              </tr>
            </thead>
            <tbody>
              {groundTruth.map((ds) => (
                <tr key={ds.key} className="border-b border-[var(--color-muted)]/50 align-top">
                  <td className="py-4 pr-6">
                    <div className="font-medium">{ds.name}</div>
                    <div className="text-xs text-[var(--color-ink-soft)] mt-1 max-w-xs">
                      {ds.citation}
                    </div>
                  </td>
                  <td className="py-4 pr-6">{ds.mount}</td>
                  <td className="py-4 pr-6 numeric">{ds.n_participants}</td>
                  <td className="py-4 pr-6 numeric">
                    {ds.status === "complete" ? (
                      <>
                        {ds.metrics.headline_n_trials ?? ds.n_trials_processed}
                        {ds.metrics.headline_n_trials !== undefined && (
                          <div className="text-xs text-[var(--color-ink-soft)] mt-1">
                            of {ds.n_trials_processed}
                          </div>
                        )}
                      </>
                    ) : (
                      <span className="text-[var(--color-ink-soft)]">pending</span>
                    )}
                  </td>
                  <td className="py-4 pr-6 numeric">
                    {fmtPct(ds.metrics.cadence_mae_pct)}
                    {ds.metrics.headline_subgroup && (
                      <div className="text-xs text-[var(--color-ink-soft)] mt-1">
                        {ds.metrics.headline_subgroup} only
                      </div>
                    )}
                  </td>
                  <td className="py-4 pr-6 numeric">{fmtPct(ds.metrics.stride_length_mae_pct)}</td>
                  <td className="py-4 pr-6 numeric">{fmtNum(ds.metrics.cadence_icc_3_1, 3)}</td>
                  <td className="py-4 pr-6 numeric">{fmtNum(ds.metrics.stride_length_icc_3_1, 3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {crossSensor.length > 0 && (
          <>
            <h3 className="text-sm uppercase tracking-widest text-[var(--color-ink-soft)] mb-4">
              Cross-sensor agreement
            </h3>
            <div className="overflow-x-auto mb-12">
              <table className="w-full text-sm">
                <thead className="text-left border-b border-[var(--color-muted)]">
                  <tr>
                    <th className="py-3 pr-6">Dataset</th>
                    <th className="py-3 pr-6">Mounts compared</th>
                    <th className="py-3 pr-6 numeric">N</th>
                    <th className="py-3 pr-6 numeric">Trials</th>
                    <th className="py-3 pr-6 numeric">Mean diff (spm)</th>
                    <th className="py-3 pr-6 numeric">95% LoA</th>
                    <th className="py-3 pr-6 numeric">Mean |diff|</th>
                    <th className="py-3 pr-6 numeric">Pearson r</th>
                  </tr>
                </thead>
                <tbody>
                  {crossSensor.map((ds) => (
                    <tr key={ds.key} className="border-b border-[var(--color-muted)]/50 align-top">
                      <td className="py-4 pr-6">
                        <div className="font-medium">{ds.name}</div>
                        <div className="text-xs text-[var(--color-ink-soft)] mt-1 max-w-xs">
                          {ds.citation}
                        </div>
                      </td>
                      <td className="py-4 pr-6">{ds.mount}</td>
                      <td className="py-4 pr-6 numeric">{ds.n_participants}</td>
                      <td className="py-4 pr-6 numeric">{ds.n_trials_processed}</td>
                      <td className="py-4 pr-6 numeric">{fmtNum(ds.metrics.mean_difference_spm, 2)}</td>
                      <td className="py-4 pr-6 numeric">
                        {ds.metrics.loa_lower_spm !== undefined && ds.metrics.loa_upper_spm !== undefined
                          ? `[${ds.metrics.loa_lower_spm.toFixed(1)}, ${ds.metrics.loa_upper_spm.toFixed(1)}]`
                          : "n/a"}
                      </td>
                      <td className="py-4 pr-6 numeric">{fmtNum(ds.metrics.mean_absolute_difference_spm, 2)}</td>
                      <td className="py-4 pr-6 numeric">{fmtNum(ds.metrics.pearson_r, 3)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}

        <div className="space-y-3 text-xs text-[var(--color-ink-soft)] max-w-3xl">
          <h4 className="text-sm text-[var(--color-ink)] font-semibold mb-2">Notes per dataset</h4>
          {datasets.flatMap((ds) =>
            ds.caveats.map((c, i) => (
              <div key={`${ds.key}-${i}`}>
                <span className="text-[var(--color-ink)] font-medium">{ds.name}:</span> {c}
              </div>
            )),
          )}
        </div>
      </div>
    </section>
  )
}
