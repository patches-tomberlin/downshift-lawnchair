package app.lawnchair.profile

/**
 * Identifies one of the two fixed Downshift workspace profiles (Personal/Work).
 *
 * Deliberately named "WorkspaceProfile", not "Profile"/"WorkProfile", to avoid colliding
 * with Android's own managed-work-profile concepts ([Favorites.PROFILE_ID],
 * `UserProfileManager`, `WorkProfileManager`) which are unrelated real-OS-user features.
 */
enum class WorkspaceProfileId {
    PERSONAL,
    WORK,
    ;

    /** Directory name used to store this profile's db/wallpaper snapshot on disk. */
    val storageKey: String
        get() = when (this) {
            PERSONAL -> "personal"
            WORK -> "work"
        }

    companion object {
        fun fromStorageKey(key: String): WorkspaceProfileId =
            entries.first { it.storageKey == key }

        fun fromString(value: String): WorkspaceProfileId =
            entries.find { it.name == value } ?: PERSONAL
    }
}
