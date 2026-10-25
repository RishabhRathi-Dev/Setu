# Getting help

Use the [quickstart](README.md#quick-start) to verify your local setup. The [documentation index](docs/README.md) links the subsystem references and operational guides.

| Need | Start here |
| --- | --- |
| Build or start the broker | [Quickstart](README.md#quick-start) and [operations](docs/operations.md) |
| Build or run the Docker image | [Containers, resource limits and publication](docs/containers.md) |
| Understand a property or default | [Configuration reference](docs/configuration.md) |
| Diagnose errors, timeouts, or missing records | [Troubleshooting](docs/operations.md#logs-and-diagnosis) and [client behavior](setu-client/README.md) |
| Understand durability or retention | [Storage guide](setu-storage/README.md) and [retention timeline](docs/operations.md#file-retention-timeline) |
| Reproduce or interpret performance | [Benchmark guide](setu-benchmark/README.md) |
| Contribute a change | [Contributing guide](CONTRIBUTING.md) |
| Report a vulnerability | [Security policy](SECURITY.md) |

## Bugs, requests, and questions

Search [existing issues](https://github.com/RishabhRathi-Dev/Setu/issues) before opening a new one. Use the bug or feature form when appropriate. For usage questions, open a blank issue with a clear question and the commands you tried.

Useful reports identify the commit (`git rev-parse HEAD`), OS, `java -version`, Maven Wrapper version output, storage mode, transport, and relevant configuration. Include the smallest reproduction and the full error text. For benchmark reports, include the profile, host/resource budget, source revision, and qualification results.

Remove secrets, certificate material, application data, and sensitive paths before sharing configuration or logs. Report exploitable security issues privately using [SECURITY.md](SECURITY.md).
