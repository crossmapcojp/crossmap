package jp.co.crossmap

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform