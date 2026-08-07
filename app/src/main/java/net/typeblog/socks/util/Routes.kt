package net.typeblog.socks.util

import android.content.Context
import android.net.VpnService
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants.ROUTE_ALL
import net.typeblog.socks.util.Constants.ROUTE_CHN
import net.typeblog.socks.util.Constants.ROUTE_RU
import net.typeblog.socks.util.Constants.ROUTE_RU_CHN

object Routes {
    @JvmStatic
    fun addRoutes(context: Context, builder: VpnService.Builder, name: String) {
        val routes = ArrayList<String>()
        when (name) {
            ROUTE_ALL -> routes.add("0.0.0.0/0")
            ROUTE_CHN -> routes.addAll(context.resources.getStringArray(R.array.simple_route).toList())
            ROUTE_RU -> routes.addAll(context.resources.getStringArray(R.array.ru_route).toList())
            ROUTE_RU_CHN -> {
                routes.addAll(context.resources.getStringArray(R.array.simple_route).toList())
                routes.addAll(context.resources.getStringArray(R.array.ru_route).toList())
            }
            else -> routes.add("0.0.0.0/0")
        }

        for (r in routes) {
            val cidr = r.split("/")

            // Cannot handle 127.0.0.0/8
            if (cidr.size == 2 && !cidr[0].startsWith("127")) {
                try {
                    builder.addRoute(cidr[0], cidr[1].toInt())
                } catch (e: Exception) {
                    // Ignore invalid routes
                }
            }
        }
    }
}
