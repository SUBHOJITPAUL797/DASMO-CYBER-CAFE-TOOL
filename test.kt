enum class AuthState {
    LOADING,
    NOT_LOGGED_IN,
    DEVICE_MISMATCH,
    PENDING_APPROVAL,
    APPROVED
}

data class AppUser(
    val email: String = "",
    val deviceId: String = "",
    val isApproved: Boolean = false,
    val isAdmin: Boolean = false
)
