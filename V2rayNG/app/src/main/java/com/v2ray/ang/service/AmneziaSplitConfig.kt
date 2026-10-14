package com.v2ray.ang.service

/** Overrides only interface application rules; all cryptographic and AWG fields stay intact. */
internal fun amneziaSplitConfig(raw: String, enabled: Boolean, bypass: Boolean, packages: Set<String>): String {
    if (!enabled || packages.isEmpty()) return raw
    var inInterface = false
    return raw.lineSequence().flatMap { line ->
        val trimmed = line.substringBefore('#').trim()
        if (trimmed.startsWith("[")) {
            inInterface = trimmed.equals("[Interface]", ignoreCase = true)
            if (inInterface) {
                val key = if (bypass) "ExcludedApplications" else "IncludedApplications"
                return@flatMap sequenceOf(line, "$key = ${packages.sorted().joinToString(", ")}")
            }
        }
        val key = trimmed.substringBefore('=').trim()
        if (inInterface && (key.equals("ExcludedApplications", true) || key.equals("IncludedApplications", true))) emptySequence()
        else sequenceOf(line)
    }.joinToString("\n")
}
