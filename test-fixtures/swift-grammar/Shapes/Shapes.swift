protocol Shape {
    func area() -> Double
}

enum Color {
    case red
    case green
    case blue
}

struct Point {
    var x: Int
    var y: Int
}

enum Outcome {
    case success(value: String)
    case failure(error: String)
}
