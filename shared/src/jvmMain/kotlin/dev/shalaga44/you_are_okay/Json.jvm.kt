package dev.shalaga44.you_are_okay

import com.google.gson.Gson

val commonGson = Gson()
actual fun toJson(any: Any): String {
    return commonGson.toJson(any)
}