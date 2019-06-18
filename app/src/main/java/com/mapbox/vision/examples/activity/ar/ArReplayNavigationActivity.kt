package com.mapbox.vision.examples.activity.ar

import android.app.PendingIntent
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.core.constants.Constants.PRECISION_6
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigation
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigationOptions
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute
import com.mapbox.services.android.navigation.v5.offroute.OffRouteListener
import com.mapbox.services.android.navigation.v5.route.RouteFetcher
import com.mapbox.services.android.navigation.v5.route.RouteListener
import com.mapbox.services.android.navigation.v5.routeprogress.ProgressChangeListener
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress
import com.mapbox.vision.VisionReplayManager
import com.mapbox.vision.ar.VisionArManager
import com.mapbox.vision.ar.core.models.Route
import com.mapbox.vision.ar.core.models.RoutePoint
import com.mapbox.vision.examples.R
import com.mapbox.vision.mobile.core.interfaces.VisionEventsListener
import com.mapbox.vision.mobile.core.models.FrameSegmentation
import com.mapbox.vision.mobile.core.models.detection.FrameDetections
import com.mapbox.vision.mobile.core.models.position.GeoCoordinate
import com.mapbox.vision.mobile.core.models.position.VehicleState
import com.mapbox.vision.performance.ModelPerformance
import com.mapbox.vision.performance.ModelPerformanceConfig
import com.mapbox.vision.performance.ModelPerformanceMode
import com.mapbox.vision.performance.ModelPerformanceRate
import kotlinx.android.synthetic.main.activity_ar_navigation.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.TimeUnit

class ArReplayNavigationActivity : AppCompatActivity(), RouteListener, ProgressChangeListener, OffRouteListener {

    companion object {
        private val REPLAY_PATH = "${Environment.getExternalStorageDirectory().absolutePath}/Telemetry/" +
                "default"
        private val ROUTE_TO = Point.fromLngLat(27.6897746, 53.9447667)
    }

    private var routeFrom: Point? = null
    private lateinit var routeFetcher: RouteFetcher

    private lateinit var mapboxNavigation: MapboxNavigation
    private var lastRouteProgress: RouteProgress? = null

    private val visionLocationEngine = object: LocationEngine {
        private var locationCallback: LocationEngineCallback<LocationEngineResult>? = null
        var lastLocation = Location("Vision").also {
            it.latitude = .0
            it.longitude = .0
        }

        override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
            locationCallback = null
        }

        override fun removeLocationUpdates(pendingIntent: PendingIntent?) {}

        override fun requestLocationUpdates(
            request: LocationEngineRequest,
            callback: LocationEngineCallback<LocationEngineResult>,
            looper: Looper?
        ) {
            locationCallback = callback
        }

        override fun requestLocationUpdates(request: LocationEngineRequest, pendingIntent: PendingIntent?) {}

        override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
            callback.onSuccess(LocationEngineResult.create(lastLocation))
        }

        fun setLocation(latitude: Double, longitude: Double) {
            lastLocation.latitude = latitude
            lastLocation.longitude = longitude
            locationCallback?.onSuccess(LocationEngineResult.create(lastLocation))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_navigation)

        back.setOnClickListener {
            onBackPressed()
        }

        routeFetcher = RouteFetcher(this, getString(R.string.mapbox_access_token))
        routeFetcher.addRouteListener(this)


        mapboxNavigation = MapboxNavigation(
            this,
            getString(R.string.mapbox_access_token),
            MapboxNavigationOptions.builder().build()
        )
    }

    private var videoPaused = false

    override fun onResume() {
        super.onResume()
        VisionReplayManager.create(REPLAY_PATH)
        VisionReplayManager.start(object : VisionEventsListener {
            override fun onFrameSegmentationUpdated(frameSegmentation: FrameSegmentation) {
                runOnUiThread {
                    ar.setFrameSegmentation(frameSegmentation)
                }
            }

            override fun onFrameDetectionsUpdated(frameDetections: FrameDetections) {
                runOnUiThread {
                    ar.setFrameDetections(frameDetections)
                }
            }

            override fun onVehicleStateUpdated(vehicleState: VehicleState) {
                runOnUiThread {
                    if (routeFrom == null &&
                        vehicleState.geoLocation.geoCoordinate.latitude > 0 &&
                        vehicleState.geoLocation.geoCoordinate.longitude > 0
                    ) {
                        routeFrom = Point.fromLngLat(
                            vehicleState.geoLocation.geoCoordinate.longitude,
                            vehicleState.geoLocation.geoCoordinate.latitude
                        )

                        getRoute(routeFrom!!, ROUTE_TO)
                    }

                    visionLocationEngine.setLocation(
                        latitude = vehicleState.geoLocation.geoCoordinate.latitude,
                        longitude = vehicleState.geoLocation.geoCoordinate.longitude
                    )
                    sign_turn.updateVehicleLocation(vehicleState.geoLocation)
                    ar.setSpeed(vehicleState.speed)
                }
            }
        })
        VisionReplayManager.setModelPerformanceConfig(
            ModelPerformanceConfig.Merged(
                performance = ModelPerformance.On(ModelPerformanceMode.FIXED, ModelPerformanceRate.LOW)
            )
        )
        VisionReplayManager.setVideoSourceListener(ar)

        VisionReplayManager.setProgress(TimeUnit.SECONDS.toMillis(30))

        root.setOnLongClickListener {
            if (videoPaused) {
                VisionReplayManager.resume()
            } else {
                VisionReplayManager.pause()
            }
            videoPaused = !videoPaused

            return@setOnLongClickListener true
        }

        VisionArManager.create(VisionReplayManager, ar)

        mapboxNavigation.addOffRouteListener(this)
        mapboxNavigation.addProgressChangeListener(this)
        mapboxNavigation.locationEngine = visionLocationEngine
    }

    private fun getRoute(origin: Point, destination: Point) {
        NavigationRoute.builder(this)
            .accessToken(Mapbox.getAccessToken()!!)
            .origin(origin)
            .destination(destination)
            .profile(DirectionsCriteria.PROFILE_DRIVING)
            .build()
            .getRoute(object : Callback<DirectionsResponse> {
                override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                    if (response.body() == null || response.body()!!.routes().size < 1) {
                        return
                    }

                    val route = response.body()!!.routes()[0]
                    setRoute(route)
                }

                override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                    throwable.printStackTrace()
                }
            })
    }

    override fun onPause() {
        super.onPause()
        VisionArManager.destroy()

        VisionReplayManager.stop()
        VisionReplayManager.destroy()
    }

    override fun onErrorReceived(throwable: Throwable?) {
        throwable?.printStackTrace()

        mapboxNavigation.stopNavigation()

        Toast.makeText(this, R.string.can_not_calculate_new_route, Toast.LENGTH_SHORT).show()
    }

    override fun onResponseReceived(response: DirectionsResponse, routeProgress: RouteProgress?) {
        if (response.routes()?.isEmpty()) {
            Toast.makeText(this, R.string.can_not_calculate_new_route, Toast.LENGTH_SHORT).show()
            return
        }

        mapboxNavigation.stopNavigation()

        lastRouteProgress = routeProgress

        setRoute(response.routes()[0])
    }

    override fun onProgressChange(location: Location?, routeProgress: RouteProgress?) {
        lastRouteProgress = routeProgress
    }

    override fun userOffRoute(location: Location?) {
        routeFetcher.findRouteFromRouteProgress(location, lastRouteProgress)
    }

    private fun setRoute(route: DirectionsRoute) {
        sign_turn.setManeuvers(route.getManeuvers()!!)

        mapboxNavigation.startNavigation(route)

        VisionArManager.setRoute(
            Route(
                points = route.getRoutePoints(),
                eta = route.duration()?.toFloat() ?: 0f,
                sourceStreetName = "TODO()",
                targetStreetName = "TODO()"
            )
        )
    }

    private fun DirectionsRoute.getManeuvers(): List<StepManeuver>? {
        return legs()?.flatMap { leg ->
            return leg.steps()?.map { step ->
                step.maneuver()
            }
        }
    }

    private fun DirectionsRoute.getRoutePoints(): Array<RoutePoint> {
        val routePoints = arrayListOf<RoutePoint>()
        legs()?.forEach { leg ->
            leg.steps()?.forEach { step ->
                val maneuverPoint = RoutePoint(
                    GeoCoordinate(
                        latitude = step.maneuver().location().latitude(),
                        longitude = step.maneuver().location().longitude()
                    )
                )
                routePoints.add(maneuverPoint)

                step.geometry()
                    ?.buildStepPointsFromGeometry()
                    ?.map { geometryStep ->
                        RoutePoint(
                            GeoCoordinate(
                                latitude = geometryStep.latitude(),
                                longitude = geometryStep.longitude()
                            )
                        )
                    }
                    ?.let { stepPoints ->
                        routePoints.addAll(stepPoints)
                    }
            }
        }

        return routePoints.toTypedArray()
    }

    private fun String.buildStepPointsFromGeometry(): List<Point> {
        return PolylineUtils.decode(this, PRECISION_6)
    }
}
