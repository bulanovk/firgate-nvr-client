package com.frigate.android.core.network

import com.frigate.android.core.server.HomeNetwork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NetworkSnapshotTest {
    @Test
    fun currentNetworkIsAddedByNameWithoutAndroidQuotes() {
        val snapshot = NetworkSnapshot("\"Home WiFi\"", "aa:bb:cc:dd:ee:ff", true)

        assertEquals(HomeNetwork("Home WiFi"), snapshot.asHomeNetwork(pinAccessPoint = false))
    }

    @Test
    fun currentAccessPointIsAddedWithItsBssid() {
        val snapshot = NetworkSnapshot("Home WiFi", "aa:bb:cc:dd:ee:ff", true)

        assertEquals(HomeNetwork("Home WiFi", "aa:bb:cc:dd:ee:ff"), snapshot.asHomeNetwork(pinAccessPoint = true))
    }

    @Test
    fun nothingToAddWithoutWifiOrPermission() {
        assertNull(NetworkSnapshot(null, null, true).asHomeNetwork(false))
        assertNull(NetworkSnapshot("<unknown ssid>", null, false).asHomeNetwork(false))
        assertNull(NetworkSnapshot("", null, true).asHomeNetwork(false))
    }

    @Test
    fun hiddenBssidCannotBePinned() {
        assertNull(NetworkSnapshot("Home WiFi", "02:00:00:00:00:00", true).asHomeNetwork(pinAccessPoint = true))
        assertNull(NetworkSnapshot("Home WiFi", null, true).asHomeNetwork(pinAccessPoint = true))
    }
}
