export type ValidationKind = "ground_truth_error" | "cross_sensor_agreement"

export interface SubgroupStats {
  n_trials: number
  cadence_mae_pct?: number
  cadence_median_pct?: number
  cadence_p95_pct?: number
}

export interface GroundTruthMetrics {
  cadence_mae_pct?: number
  cadence_median_pct?: number
  cadence_p95_pct?: number
  stride_length_mae_pct?: number
  stride_length_median_pct?: number
  cadence_icc_3_1?: number
  stride_length_icc_3_1?: number
  /**
   * Present when a dataset's headline figure covers a subset of its trials
   * rather than all of them. The top level cadence fields then describe
   * headline_subgroup, and by_subgroup carries every subset plus "pooled".
   */
  by_subgroup?: Record<string, SubgroupStats>
  headline_subgroup?: string
  headline_n_trials?: number
}

export interface CrossSensorMetrics {
  mean_cadence_trunk_spm?: number
  mean_cadence_shank_spm?: number
  mean_difference_spm?: number
  sd_difference_spm?: number
  loa_lower_spm?: number
  loa_upper_spm?: number
  mean_absolute_difference_spm?: number
  pearson_r?: number
}

export type DatasetMetrics = GroundTruthMetrics & CrossSensorMetrics

export interface DatasetSummary {
  key: "santos" | "nonan" | "marea" | "luo"
  name: string
  citation: string
  doi: string
  mount: string
  n_participants: number
  n_trials_processed: number
  sample_rate_hz: number
  validation_kind: ValidationKind
  status: "complete" | "pending"
  caveats: string[]
  metrics: DatasetMetrics
}

export interface ValidationSummary {
  generated_at_utc: string
  datasets: DatasetSummary[]
}
