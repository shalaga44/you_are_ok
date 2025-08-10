package dev.shalaga44.you_are_okay

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform