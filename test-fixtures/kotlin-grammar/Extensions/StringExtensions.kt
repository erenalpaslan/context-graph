package com.example.ext

fun String.shout(): String = this.uppercase()

fun String.shout(times: Int): String = this.uppercase().repeat(times)

val String.firstOrEmpty: String
    get() = if (this.isEmpty()) "" else this.substring(0, 1)

fun Int.shout(): String = this.toString()
