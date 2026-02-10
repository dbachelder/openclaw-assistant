## 2024-05-23 - [Enforcing HTTPS/Local Network for Webhook URLs]
**Vulnerability:** Application allowed configuring arbitrary HTTP URLs for sensitive webhook communication, risking data interception.
**Learning:** `EncryptedSharedPreferences` protects data at rest, but `usesCleartextTraffic="true"` combined with lack of input validation exposes data in transit. `NetworkUtils` validation logic provided a targeted fix without breaking local development flows.
**Prevention:** Always validate user-provided URLs for security schemes (HTTPS) or safe destinations (Localhost/Private IP) before use.
