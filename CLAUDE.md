# AlgoTrader Backend

## Tech Stack
- Java 24, Spring Boot 3.4.2, Maven (use `./mvnw`, not global `mvn`)
- H2 Database (file mode, embedded), Redis (Lettuce), Caffeine cache
- MapStruct 1.6.3, Lombok 1.18.40, Resilience4j 2.3.0
- JAVA_HOME: `/Users/sandeep/.sdkman/candidates/java/24-tem`

## Build & Run
```bash
./mvnw clean compile          # compile
./mvnw test                   # run tests
./mvnw spotless:apply         # format code
./mvnw spotless:check         # check formatting
./mvnw spring-boot:run        # run app (port 40002)
```

## Code Rules

### Database & JPA
- **No N+1 queries.** Always use `JOIN FETCH`, `@EntityGraph`, or `@Query` with explicit joins. Never rely on lazy loading triggering individual SELECTs in a loop.
- For enumerated `String` attributes, always include `columnDefinition` with varchar size: `@Column(columnDefinition = "varchar(50)")`
- Use Flyway migrations for all schema changes — never `ddl-auto=update` outside tests
- PositionService.getPositions() reads from Redis (real-time), never from H2. H2 is for historical queries only.

### Comments
- **Every class must have a Javadoc comment** explaining its purpose, responsibilities, and how it fits in the system. This is critical for LLM-assisted debugging later.
- Add `why` comments for non-obvious business logic (e.g., "// Kite tokens expire at 6 AM IST, so TTL must align to next 6 AM" or "// Delta is signed: negative for short positions").
- Add `context` comments for trading domain concepts that aren't self-evident (e.g., "// STT is charged only on sell-side for options" or "// OI change = today's OI - previous day's OI").
- Do NOT add comments for self-evident code (getters, simple assignments, standard patterns).

### Naming
- Use the class name (starting lowercase) for instance variable names: `OrderService orderService`, `StrategyEngine strategyEngine`. This makes usages searchable by class name.
- Use simple class names in code. Use import statements with fully qualified names.
- **Related files must follow consistent naming patterns across layers:**
  - Domain model: `Order.java` (no suffix)
  - JPA entity: `OrderEntity.java`
  - JPA repository: `OrderJpaRepository.java`
  - Redis repository: `OrderRedisRepository.java`
  - MapStruct mapper: `OrderMapper.java`
  - Service: `OrderService.java`
  - Controller: `OrderController.java`
  - DTO request: `OrderRequest.java`
  - DTO response: `OrderResponse.java`
  - Event: `OrderEvent.java`
- Enums go in `domain/enums/`, value objects in `domain/vo/`, domain models in `domain/model/`.

### Mapping
- Always use MapStruct for conversions between domain models, JPA entities, and DTOs. Never use ModelMapper or manual mapping.
- Mapping boundaries: Controllers map DTO<->Domain, Repositories map Entity<->Domain, Broker maps KiteSDK<->Domain.

### Architecture
- Services operate exclusively on domain models, never on entities or DTOs
- All broker operations flow through `BrokerGateway` interface (not direct service injection)
- Use `@Async("eventExecutor")` for event listeners to prevent blocking
- Mark future integration points with `#TODO` comments

### Formatting
- Spotless + Palantir Java Format enforced (run `./mvnw spotless:apply` before committing)
- 4-space indentation for Java

### Testing
- Use H2 in-memory (`jdbc:h2:mem:test`) for tests
- Unit tests go in `src/test/java/com/algotrader/unit/`
- Integration tests go in `src/test/java/com/algotrader/integration/`
