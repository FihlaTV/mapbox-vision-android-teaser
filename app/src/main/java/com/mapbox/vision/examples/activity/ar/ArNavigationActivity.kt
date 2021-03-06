package com.mapbox.vision.examples.activity.ar

import android.app.Activity
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigation
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigationOptions
import com.mapbox.services.android.navigation.v5.offroute.OffRouteListener
import com.mapbox.services.android.navigation.v5.route.RouteFetcher
import com.mapbox.services.android.navigation.v5.route.RouteListener
import com.mapbox.services.android.navigation.v5.routeprogress.ProgressChangeListener
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress
import com.mapbox.vision.VisionManager
import com.mapbox.vision.ar.VisionArManager
import com.mapbox.vision.ar.core.models.Route
import com.mapbox.vision.ar.core.models.RoutePoint
import com.mapbox.vision.common.models.ArFeature
import com.mapbox.vision.common.utils.buildStepPointsFromGeometry
import com.mapbox.vision.common.utils.mapToManeuverType
import com.mapbox.vision.examples.R
import com.mapbox.vision.mobile.core.models.position.GeoCoordinate
import com.mapbox.vision.performance.ModelPerformance
import com.mapbox.vision.performance.ModelPerformanceConfig
import com.mapbox.vision.performance.ModelPerformanceMode
import com.mapbox.vision.performance.ModelPerformanceRate
import com.mapbox.vision.utils.VisionLogger
import kotlinx.android.synthetic.main.activity_ar_navigation.*
import kotlinx.android.synthetic.main.activity_ar_navigation.back

class ArNavigationActivity : AppCompatActivity(), RouteListener, ProgressChangeListener,
    OffRouteListener {

    companion object {
        private var TAG = ArNavigationActivity::class.java.simpleName

        private const val LOCATION_INTERVAL_DEFAULT = 0L
        private const val LOCATION_INTERVAL_FAST = 1000L

        var directionsRoute: DirectionsRoute? = null

        fun start(context: Activity) {
            context.startActivity(Intent(context, ArNavigationActivity::class.java))
        }
    }

    private val arLocationEngine by lazy {
        LocationEngineProvider.getBestLocationEngine(this)
    }

    private val arLocationEngineRequest by lazy {
        LocationEngineRequest.Builder(LOCATION_INTERVAL_DEFAULT)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .setFastestInterval(LOCATION_INTERVAL_FAST)
            .build()
    }

    private lateinit var mapboxNavigation: MapboxNavigation
    private lateinit var routeFetcher: RouteFetcher

    private var lastRouteProgress: RouteProgress? = null
    private var activeArFeature: ArFeature = ArFeature.LaneAndFence

    private val locationCallback by lazy {
        object : LocationEngineCallback<LocationEngineResult> {
            override fun onSuccess(result: LocationEngineResult?) {
            }

            override fun onFailure(exception: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_navigation)

        back.setOnClickListener {
            onBackPressed()
        }

        applyArFeature()
        ar_mode_view.setOnClickListener {
            activeArFeature = activeArFeature.getNextFeature()
            applyArFeature()
        }

        val builder = MapboxNavigationOptions
            .builder()

        mapboxNavigation =
            MapboxNavigation(this, getString(R.string.mapbox_access_token), builder.build())

        routeFetcher = RouteFetcher(this, getString(R.string.mapbox_access_token))
        routeFetcher.addRouteListener(this)
    }

    private fun applyArFeature() {
        ar_mode_view.setImageResource(activeArFeature.drawableId)
        ar_view.setLaneVisible(activeArFeature.isLaneVisible)
        ar_view.setFenceVisible(activeArFeature.isFenceVisible)
    }

    override fun onResume() {
        super.onResume()
        try {
            arLocationEngine.requestLocationUpdates(
                arLocationEngineRequest,
                locationCallback,
                mainLooper
            )
        } catch (se: SecurityException) {
            VisionLogger.d(TAG, se.toString())
        }

        mapboxNavigation.addOffRouteListener(this)
        mapboxNavigation.addProgressChangeListener(this)
        mapboxNavigation.locationEngine = arLocationEngine

        VisionManager.create()
        VisionManager.start()
        VisionManager.setModelPerformanceConfig(
            ModelPerformanceConfig.Merged(
                performance = ModelPerformance.On(
                    ModelPerformanceMode.FIXED,
                    ModelPerformanceRate.LOW
                )
            )
        )

        VisionArManager.create(VisionManager)
        ar_view.setArManager(VisionArManager)
        ar_view.onResume()

        directionsRoute.let {
            if (it == null) {
                Toast.makeText(this, "Route is not set!", Toast.LENGTH_LONG).show()
                finish()
            } else {
                setRoute(it)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ar_view.onPause()
        VisionArManager.destroy()

        VisionManager.stop()
        VisionManager.destroy()

        arLocationEngine.removeLocationUpdates(locationCallback)
        mapboxNavigation.removeProgressChangeListener(this)
        mapboxNavigation.removeOffRouteListener(this)
        mapboxNavigation.stopNavigation()
    }

    override fun onErrorReceived(throwable: Throwable?) {
        throwable?.printStackTrace()

        mapboxNavigation.stopNavigation()
        Toast.makeText(this, R.string.can_not_calculate_new_route, Toast.LENGTH_SHORT).show()
    }

    override fun onResponseReceived(response: DirectionsResponse, routeProgress: RouteProgress?) {
        mapboxNavigation.stopNavigation()

        if (response.routes().isEmpty()) {
            Toast.makeText(this, R.string.can_not_calculate_new_route, Toast.LENGTH_SHORT).show()
            return
        }
        lastRouteProgress = routeProgress

        setRoute(response.routes()[0])
    }

    private fun setRoute(route: DirectionsRoute) {
        mapboxNavigation.startNavigation(route)

        VisionArManager.setRoute(
            Route(
                points = route.getRoutePoints(),
                eta = route.duration()?.toFloat() ?: 0f
            )
        )
    }

    override fun onProgressChange(location: Location?, routeProgress: RouteProgress?) {
        lastRouteProgress = routeProgress
    }

    override fun userOffRoute(location: Location?) {
        routeFetcher.findRouteFromRouteProgress(location, lastRouteProgress)
    }

    private fun DirectionsRoute.getRoutePoints(): Array<RoutePoint> {
        val routePoints = arrayListOf<RoutePoint>()
        legs()?.forEach { leg ->
            leg.steps()?.forEach { step ->
                val maneuverPoint = RoutePoint(
                    GeoCoordinate(
                        latitude = step.maneuver().location().latitude(),
                        longitude = step.maneuver().location().longitude()
                    ),
                    step.maneuver().type().mapToManeuverType()
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
}
