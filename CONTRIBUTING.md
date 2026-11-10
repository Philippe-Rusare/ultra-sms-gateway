# Contributing

Thank you for helping. Issues, documentation fixes and pull requests are all welcome.

## Before you start

- For a bug, open an issue with the steps to reproduce, what you expected and what happened.
- For a feature or a change of behaviour, open an issue first so that we agree on the approach
  before you spend time on code.
- Never post real phone numbers, API keys, device tokens or message texts in issues or logs.
- Security problems: do not open a public issue, see [SECURITY.md](SECURITY.md).

## Development setup

Requirements: JDK 21 or later, Docker (only to run PostgreSQL locally).

```bash
./mvnw test                    # unit and integration tests (H2 in memory, no Docker)
cp .env.example .env
docker compose up -d postgres
GATEWAY_ADMIN_PASSWORD=dev DB_PASSWORD=<from .env> ./mvnw spring-boot:run
```

The dashboard is then at <http://localhost:8080>, the API documentation at `/swagger-ui.html`.

To try the device side without a phone, `tools/FakeDevice.java` connects like the Android app and
answers every `send` frame with `ack`, `sent` and `delivered` (no dependency, JDK 21+):

```bash
java tools/FakeDevice.java ws://localhost:8080/v1/device/ws usgd_... [--fail-every 5]
```

For a dashboard full of data, start with the `demo` profile instead
(see [Try it without a phone](README.md#try-it-without-a-phone)).

## Guidelines

- **Tests.** Every behaviour change comes with tests. `./mvnw test` must pass.
- **Database.** Never edit a released migration; add `V<n>__description.sql`. Keep SQL portable between
  PostgreSQL and H2 in PostgreSQL mode.
- **Documentation.** Update `docs/` when you change the API, the device protocol or the state machine.
  The device protocol is a contract with deployed apps: add fields, do not rename or remove them.
- **Secrets and personal data.** Never log message text, tokens or API keys. Avoid logging phone numbers.
- **Style.** Follow the existing code: constructor injection, small services, English names and comments,
  four-space indentation.
- **Commits.** One logical change per commit, with a message in the imperative mood
  (`Add operator routing`, `Fix requeue after reconnection`).

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make your change with its tests and documentation.
3. Run `./mvnw verify`.
4. Open the pull request and fill in the template. Link the issue it solves.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).

## Code of conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). Be kind and constructive.
