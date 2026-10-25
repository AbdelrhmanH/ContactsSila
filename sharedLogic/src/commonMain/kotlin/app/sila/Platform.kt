package app.sila

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform