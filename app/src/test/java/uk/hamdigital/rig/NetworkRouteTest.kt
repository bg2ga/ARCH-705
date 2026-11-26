package uk.hamdigital.rig

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRouteTest {
    @Test fun vpnUsesDefaultNetwork() {
        assertEquals(NetworkRoutePreference.DEFAULT, chooseNetworkRoute(local = true, onVpn = true, hasWifi = true, hasDefault = true, tryAlternate = false))
    }

    @Test fun localRadioPrefersWifiThenFallsBackToDefault() {
        assertEquals(NetworkRoutePreference.WIFI, chooseNetworkRoute(local = true, onVpn = false, hasWifi = true, hasDefault = true, tryAlternate = false))
        assertEquals(NetworkRoutePreference.DEFAULT, chooseNetworkRoute(local = true, onVpn = false, hasWifi = true, hasDefault = true, tryAlternate = true))
    }

    @Test fun publicRadioUsesDefaultNetwork() {
        assertEquals(NetworkRoutePreference.DEFAULT, chooseNetworkRoute(local = false, onVpn = false, hasWifi = true, hasDefault = true, tryAlternate = false))
    }
}
