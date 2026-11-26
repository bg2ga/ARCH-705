# Security Policy

This repository must not contain Android signing keys, radio credentials, private keys, local SDK paths or APK/AAB build outputs.

Remote transmission requires the IC-705 TX Time-out Timer to be enabled on the radio. A lost network connection or force-stopped app cannot guarantee PTT-off delivery. Test only with a dummy load, minimum power and direct supervision.