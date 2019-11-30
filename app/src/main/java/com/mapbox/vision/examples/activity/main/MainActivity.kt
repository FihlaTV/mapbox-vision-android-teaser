package com.mapbox.vision.examples.activity.main

import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.mapbox.vision.VisionManager
import com.mapbox.vision.common.view.BaseTeaserActivity
import com.mapbox.vision.common.view.show
import com.mapbox.vision.examples.R
import com.mapbox.vision.examples.activity.ar.ArMapActivity
import com.mapbox.vision.examples.activity.map.MapActivity
import com.mapbox.vision.performance.ModelPerformance
import com.mapbox.vision.performance.ModelPerformanceConfig
import com.mapbox.vision.performance.ModelPerformanceMode
import com.mapbox.vision.performance.ModelPerformanceRate
import com.mapbox.vision.performance.ModelType
import com.mapbox.vision.safety.VisionSafetyManager
import com.mapbox.vision.view.VisionView

class MainActivity : BaseTeaserActivity() {

    override fun initViews(root: View) {
        root.findViewById<LinearLayout>(R.id.object_mapping_button_container).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, MapActivity::class.java)) }
        }

        root.findViewById<LinearLayout>(R.id.ar_navigation_button_container).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ArMapActivity::class.java)) }
        }

        root.findViewById<ImageView>(R.id.title_teaser).apply {
            show()
        }
    }

    override fun getFrameStatistics() = VisionManager.getFrameStatistics()

    override fun initVisionManager(visionView: VisionView): Boolean {
        VisionManager.create(
            modelType = ModelType.NEW
        )
        VisionManager.setModelPerformanceConfig(
            ModelPerformanceConfig.Merged(
                performance = ModelPerformance.On(
                    mode = ModelPerformanceMode.FIXED,
                    rate = ModelPerformanceRate.FIXED(5f)
                )
            )
        )

        visionView.setVisionManager(VisionManager)
        VisionManager.visionEventsListener = visionEventsListener
        VisionManager.start()

        VisionSafetyManager.create(VisionManager)
        VisionSafetyManager.visionSafetyListener = visionSafetyListener

        return true
    }

    override fun destroyVisionManager() {
        VisionSafetyManager.destroy()
        VisionManager.stop()
        VisionManager.destroy()
    }
}
