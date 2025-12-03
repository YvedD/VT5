package com.yvesds.vt5.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * CompassView - Een grafische kompasweergave met bewegende naald en knoppen rondom
 * 
 * Gebruikt de rotatie vector sensor of accelerometer+magnetometer
 * om de huidige richting te bepalen en weer te geven.
 * 
 * De gebruiker kan op een van de 16 windrichting-knoppen tikken om deze te selecteren.
 * Een tweede tik deselecteert de knop. Geselecteerde knop wordt blauw.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    companion object {
        // Button dimensions relative to radius
        private const val BUTTON_WIDTH_FACTOR = 0.28f  // Width of each button
        private const val BUTTON_HEIGHT_FACTOR = 0.14f // Height of each button
        private const val BUTTON_DISTANCE_FACTOR = 1.15f // Distance from center to button center
        
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
    
    // Track if we have received both sensor readings (for accel+mag fallback)
    private var hasAccelerometerReading = false
    private var hasMagnetometerReading = false

    // Geselecteerde richting (-1 = geen selectie)
    private var selectedDirectionIndex = -1

    // Callback voor richting selectie
    var onDirectionSelectedListener: ((index: Int, label: String, code: String) -> Unit)? = null
    
    // Callback voor deselectie (wanneer gebruiker op dezelfde richting tikt om deze ongedaan te maken)
    var onDirectionDeselectedListener: (() -> Unit)? = null

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

    // Button paints
    private val paintButtonNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#424242") // Dark gray background
        style = Paint.Style.FILL
    }
    
    private val paintButtonSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3") // Blue 500 - app theme color
        style = Paint.Style.FILL
    }
    
    private val paintButtonBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#757575") // Gray border
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private val paintButtonBorderSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2") // Darker blue border when selected
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val paintButtonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f
        isFakeBoldText = true
    }

    private val paintAzimuthText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }

    private val needlePath = Path()
    
    // Button rectangles for hit testing
    private val buttonRects = Array(16) { RectF() }

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
        // Reset sensor reading flags when stopping
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
        // Only update orientation if we have readings from both sensors
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
        // Make radius smaller to leave room for buttons around the edge
        radius = min(w, h) / 2f * 0.55f

        // Update text sizes based on radius
        paintButtonText.textSize = radius * 0.18f
        paintAzimuthText.textSize = radius * 0.2f
        
        // Calculate button rectangles for hit testing
        calculateButtonRects()
    }
    
    private fun calculateButtonRects() {
        val buttonWidth = radius * BUTTON_WIDTH_FACTOR * 2.2f
        val buttonHeight = radius * BUTTON_HEIGHT_FACTOR * 2.2f
        val buttonDistance = radius * BUTTON_DISTANCE_FACTOR * 1.35f
        
        for (i in DIRECTION_ANGLES.indices) {
            val angle = DIRECTION_ANGLES[i]
            val angleRad = Math.toRadians((angle - 90).toDouble())
            
            val buttonCenterX = centerX + (buttonDistance * cos(angleRad)).toFloat()
            val buttonCenterY = centerY + (buttonDistance * sin(angleRad)).toFloat()
            
            buttonRects[i].set(
                buttonCenterX - buttonWidth / 2,
                buttonCenterY - buttonHeight / 2,
                buttonCenterX + buttonWidth / 2,
                buttonCenterY + buttonHeight / 2
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Teken achtergrond cirkel (smaller now)
        canvas.drawCircle(centerX, centerY, radius, paintCircleFill)
        canvas.drawCircle(centerX, centerY, radius, paintCircle)

        // Teken kompasnaald (roterende met sensor data)
        drawNeedle(canvas)

        // Teken huidige graden in het midden
        val azimuthText = "${currentAzimuth.toInt()}°"
        canvas.drawText(azimuthText, centerX, centerY + radius * 0.35f, paintAzimuthText)
        
        // Teken de 16 windrichting-knoppen rondom de cirkel
        drawDirectionButtons(canvas)
    }
    
    private fun drawDirectionButtons(canvas: Canvas) {
        val cornerRadius = radius * 0.06f
        
        for (i in DIRECTION_ANGLES.indices) {
            val rect = buttonRects[i]
            val isSelected = i == selectedDirectionIndex
            
            // Draw button background
            val bgPaint = if (isSelected) paintButtonSelected else paintButtonNormal
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            
            // Draw button border
            val borderPaint = if (isSelected) paintButtonBorderSelected else paintButtonBorder
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
            
            // Draw button text
            val textBounds = android.graphics.Rect()
            paintButtonText.getTextBounds(DIRECTION_LABELS[i], 0, DIRECTION_LABELS[i].length, textBounds)
            val textY = rect.centerY() + textBounds.height() / 2f
            canvas.drawText(DIRECTION_LABELS[i], rect.centerX(), textY, paintButtonText)
        }
    }

    private fun drawNeedle(canvas: Canvas) {
        val needleLength = radius * 0.7f
        val needleWidth = radius * 0.12f

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
        canvas.drawCircle(centerX, centerY, needleWidth * 0.6f, paintCircle)

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val touchX = event.x
            val touchY = event.y

            // Check if touch is on any button
            for (i in buttonRects.indices) {
                if (buttonRects[i].contains(touchX, touchY)) {
                    // Toggle: if tapping the same direction, deselect it
                    if (i == selectedDirectionIndex) {
                        selectedDirectionIndex = -1
                        onDirectionDeselectedListener?.invoke()
                    } else {
                        selectedDirectionIndex = i
                        val label = DIRECTION_LABELS[i]
                        val code = DIRECTION_CODES[i]
                        onDirectionSelectedListener?.invoke(i, label, code)
                    }
                    
                    invalidate()
                    performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Zet de geselecteerde richting programmatisch.
     * Als de code niet gevonden wordt, wordt geen selectie gemaakt.
     * 
     * @param code De richtingscode (bijv. "N", "NNE", etc.) of null om selectie te wissen
     */
    fun setSelectedDirection(code: String?) {
        if (code == null) {
            selectedDirectionIndex = -1
        } else {
            val index = DIRECTION_CODES.indexOf(code.uppercase())
            // Only set if valid index found, otherwise leave as no selection
            selectedDirectionIndex = if (index >= 0) index else -1
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
