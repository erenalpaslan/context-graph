package com.example.shapes

sealed interface Shape {
    fun area(): Double
}

data class Circle(val radius: Double) : Shape {
    override fun area(): Double = radius * radius * 3.14159
}

object EmptyShape : Shape {
    override fun area(): Double = 0.0
}

enum class Color {
    RED, GREEN, BLUE
}
