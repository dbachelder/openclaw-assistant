## 2024-05-23 - HTTPS Enforcement Gap
**Vulnerability:** Application allowed global cleartext traffic (`usesCleartextTraffic="true"`) without enforcing HTTPS for public network connections, risking data exposure.
**Learning:** `usesCleartextTraffic` is necessary for local development (e.g., `localhost`, `10.0.2.2`) but requires strict application-level checks to prevent insecure public connections.
**Prevention:** Use `NetworkUtils.isUrlSecure` to validate user-provided URLs before initiating requests, ensuring HTTPS for public addresses while allowing HTTP only for local/private IPs.
