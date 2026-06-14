# Architecture Overview

## Decision: Use Event-Driven Architecture

After evaluating alternatives, we chose an event-driven architecture for its scalability and loose coupling.

## Components

### UserService
The **UserService** manages user lifecycle including creation, authentication, and deletion.
It depends on **UserRepository** for persistence.

### AuthController
The **AuthController** handles HTTP requests for login and registration.
It delegates to **UserService** for business logic.

## Requirements

- [ ] Users must be authenticated before accessing protected resources
- [ ] Passwords MUST be hashed before storage
- [ ] Sessions SHOULD expire after 24 hours
- [ ] The system MUST support at least 1000 concurrent users

## Related Concepts

**Authentication** is the process of verifying identity.
**Authorization** controls what authenticated users can do.
