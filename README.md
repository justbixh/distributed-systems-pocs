# distributed-systems-pocs

A collection of proofs of concept and R&D experiments on backend and distributed systems, mostly built with Java and Spring Boot.

Each POC is self-contained in its own folder, with its own build, its own README, and instructions to run it locally. 
Nothing is shared between them, so you can clone the repo and run any single POC without touching the rest.


## Repo layout

```
distributed-systems-pocs/
  README.md
  .gitignore
  websocket-poc/
    README.md
    pom.xml
    docker-compose.yml
    src/
  <next-poc>/
```

## Running a POC

Every POC follows the same pattern:

```bash
cd <poc-folder>

# start any dependencies (Kafka, Postgres, etc.), if the POC has them
docker compose up -d

# run the service
./mvnw spring-boot:run
```

Check the README inside each folder for ports, endpoints, and test instructions.

### Prerequisites

- Java 21+ (check each POC's `pom.xml` for the exact version)
- Docker or Podman, for POCs that need external services
- Maven is optional because each POC ships with the Maven wrapper

## Conventions

- **Independent builds:** each POC has its own `pom.xml`. There is no root parent POM, so a broken POC never breaks the others.
- **One folder per POC:** if a POC needs multiple services (say, a producer and a consumer), they all live inside that POC's folder.
- **Every POC has a README** covering the problem, the architecture, how to run it, and what was learned.
- **Findings are documented:** benchmarks, latency numbers, trade-offs, and gotchas go in the POC's README, not just the code.
- **No secrets in the repo:** use `.env.example` for config templates and keep real values in `.env` (gitignored).

A good POC README skeleton:

```markdown
# <POC name>

## Problem
What question or idea is this exploring?

## Approach
Architecture overview (a diagram helps) and key design choices.

## How to run
Exact commands, ports, and sample requests.

## Results
Numbers, observations, and what worked or didn't.

## Takeaways
What I'd do differently, and when this approach is worth using.
```

## About

Built by [Bish](https://github.com/justbixh). These are learning and experimentation projects, not production-ready code.
