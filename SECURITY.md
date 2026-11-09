# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for a security problem.

Report it privately through GitHub: **Security → Report a vulnerability** on this repository. Include the
affected version or commit, the steps to reproduce and the impact you see.

We aim to acknowledge a report within 5 working days and to agree with you on a disclosure date once a fix
is available.

## Supported versions

The project is in early development: only the latest version on `main` receives security fixes.

## Deployment checklist

- Set strong values for `GATEWAY_ADMIN_PASSWORD` and `DB_PASSWORD`; never commit `.env`.
- Serve the gateway over HTTPS only (reverse proxy with TLS), so that API keys, device tokens and
  message texts are encrypted in transit; phones then use `wss://`.
- Do not expose PostgreSQL to the internet.
- Give each client application its own API key and each phone its own token; revoke them when no longer used.
