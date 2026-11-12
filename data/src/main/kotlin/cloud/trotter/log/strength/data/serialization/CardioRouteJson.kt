package cloud.trotter.log.strength.data.serialization

import cloud.trotter.log.strength.domain.model.RoutePoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CardioRouteJson {
    fun encode(points: List<RoutePoint>): String = points.joinToString(prefix = "{\"v\":1,\"p\":[", postfix = "]}", separator = ",") {
        "[${it.seconds},${it.latitudeE5},${it.longitudeE5}]"
    }

    fun decode(json: String): List<RoutePoint>? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        require(integer(root.getValue("v").jsonPrimitive) == 1)
        val points = root.getValue("p").jsonArray
        require(points.size in 2..100_000)
        var previous = 0
        points.map { point ->
            val values = point.jsonArray
            require(values.size == 3)
            val seconds = integer(values[0].jsonPrimitive)
            val latitude = integer(values[1].jsonPrimitive)
            val longitude = integer(values[2].jsonPrimitive)
            require(seconds >= previous)
            require(latitude in -9_000_000..9_000_000 && longitude in -18_000_000..18_000_000)
            previous = seconds
            RoutePoint(seconds, latitude, longitude)
        }
    }.getOrNull()

    private fun integer(value: JsonPrimitive): Int {
        require(!value.isString)
        return value.int
    }
}
