package io.gutapk.registry

import io.gutapk.features.device.DeviceFeature
import io.gutapk.features.overview.OverviewFeature

// The only list a new feature adds itself to, one line each.
object Features {
    fun registerAll() {
        Registry.register(OverviewFeature)
        Registry.register(DeviceFeature)
    }
}
