# Security Policy

## Repository hygiene

This repository must not contain:

- Android signing keystores or passwords
- radio credentials, private keys or tokens
- local SDK paths and `local.properties`
- APK/AAB build outputs

## Radio transmission safety

Before remote transmission, enable the IC-705 TX Time-out Timer. A network failure or force-stopped app cannot guarantee that a PTT-off command reaches the radio. Test only with a dummy load, minimum power and direct supervision.

## Reporting

Please report security issues privately to the repository owner instead of opening a public issue.