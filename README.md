# Net Central v2

Net Central v2 is a rebuild of Net Central as one Spring Boot application. Implemented milestones provide Spring MVC, rendered pages, local assets, file H2, Liquibase initialization, local account authentication/authorization, and managed communications.

Register the first account at `/register` to become system administrator, then sign in at `/login`. Manage your account at `/profile`, administer accounts at `/users`, and configure APRS-IS/KISS/TNC2 instances at `/communications`. Create and monitor nets at `/nets`, including live participants/maps, messaging, archives, and PDF exports. Setup's `/setup/map-cache` selects the external tile server. TNC2 TCP and Serial replace the Kenwood placeholder; see [communication integration](docs/communication-welcome-center-integration.md). Manage scheduled occurrences and restart recovery on `/nets`. Use `/explorer` for stations, objects and reports, `/tools` for Winlink/NTS, and `/setup/operations` for retention and federation. The live Dashboard, Explorer, and responsive Monitor are complete through milestone 7. Operational acceptance remains in milestone 8. The application binds to localhost by default.

## Build and run

Requirements: JDK 21 and Maven 3.6.3 or later. Node.js and an external database are not required. Maven needs access to its dependency repositories on the first build.

```text
mvn verify
java -jar target/netcentralv2-0.1.0-SNAPSHOT.jar
```

Open [Net Central](http://127.0.0.1:8080). Stop with Ctrl+C. The database defaults to `./data/netcentral.mv.db`, relative to the directory from which Java is started. Use an absolute data directory for a stable installation.

- [Windows/Linux installation and configuration](docs/running.md)
- [Milestone 1 verification and source baseline](docs/milestone-1.md)
- [Local security and account administration](docs/local-security.md)
- [Milestone 3: communications, routing, and verification](docs/milestone-3.md)
- [Milestone 4: net workflow, live monitor, and archives](docs/milestone-4.md)
- [Milestone 5: schedules and restart recovery](docs/milestone-5.md)
- [Milestone 6: operational features and verification](docs/milestone-6.md)
- [Milestone 6 API contracts](docs/milestone-6-api.md)
- [Milestone 7: responsive UI and live updates](docs/milestone-7.md)
- [Complete legacy endpoint register](docs/legacy-endpoint-register.md)
- [API compatibility and intentional changes](docs/api-compatibility.md)

- [Agreed migration specification](docs/migration-specification.md)
- [Implementation and acceptance checklist](docs/implementation-checklist.md)

The specification records decisions agreed on September 27, 2026. Checked milestones reflect implemented and verified work; unchecked items are future work.


