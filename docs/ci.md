# Continuous integration

The project is built by **Jenkins** with the [`Jenkinsfile`](../Jenkinsfile) at the root of the repository.
There is no GitHub Actions workflow.

## Pipeline

| Stage | Branches | What it does |
|---|---|---|
| Prepare execution | all | Computes the image tag (`IMAGE_VERSION`, or `<build number>-<short SHA>`) and reads the commit details. |
| Test | all | `./mvnw verify`: all tests on H2, plus the JaCoCo coverage report. |
| Analyze | all | SonarQube analysis of project `ultra-sms-gateway`, waits for the quality gate. |
| Build and publish image | `main` | Builds the Docker image and pushes `<user>/ultra-sms-gateway:<tag>` and `:latest` to Docker Hub. |
| Deploy to production | `main`, with `DEPLOY_TO_PRODUCTION` | Asks for a manual confirmation, then updates the gateway on the server. |

At the end of every run, a report is sent to Telegram, test results are published in Jenkins and the
workspace is cleaned.

## Parameters

| Parameter | Default | Meaning |
|---|---|---|
| `DEPLOY_TO_PRODUCTION` | `false` | Deploy the published image after confirmation. Refused outside `main`. |
| `IMAGE_VERSION` | empty | Docker tag to publish, e.g. `0.2.0`. Empty: `<build number>-<short SHA>`. |

## Jenkins setup

The pipeline expects the same environment as the UltraCore backend pipeline:

- an agent with JDK 21 and the Docker CLI (with Buildx and Compose), allowed to use the Docker daemon;
- a SonarQube server reachable at `http://sonarqube:9000` (`SONAR_HOST_URL` in the `Jenkinsfile`);
- these credentials:

| Credential ID | Type | Use |
|---|---|---|
| `sonarqube-token` | Secret text | SonarQube analysis |
| `dockerhub-credentials` | Username with password | Docker Hub push; the username is the image namespace |
| `telegram-bot-token` | Secret text | Build report |
| `telegram-chat-id` | Secret text | Build report |

Create a **Pipeline** (or **Multibranch Pipeline**) job on this repository with *Pipeline script from SCM*,
script path `Jenkinsfile`.

## Production server

The deployment runs `docker compose` in `/opt/ultra-sms-gateway` (`DEPLOY_DIRECTORY` in the `Jenkinsfile`)
on the Docker host used by Jenkins. Prepare it once:

```bash
sudo mkdir -p /opt/ultra-sms-gateway
sudo cp .env.example /opt/ultra-sms-gateway/.env   # then set real passwords
```

The production `.env` stays on the server and is never taken from Git. Each deployment copies
`docker-compose.yml`, sets `GATEWAY_IMAGE` to the image just published, pulls it and restarts the gateway.
PostgreSQL and its volume are kept.

## Pull requests from forks

Jenkins should not run code from forks automatically: a pull request could read the credentials. Maintainers
run the tests of an external pull request themselves (`./mvnw verify`) or trigger a build once they have read
the change.
