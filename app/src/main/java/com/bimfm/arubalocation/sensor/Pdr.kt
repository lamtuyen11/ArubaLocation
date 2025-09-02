package com.bimfm.arubalocation.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * PDR = Pedestrian Dead Reckoning (indoor displacement)
 *
 * - Emits Δx, Δy (meters) whenever a step is detected.
 * - Heading comes from the phone's rotation vector (azimuth).
 * - Default step length ~0.7 m (adjust per user).
 */
class Pdr(private val context: Context) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val stepDetector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotVector   = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var headingRad = 0.0
    private var stepLenM   = 0.7  // average step length (calibrate if needed)

    private val listeners = mutableListOf<(dx: Double, dy: Double, tNanos: Long) -> Unit>()

    /** Start listening to sensors. Call in onCreate/onStart. */
    fun start() {
        sm.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(this, rotVector,   SensorManager.SENSOR_DELAY_GAME)
    }

    /** Stop listening to sensors. Call in onDestroy/onStop. */
    fun stop() {
        sm.unregisterListener(this)
    }

    /** Subscribe to displacement updates. */
    fun onDisplacement(listener: (dx: Double, dy: Double, tNanos: Long) -> Unit) {
        listeners += listener
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val R = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(R, e.values)
                val ori = FloatArray(3)
                SensorManager.getOrientation(R, ori)
                headingRad = ori[0].toDouble()   // azimuth (radians, relative to magnetic north)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                // Each step: project step length onto heading
                val dx = stepLenM * cos(headingRad)
                val dy = stepLenM * sin(headingRad)
                listeners.forEach { it(dx, dy, e.timestamp) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }
}
