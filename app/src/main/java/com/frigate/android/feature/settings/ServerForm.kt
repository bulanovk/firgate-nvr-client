package com.frigate.android.feature.settings

import com.frigate.android.core.server.HomeNetwork
import com.frigate.android.core.server.ServerProfile

/**
 * What one Save of the settings form produces. [profile] is null while a field is invalid; each
 * error belongs to its own field. [mainChanged] means the session of the old main address no
 * longer applies and the user has to sign in again.
 */
internal data class ServerForm(
    val profile: ServerProfile?,
    val mainError: String?,
    val homeError: String?,
    val mainChanged: Boolean,
)

internal fun ServerProfile.submit(mainInput: String, homeInput: String, networks: List<HomeNetwork>): ServerForm {
    val main = withMain(mainInput)
    val withMainAddress = main.getOrNull()
    val home = (withMainAddress ?: this).withHome(homeInput, networks)
    val profile = home.getOrNull()?.takeIf { withMainAddress != null }
    return ServerForm(
        profile = profile,
        mainError = main.exceptionOrNull()?.message,
        homeError = home.exceptionOrNull()?.message,
        mainChanged = withMainAddress != null && withMainAddress.primaryUrl != primaryUrl,
    )
}
