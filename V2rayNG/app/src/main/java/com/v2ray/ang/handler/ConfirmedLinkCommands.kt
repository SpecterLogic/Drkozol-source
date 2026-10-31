package com.v2ray.ang.handler

/** One-use consent ticket lives only across the UI activity handoff; it is never daemon state. */
internal object ConfirmedLinkCommands {
    private var pending: Triple<String,String,Long>? = null
    @Synchronized fun issue(command: String): String {
        require(command in setOf("connect","disconnect","toggle","status"))
        val token=java.util.UUID.randomUUID().toString()
        pending=Triple(token,command,android.os.SystemClock.elapsedRealtime()+60_000)
        return token
    }
    @Synchronized fun consume(token: String?): String? {
        val ticket=pending ?: return null
        if (ticket.first!=token || android.os.SystemClock.elapsedRealtime()>ticket.third) return null
        pending=null
        return ticket.second
    }
}
