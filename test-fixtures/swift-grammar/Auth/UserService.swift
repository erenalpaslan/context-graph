import Foundation

public class UserService {
    private let name: String
    var loginCount: Int = 0

    public init(name: String) {
        self.name = name
    }

    public func save(user: String) -> String {
        return user
    }

    public func save(user: String, retries: Int) -> String {
        return user
    }

    class AuditLog {
        func record() {
        }
    }
}
