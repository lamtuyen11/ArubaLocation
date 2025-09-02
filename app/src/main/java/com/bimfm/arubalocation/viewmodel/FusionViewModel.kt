package com.bimfm.arubalocation.viewmodel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** A position fix in your local floor frame (meters). */
data class Fix(val x: Double, val y: Double, val sigma: Double)

/**
 * FusionViewModel
 * - Call onStep(dx, dy, tNanos) from your PDR (step detector) whenever a step is detected.
 * - Call onRttFix(x, y, sigma, tNanos) when your RTT trilateration outputs a fix.
 * - Observe fusedFix for the fused (x,y,σ) to render in Compose.
 */
class FusionViewModel : ViewModel() {

    // ===== Public fused state =====
    private val _fusedFix = MutableStateFlow<Fix?>(null)
    val fusedFix = _fusedFix.asStateFlow()

    // ===== Internals =====
    private var initialized = false
    private var lastNanos: Long = 0L

    // Kalman filter (constant-velocity in 2D)
    private var kf = Kf2D(dtSec = 0.05) // default ~20 Hz integration

    /** Optional tuning — process noise (bigger => smoother but laggier). */
    fun setNoise(positionQ: Double = 0.05, velocityQ: Double = 0.5) {
        kf.setProcessNoise(positionQ, velocityQ)
    }

    /** Reset the filter (e.g., when changing floors). */
    fun reset() {
        initialized = false
        lastNanos = 0L
        kf = Kf2D(dtSec = 0.05)
        post(kf.state())
    }

    /** First-time initialization at a known (x,y). */
    private fun initAt(x: Double, y: Double, tNanos: Long) {
        initialized = true
        lastNanos = tNanos
        kf.initAt(x, y)
        post(kf.state())
    }

    /** PDR step update: provide Δx, Δy (meters) in your local frame. */
    fun onStep(dx: Double, dy: Double, tNanos: Long) {
        if (!initialized) return
        // Optionally update dt from wall-clock; here we integrate displacement directly.
        kf.predictWithPdr(dx, dy)
        lastNanos = tNanos
        post(kf.state())
    }

    /**
     * RTT absolute fix: (x, y) meters + sigma (meters).
     * Pass sigma from your RTT pipeline (e.g., mean/std from RangingResults, geometry factor).
     */
    fun onRttFix(x: Double, y: Double, sigma: Double, tNanos: Long = System.nanoTime()) {
        if (!initialized) {
            initAt(x, y, tNanos)
            return
        }
        // Before correcting, do a small time-based prediction if you want (already handled by onStep).
        kf.updateWithRtt(Fix(x, y, sigma))
        lastNanos = tNanos
        post(kf.state())
    }

    private fun post(fix: Fix) = viewModelScope.launch {
        _fusedFix.value = fix
    }
}

/* ======================= Kalman Filter ===========================
   State x = [x y vx vy]^T
   Predict: integrate PDR Δx,Δy into (x,y) and propagate covariance.
   Update:  correct with RTT z = (x,y), with R = diag(σ², σ²)
   ================================================================= */
private class Kf2D(dtSec: Double) {
    // State and covariance
    private val x = DoubleArray(4) // [x y vx vy]
    private val P = eye(4, 100.0)  // large initial uncertainty

    // Constant-velocity model matrices
    private val F = arrayOf(
        doubleArrayOf(1.0, 0.0, dtSec, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, dtSec),
        doubleArrayOf(0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0),
    )

    private var Q = arrayOf( // process noise (tunable)
        doubleArrayOf(0.05, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.05, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.5, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.5),
    )

    // Measurement z = [x y]
    private val H = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, 0.0),
    )

    fun setProcessNoise(positionQ: Double, velocityQ: Double) {
        Q = arrayOf(
            doubleArrayOf(positionQ, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, positionQ, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, velocityQ, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, velocityQ),
        )
    }

    fun initAt(x0: Double, y0: Double) {
        x[0] = x0; x[1] = y0; x[2] = 0.0; x[3] = 0.0
        // Keep P as large; it will shrink after first updates
    }

    /** Integrate PDR displacement directly into position, then propagate covariance. */
    fun predictWithPdr(dx: Double, dy: Double) {
        // Apply displacement (position-only integration)
        x[0] += dx
        x[1] += dy
        // Covariance propagation: P = F P F^T + Q
        val FP = mul(F, P)
        val FPFt = mul(FP, tr(F))
        addInPlace(FPFt, Q)
        copyInto(FPFt, P)
    }

    /** Correct with RTT fix; R = diag(σ², σ²). */
    fun updateWithRtt(z: Fix) {
        val R = arrayOf(
            doubleArrayOf(z.sigma * z.sigma, 0.0),
            doubleArrayOf(0.0, z.sigma * z.sigma),
        )
        // y = z - Hx
        val zvec = doubleArrayOf(z.x, z.y)
        val Hx = mul(H, x)
        val y = doubleArrayOf(zvec[0] - Hx[0], zvec[1] - Hx[1])

        // S = H P H^T + R
        val HP = mul(H, P)
        val HPHt = mul(HP, tr(H))
        val S = add(HPHt, R)
        val SInv = inv2(S) // 2x2 inverse

        // K = P H^T S^-1
        val PHt = mul(P, tr(H))
        val K = mul(PHt, SInv) // 4x2

        // x = x + K y
        val Ky = mul(K, y)
        for (i in 0..3) x[i] += Ky[i]

        // P = (I - K H) P
        val KH = mul(K, H)          // 4x4
        val I = eye(4, 1.0)
        val IKH = sub(I, KH)
        val newP = mul(IKH, P)
        copyInto(newP, P)
    }

    fun state(): Fix = Fix(x[0], x[1], sqrt(maxOf(P[0][0], P[1][1])))
}

/* ======================= Tiny matrix helpers ======================= */

private fun eye(n: Int, diag: Double) = Array(n) { i ->
    DoubleArray(n) { j -> if (i == j) diag else 0.0 }
}

private fun tr(A: Array<DoubleArray>): Array<DoubleArray> {
    val m = A.size; val n = A[0].size
    val T = Array(n) { DoubleArray(m) }
    for (i in 0 until m) for (j in 0 until n) T[j][i] = A[i][j]
    return T
}

private fun mul(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
    val m = A.size; val n = A[0].size; val p = B[0].size
    require(B.size == n) { "mul dim mismatch: ${A.size}x${A[0].size} * ${B.size}x${B[0].size}" }
    val C = Array(m) { DoubleArray(p) }
    for (i in 0 until m) {
        for (k in 0 until n) {
            val aik = A[i][k]
            for (j in 0 until p) C[i][j] += aik * B[k][j]
        }
    }
    return C
}

private fun mul(A: Array<DoubleArray>, x: DoubleArray): DoubleArray {
    val m = A.size; val n = A[0].size
    require(x.size == n) { "mul dim mismatch: ${A.size}x${A[0].size} * ${x.size}" }
    val y = DoubleArray(m)
    for (i in 0 until m) {
        var s = 0.0
        for (k in 0 until n) s += A[i][k] * x[k]
        y[i] = s
    }
    return y
}

private fun add(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
    val m = A.size; val n = A[0].size
    require(B.size == m && B[0].size == n)
    val C = Array(m) { DoubleArray(n) }
    for (i in 0 until m) for (j in 0 until n) C[i][j] = A[i][j] + B[i][j]
    return C
}

private fun addInPlace(A: Array<DoubleArray>, B: Array<DoubleArray>) {
    val m = A.size; val n = A[0].size
    require(B.size == m && B[0].size == n)
    for (i in 0 until m) for (j in 0 until n) A[i][j] += B[i][j]
}

private fun sub(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
    val m = A.size; val n = A[0].size
    require(B.size == m && B[0].size == n)
    val C = Array(m) { DoubleArray(n) }
    for (i in 0 until m) for (j in 0 until n) C[i][j] = A[i][j] - B[i][j]
    return C
}

/** Inverse for a symmetric 2x2 matrix [[a,b],[b,c]] or general 2x2 [[a,b],[c,d]]. */
private fun inv2(S: Array<DoubleArray>): Array<DoubleArray> {
    require(S.size == 2 && S[0].size == 2)
    val a = S[0][0]; val b = S[0][1]; val c = S[1][0]; val d = S[1][1]
    val det = a * d - b * c
    require(kotlin.math.abs(det) > 1e-12) { "Singular 2x2" }
    val invDet = 1.0 / det
    return arrayOf(
        doubleArrayOf( d * invDet, -b * invDet),
        doubleArrayOf(-c * invDet,  a * invDet),
    )
}

private fun copyInto(src: Array<DoubleArray>, dst: Array<DoubleArray>) {
    require(src.size == dst.size && src[0].size == dst[0].size)
    for (i in src.indices) src[i].copyInto(dst[i])
}
