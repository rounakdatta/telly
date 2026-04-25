package club.taptappers.telly.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class HealthConnectStatus {
    /** Health Connect SDK isn't installed/available on this device. */
    data object Unavailable : HealthConnectStatus()

    /** Available, but Telly is missing one or more required read permissions. */
    data object NeedsPermission : HealthConnectStatus()

    /** All required permissions granted. */
    data object Granted : HealthConnectStatus()
}

@Singleton
class HealthConnectAuthState @Inject constructor(
    private val helper: HealthConnectHelper
) {
    private val _status = MutableStateFlow<HealthConnectStatus>(HealthConnectStatus.Unavailable)
    val status: StateFlow<HealthConnectStatus> = _status.asStateFlow()

    val requiredPermissions: Set<String>
        get() = helper.requiredPermissions

    /** Re-derive from the OS. Cheap; safe to call on screen entry. */
    suspend fun refresh() {
        _status.value = when {
            !helper.isAvailable() -> HealthConnectStatus.Unavailable
            !helper.hasAllPermissions() -> HealthConnectStatus.NeedsPermission
            else -> HealthConnectStatus.Granted
        }
    }
}
