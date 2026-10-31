package com.dylenq.eq.ui

/** UI-SPEC copy for preset dialogs and snackbars, plus a pure pre-check mirroring the core name rules. */
internal object PresetNames {
    const val NAME_BLANK = "Enter a name"
    const val NAME_INVALID = "Names must be 1 to 40 characters"
    const val NAME_TAKEN = "A preset with that name already exists"
    const val TOO_MANY = "You can keep up to 100 presets. Delete one to save another."
    const val GONE = "That preset no longer exists."
    const val READ_ONLY = "Built-in presets can't be changed."
    const val NOTHING_TO_SAVE = "There are no changes to save."
    const val MAX_NAME_CHARS = 40

    /**
     * Returns the error copy for [name], or null when it is a valid free name. [existingNames] holds every
     * display name (built-ins included); [ownName] is excluded so a case-only rename of itself passes.
     * Length counts code points, matching the core's chars().count().
     */
    fun check(name: String, existingNames: List<String>, ownName: String? = null): String? {
        val t = name.trim()
        if (t.isEmpty()) return NAME_BLANK
        if (t.codePointCount(0, t.length) > MAX_NAME_CHARS || t.codePoints().anyMatch { Character.isISOControl(it) }) {
            return NAME_INVALID
        }
        val own = ownName?.trim()?.lowercase()
        val key = t.lowercase()
        if (existingNames.any { n ->
                val l = n.trim().lowercase()
                l == key && l != own
            }) {
            return NAME_TAKEN
        }
        return null
    }
}
