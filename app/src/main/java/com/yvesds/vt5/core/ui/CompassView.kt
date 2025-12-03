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
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * CompassView - Een grafische kompasweergave met bewegende naald
 * 
 * Gebruikt de rotatie vector sensor of accelerometer+magnetometer
 * om de huidige richting te bepalen en weer te geven.
 * 
 * De gebruiker kan op een van de 16 windrichtingen tikken om deze te selecteren.
 * De geselecteerde richting wordt gemarkeerd.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    companion object {
        // 16-punts windroos in graden (beginnend bij Noord = 0°)
        private val DIRECTION_ANGLES = floatArrayOf(
            0f,    // N
            22.5f, // NNE (NNO)
            45f,   // NE (NO)
            67.5f, // ENE (ONO)
            90f,   // E (O)
            112.5f,// ESE (OZO)
            135f,  // SE (ZO)
            157.5f,// SSE (ZZO)
            180f,  // S (Z)
            202.5f,// SSW (ZZW)
            225f,  // SW (ZW)
            247.5f,// WSW (WZW)
            270f,  // W
            292.5f,// WNW
            315f,  // NW
            337.5f // NNW
        )

        // Labels voor de 16 windrichtingen (NL)
        val DIRECTION_LABELS = arrayOf(
            "N", "NNO", "NO", "ONO", "O", "OZO", "ZO", "ZZO",
            "Z", "ZZW", "ZW", "WZW", "W", "WNW", "NW", "NNW"
        )

        // Waarden voor sightingdirection veld (codes.json formaat - Engels)
        val DIRECTION_CODES = arrayOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        )
    }

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

    // Geselecteerde richting (-1 = geen selectie)
    private var selectedDirectionIndex = -1

    // Callback voor richting selectie
    var onDirectionSelectedListener: ((index: Int, label: String, code: String) -> Unit)? = null

    // Paint objecten
    private val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3") // Blue 500
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val paintCircleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2196F3") // Transparante blauwe achtergrond
        style = Paint.Style.FILL
    }

    private val paintNeedle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }

    private val paintCardinalText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 36f
        isFakeBoldText = true
    }

    private val paintDirectionMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50") // Green 500
        style = Paint.Style.FILL
    }

    private val paintSelectedMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800") // Orange 500
        style = Paint.Style.FILL
    }

    private val paintTouchArea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF") // Transparant wit
        style = Paint.Style.FILL
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
                updateOrientationFromAccelMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                updateOrientationFromAccelMag()
            }
        }
    }

    private fun updateOrientationFromAccelMag() {
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
        radius = min(w, h) / 2f * 0.85f

        // Update text grootte gebaseerd op radius
        paintText.textSize = radius * 0.12f
        paintCardinalText.textSize = radius * 0.16f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Teken achtergrond cirkel
        canvas.drawCircle(centerX, centerY, radius, paintCircleFill)
        canvas.drawCircle(centerX, centerY, radius, paintCircle)
        canvas.drawCircle(centerX, centerY, radius * 0.6f, paintCircle)

        // Teken de 16 windrichtingen
        for (i in DIRECTION_ANGLES.indices) {
            val angle = DIRECTION_ANGLES[i]
            val angleRad = Math.toRadians((angle - 90).toDouble()) // -90 om N bovenaan te zetten

            // Teken richting markers (punten op buitenring)
            val markerRadius = if (i % 2 == 0) radius * 0.08f else radius * 0.05f
            val markerDistance = radius * 0.92f
            val markerX = centerX + (markerDistance * cos(angleRad)).toFloat()
            val markerY = centerY + (markerDistance * sin(angleRad)).toFloat()

            val paint = if (i == selectedDirectionIndex) paintSelectedMarker else paintDirectionMarker
            canvas.drawCircle(markerX, markerY, markerRadius, paint)

            // Teken labels
            val labelDistance = radius * 0.75f
            val labelX = centerX + (labelDistance * cos(angleRad)).toFloat()
            val labelY = centerY + (labelDistance * sin(angleRad)).toFloat()

            val textPaint = if (i % 4 == 0) paintCardinalText else paintText
            
            // Correctie voor text baseline
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(DIRECTION_LABELS[i], 0, DIRECTION_LABELS[i].length, textBounds)
            val textHeight = textBounds.height()

            canvas.drawText(DIRECTION_LABELS[i], labelX, labelY + textHeight / 2f, textPaint)
        }

        // Teken kompasnaald (roterende met sensor data)
        drawNeedle(canvas)

        // Teken huidige graden in het midden
        val azimuthText = "${currentAzimuth.toInt()}°"
        paintText.textSize = radius * 0.14f
        canvas.drawText(azimuthText, centerX, centerY + radius * 0.05f, paintText)
    }

    private fun drawNeedle(canvas: Canvas) {
        val needleLength = radius * 0.5f
        val needleWidth = radius * 0.08f

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
        canvas.drawCircle(centerX, centerY, needleWidth * 0.8f, paintCircle)

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val touchX = event.x
            val touchY = event.y

            // Bereken hoek van touch relatief tot center
            val dx = touchX - centerX
            val dy = touchY - centerY

            // Check of touch binnen kompas cirkel is
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance > radius * 0.4f && distance < radius * 1.1f) {
                // Bereken hoek (0 = rechts, dus we moeten corrigeren)
                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                angle += 90f // Correctie voor N bovenaan
                if (angle < 0) angle += 360f
                if (angle >= 360f) angle -= 360f

                // Vind dichtsbijzijnde richting
                val directionIndex = findClosestDirection(angle)
                selectedDirectionIndex = directionIndex
                
                val label = DIRECTION_LABELS[directionIndex]
                val code = DIRECTION_CODES[directionIndex]
                onDirectionSelectedListener?.invoke(directionIndex, label, code)
                
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findClosestDirection(angle: Float): Int {
        var minDiff = Float.MAX_VALUE
        var closestIndex = 0

        for (i in DIRECTION_ANGLES.indices) {
            var diff = kotlin.math.abs(angle - DIRECTION_ANGLES[i])
            // Handle wrap-around (bijv. 359° is dicht bij 0°)
            if (diff > 180f) diff = 360f - diff

            if (diff < minDiff) {
                minDiff = diff
                closestIndex = i
            }
        }
        return closestIndex
    }

    /**
     * Zet de geselecteerde richting programmatisch
     */
    fun setSelectedDirection(code: String?) {
        if (code == null) {
            selectedDirectionIndex = -1
        } else {
            selectedDirectionIndex = DIRECTION_CODES.indexOf(code.uppercase())
        }
        invalidate()
    }

    /**
     * Geef de huidige kompasrichting (azimuth) terug
     */
    fun getCurrentAzimuth(): Float = currentAzimuth

    /**
     * Geef de geselecteerde richting code terug, of null als niets geselecteerd
     */
    fun getSelectedDirectionCode(): String? {
        return if (selectedDirectionIndex >= 0 && selectedDirectionIndex < DIRECTION_CODES.size) {
            DIRECTION_CODES[selectedDirectionIndex]
        } else {
            null
        }
    }

    /**
     * Geef de geselecteerde richting label terug, of null als niets geselecteerd
     */
    fun getSelectedDirectionLabel(): String? {
        return if (selectedDirectionIndex >= 0 && selectedDirectionIndex < DIRECTION_LABELS.size) {
            DIRECTION_LABELS[selectedDirectionIndex]
        } else {
            null
        }
    }
}
