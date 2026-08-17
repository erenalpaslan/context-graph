extension UserService: Greetable {
    func greet() -> String {
        return "Hello, \(name)"
    }
}

extension UserService {
    struct Config {
        var retries: Int
    }
}
