import SwiftUI

struct ContentView: View {
    @State private var count: Int = 0
    @Published var name: String = ""

    var body: some View {
        Text("Hello, \(name)")
    }

    func increment() async throws {
        count += 1
    }
}

actor Counter {
    var value: Int = 0

    func increment() {
        value += 1
    }
}

class Container<T> where T: Equatable {
    var items: [T] = []
}

typealias Handler = (String) -> Void

infix operator +++: AdditionPrecedence

func +++(lhs: Int, rhs: Int) -> Int {
    return lhs + rhs
}
