package com.yvesds.vt5.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * CompassNeedleView - Een eenvoudige kompasnaald weergave
 * 
 * Toont alleen de kompasnaald die met de sensor meedraait.
 * De windrichting-knoppen worden apart als echte Android Buttons weergegeven.
 */
class CompassNeedleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    // Sensor manager
    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magnetometerSensor: Sensor? = null

    // Sensor data
    private var currentAzimuth = 0f
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    // Track if we have received both sensor readings (for accel+mag fallback)
    private var hasAccelerometerReading = false
    private var hasMagnetometerReading = false

    // Paint objecten
    private val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3") // Blue 500
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintCircleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2196F3") // Transparante blauwe achtergrond
        style = Paint.Style.FILL
    }

    private val paintNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintAzimuthText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }

    private val needlePath = Path()

    // Afmetingen
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    init {
        initSensors()
    }

    private fun initSensors() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        
        // Probeer eerst rotation vector sensor (nauwkeuriger)
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        // Fallback naar accelerometer + magnetometer
        if (rotationVectorSensor == null) {
            accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }
    }

    fun startSensors() {
        sensorManager?.let { sm ->
            rotationVectorSensor?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            } ?: run {
                accelerometerSensor?.let { acc ->
                    sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
                }
                magnetometerSensor?.let { mag ->
                    sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }
    }

    fun stopSensors() {
        sensorManager?.unregisterListener(this)
        hasAccelerometerReading = false
        hasMagnetometerReading = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                currentAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                if (currentAzimuth < 0) currentAzimuth += 360f
                invalidate()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                hasAccelerometerReading = true
                updateOrientationFromAccelMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                hasMagnetometerReading = true
                updateOrientationFromAccelMag()
            }
        }
    }

    private fun updateOrientationFromAccelMag() {
        if (!hasAccelerometerReading || !hasMagnetometerReading) {
            return
        }
        
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            currentAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (currentAzimuth < 0) currentAzimuth += 360f
            invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Niet gebruikt
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f * 0.9f
        paintAzimuthText.textSize = radius * 0.25f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Teken achtergrond cirkel
        canvas.drawCircle(centerX, centerY, radius, paintCircleFill)
        canvas.drawCircle(centerX, centerY, radius, paintCircle)

        // Teken kompasnaald
        drawNeedle(canvas)

        // Teken huidige graden in het midden
        val azimuthText = "${currentAzimuth.toInt()}°"
        canvas.drawText(azimuthText, centerX, centerY + radius * 0.4f, paintAzimuthText)
    }

    private fun drawNeedle(canvas: Canvas) {
        val needleLength = radius * 0.7f
        val needleWidth = radius * 0.15f

        canvas.save()
        canvas.rotate(-currentAzimuth, centerX, centerY)

        // Noord (rood)
        needlePath.reset()
        needlePath.moveTo(centerX, centerY - needleLength)
        needlePath.lineTo(centerX - needleWidth / 2, centerY)
        needlePath.lineTo(centerX + needleWidth / 2, centerY)
        needlePath.close()
        paintNeedle.color = Color.parseColor("#F44336") // Red 500
        canvas.drawPath(needlePath, paintNeedle)

        // Zuid (wit)
        needlePath.reset()
        needlePath.moveTo(centerX, centerY + needleLength)
        needlePath.lineTo(centerX - needleWidth / 2, centerY)
        needlePath.lineTo(centerX + needleWidth / 2, centerY)
        needlePath.close()
        paintNeedle.color = Color.WHITE
        canvas.drawPath(needlePath, paintNeedle)

        // Middenpunt
        canvas.drawCircle(centerX, centerY, needleWidth * 0.5f, paintCircle)

        canvas.restore()
    }

    /**
     * Geef de huidige kompasrichting (azimuth) terug
     */
    fun getCurrentAzimuth(): Float = currentAzimuth
}
