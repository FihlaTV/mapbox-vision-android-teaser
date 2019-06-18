package com.mapbox.vision.examples

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.vision.VisionReplayManager
import com.mapbox.vision.examples.utils.getBitmap
import com.mapbox.vision.mobile.core.models.frame.ImageSize
import com.mapbox.vision.mobile.core.models.position.GeoCoordinate
import com.mapbox.vision.mobile.core.models.position.GeoLocation
import kotlin.math.max
import kotlin.math.min

class TurnSignView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // sign image will start to appear at this distance, starting with transparent and appearing gradually
        private const val DRAW_TURN_MIN_DISTANCE_METERS = 250
        private const val DISTANCE_FOR_ALPHA_APPEAR_METERS = 50

        // sign size and position above the ground
        private const val SIGN_SIZE_METERS = 8
        private const val SIGN_ABOVE_GROUND_METERS = 2
    }

    private var bitmap: Bitmap? = null
    private var bitmapLeft: Bitmap? = null
    private var bitmapRight: Bitmap? = null
    private var bitmapUturn: Bitmap? = null
    private val bitmapRect = Rect(0, 0, 0, 0)
    private val bitmapPaint = Paint()

    private var stepManeuvers: List<StepManeuver>? = null
    private var currentManeuver: Int = 0
    private var distanceIsIncreasingCounter: Int = 0
    private var currentVehicleLocation: GeoLocation? = null

    private val transparent = resources.getColor(android.R.color.transparent, null)

    private var scaleFactor = 1f
    private var scaledSize = ImageSize(1, 1)

    private fun Float.scaleX(): Float = this * scaleFactor - (scaledSize.imageWidth - width) / 2
    private fun Float.scaleY(): Float = this * scaleFactor - (scaledSize.imageHeight - height) / 2

    init {
        bitmapRight = context.assets.getBitmap("turn_right.png")
        bitmapLeft = context.assets.getBitmap("turn_left.png")
        bitmapUturn = context.assets.getBitmap("turn_uturn.png")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val frameSize = ImageSize(1280, 720)

        scaleFactor = max(
            width.toFloat() / frameSize.imageWidth,
            height.toFloat() / frameSize.imageHeight
        )
        scaledSize = ImageSize(
            imageWidth = (frameSize.imageWidth * scaleFactor).toInt(),
            imageHeight = (frameSize.imageHeight * scaleFactor).toInt()
        )
    }

    private fun drawTurnSign(
        signLatLng: LatLng,
        turnDescription: String?,
        distanceTurnFromNewLocation: Int
    ) {
        if (distanceTurnFromNewLocation > DRAW_TURN_MIN_DISTANCE_METERS) {
            return
        }

        val worldCoordinate = VisionReplayManager.geoToWorld(
            GeoCoordinate(
                latitude = signLatLng.latitude,
                longitude = signLatLng.longitude
            )
        ) ?: return

        val worldLeftTop = worldCoordinate.copy(
            y = worldCoordinate.y + SIGN_SIZE_METERS / 2,
            z = worldCoordinate.z + SIGN_ABOVE_GROUND_METERS + SIGN_SIZE_METERS
        )
        val worldRightBottom = worldCoordinate.copy(
            y = worldCoordinate.y - SIGN_SIZE_METERS / 2,
            z = worldCoordinate.z + SIGN_ABOVE_GROUND_METERS
        )

        if (worldLeftTop.x < 0 && worldRightBottom.x < 0) {
            currentManeuver++
            return
        }

        VisionReplayManager.worldToPixel(worldLeftTop)?.run {
            bitmapRect.left = x.toFloat().scaleX().toInt()
            bitmapRect.top = y.toFloat().scaleY().toInt()
        }
        VisionReplayManager.worldToPixel(worldRightBottom)?.run {
            bitmapRect.right = x.toFloat().scaleX().toInt()
            bitmapRect.bottom = y.toFloat().scaleY().toInt()
        }

        bitmap = when {
            turnDescription?.contains("uturn") ?: false -> bitmapUturn
            turnDescription?.contains("right") ?: false -> bitmapRight
            else -> bitmapLeft
        }

        // will draw signs starting with `DRAW_TURN_MIN_DISTANCE_METERS` with 0 alpha and then increase gradually to 1 alpha
        val minDistance = min(DRAW_TURN_MIN_DISTANCE_METERS - distanceTurnFromNewLocation, DISTANCE_FOR_ALPHA_APPEAR_METERS)
        bitmapPaint.alpha = ((minDistance / DISTANCE_FOR_ALPHA_APPEAR_METERS.toFloat()) * 255).toInt()

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        setBackgroundColor(transparent)

        bitmap?.apply {
            canvas.drawBitmap(
                this,
                null,
                bitmapRect,
                bitmapPaint
            )
        }

        super.onDraw(canvas)
    }

    fun setManeuvers(maneuvers: List<StepManeuver>) {
        currentManeuver = 0
        stepManeuvers = maneuvers
    }

    fun updateVehicleLocation(newVehicleLocation: GeoLocation) {
        if (stepManeuvers == null) {
            return
        }

        if (currentVehicleLocation == null) {
            currentVehicleLocation = newVehicleLocation
            return
        }

        val maneuver = stepManeuvers!![currentManeuver]
        val maneuverLatLng = maneuver.location().run {
            LatLng(latitude(), longitude())
        }

        val distanceTurnFromPreviousLocation = maneuverLatLng.distanceTo(
            LatLng(currentVehicleLocation!!.geoCoordinate.latitude, currentVehicleLocation!!.geoCoordinate.longitude)
        )
        val distanceTurnFromNewLocation = maneuverLatLng.distanceTo(
            LatLng(newVehicleLocation.geoCoordinate.latitude, newVehicleLocation.geoCoordinate.longitude)
        )

        drawTurnSign(
            signLatLng = maneuverLatLng,
            turnDescription = maneuver.modifier(),
            distanceTurnFromNewLocation = distanceTurnFromNewLocation.toInt()
        )

        updateNextTurn(
            distanceTurnFromPreviousLocation = distanceTurnFromPreviousLocation,
            distanceTurnFromNewLocation = distanceTurnFromNewLocation,
            newVehicleLocation = newVehicleLocation
        )
    }

    private fun updateNextTurn(
        distanceTurnFromPreviousLocation: Double,
        distanceTurnFromNewLocation: Double,
        newVehicleLocation: GeoLocation
    ) {
        currentVehicleLocation = newVehicleLocation

        if (distanceTurnFromNewLocation > distanceTurnFromPreviousLocation && distanceTurnFromNewLocation < 50) {
            distanceIsIncreasingCounter++

            if (distanceIsIncreasingCounter >= 5) {
                distanceIsIncreasingCounter = 0
                currentManeuver++
                if (currentManeuver >= stepManeuvers!!.size) {
                    return
                }
            }
        } else {
            distanceIsIncreasingCounter = 0
        }
    }
}
