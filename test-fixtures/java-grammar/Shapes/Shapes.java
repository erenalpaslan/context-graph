package com.example.shapes;

public interface Shape {
    double area();
}

enum Color {
    RED, GREEN, BLUE
}

record Point(int x, int y) {
}
