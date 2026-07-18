use ndarray::Array2;
use rustfft::{num_complex::Complex, FftPlanner};
use std::f32::consts::PI;

const SAMPLE_RATE: f32 = 16000.0;
const N_FFT: usize = 512;
const HOP_LENGTH: usize = 160;
const WIN_LENGTH: usize = 400;
const N_MEL: usize = 128;
const PREEMPH_COEF: f32 = 0.97;
const MEL_LOW_FREQ: f32 = 0.0;
const MEL_HIGH_FREQ: f32 = 8000.0;

pub fn extract_mel_features(samples: &[f32]) -> Array2<f32> {
    let preemphasized = apply_preemphasis(samples);
    let stft = compute_stft(&preemphasized);
    let power_spec = stft.mapv(|c| c.re * c.re + c.im * c.im);

    let filters = create_mel_filterbank();
    let mel_spec = power_spec.dot(&filters.t());
    let log_mel = mel_spec.mapv(|x| (x + 1e-10).ln());

    per_feature_normalize(log_mel)
}

fn apply_preemphasis(samples: &[f32]) -> Vec<f32> {
    if samples.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(samples.len());
    out.push(samples[0]);
    for i in 1..samples.len() {
        out.push(samples[i] - PREEMPH_COEF * samples[i - 1]);
    }
    out
}

fn povey_window(len: usize) -> Vec<f32> {
    (0..len)
        .map(|n| {
            let factor = 2.0 * PI * n as f32 / (len - 1) as f32;
            (0.5 - 0.5 * factor.cos()).powf(0.85)
        })
        .collect()
}

fn compute_stft(samples: &[f32]) -> Array2<Complex<f32>> {
    let num_frames = (samples.len().saturating_sub(WIN_LENGTH)) / HOP_LENGTH + 1;
    let mut stft = Array2::zeros((num_frames, N_FFT / 2 + 1));
    let window = povey_window(WIN_LENGTH);
    let fft = FftPlanner::<f32>::new().plan_fft_forward(N_FFT);

    for frame_idx in 0..num_frames {
        let start = frame_idx * HOP_LENGTH;
        let end = start + WIN_LENGTH;
        if end > samples.len() {
            break;
        }
        let mut buffer: Vec<Complex<f32>> = vec![Complex::new(0.0, 0.0); N_FFT];
        for i in 0..WIN_LENGTH {
            buffer[i] = Complex::new(samples[start + i] * window[i], 0.0);
        }
        fft.process(&mut buffer);
        for i in 0..=N_FFT / 2 {
            stft[[frame_idx, i]] = buffer[i];
        }
    }
    stft
}

fn hz_to_mel(hz: f32) -> f32 {
    2595.0 * (1.0 + hz / 700.0).log10()
}

fn mel_to_hz(mel: f32) -> f32 {
    let hz = 700.0 * (10.0_f32.powf(mel / 2595.0) - 1.0);
    if hz.is_finite() { hz } else { MEL_HIGH_FREQ }
}

fn create_mel_filterbank() -> Array2<f32> {
    let freq_bins = N_FFT / 2 + 1;
    let mel_min = hz_to_mel(MEL_LOW_FREQ);
    let mel_max = hz_to_mel(MEL_HIGH_FREQ);
    let mel_points: Vec<f32> = (0..=N_MEL + 1)
        .map(|i| {
            let mel = mel_min + (mel_max - mel_min) * i as f32 / (N_MEL + 1) as f32;
            mel_to_hz(mel)
        })
        .collect();
    let freq_bin_width = SAMPLE_RATE / N_FFT as f32;
    let mut filterbank = Array2::zeros((N_MEL, freq_bins));

    for m in 0..N_MEL {
        let left = mel_points[m];
        let center = mel_points[m + 1];
        let right = mel_points[m + 2];
        for k in 0..freq_bins {
            let freq = k as f32 * freq_bin_width;
            if freq >= left && freq <= center && center != left {
                filterbank[[m, k]] = (freq - left) / (center - left);
            } else if freq > center && freq <= right && right != center {
                filterbank[[m, k]] = (right - freq) / (right - center);
            }
        }
    }
    filterbank
}

fn per_feature_normalize(mut features: Array2<f32>) -> Array2<f32> {
    let num_frames = features.nrows();
    if num_frames == 0 {
        return features;
    }
    for col_idx in 0..features.ncols() {
        let mut col = features.column_mut(col_idx);
        let mean: f32 = col.iter().sum::<f32>() / num_frames as f32;
        let var: f32 = col.iter().map(|&x| (x - mean).powi(2)).sum::<f32>() / num_frames as f32;
        let std = var.max(0.0).sqrt().max(1e-10);
        for val in col.iter_mut() {
            *val = (*val - mean) / std;
        }
    }
    features
}
