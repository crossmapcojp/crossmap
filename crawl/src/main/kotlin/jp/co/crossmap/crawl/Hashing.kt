package jp.co.crossmap.crawl

import java.security.MessageDigest

internal fun String.sha1(): String = MessageDigest.getInstance("SHA-1")
    .digest(toByteArray()).joinToString("") { "%02x".format(it) }

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }
