# Traffic Status Bridge

This private VPS service exposes a read-only `/traffic` JSON response for the
standby clock. It signs in to HostVDS with stored account credentials and reads
the standard `Subscription-Userinfo` header from the Sanmao subscription.

## HostVDS authentication

HostVDS issues a SimpleJWT access token that is valid for exactly 24 hours.
Pasting a browser Cookie by hand therefore never lasted: it meant chasing a
token that died every day. The service now signs in on its own.

Put the account e-mail on the first line of `config/hostvds.credentials` and the
password on the second. The service calls `POST /api/token/` whenever its cached
token is within five minutes of expiring, and caches the result in
`/data/hostvds-token.json` so a restart does not force a fresh sign-in. A 401 from
the products API triggers exactly one forced re-login before the failure is
reported, which covers a password change or a revoked token.

Only the e-mail is stripped of surrounding whitespace; the password is taken
verbatim apart from the trailing newline, so leading and trailing spaces survive.

The legacy Cookie mode still works: when `config/hostvds.credentials` is absent
or empty the service falls back to `config/hostvds.cookie`. Keep the credentials
file as the primary path — the Cookie will keep expiring daily.

Secrets live only in `config/hostvds.credentials`, `config/hostvds.cookie` and
`config/sanmao.url`. They are ignored by Git and mounted read-only into the
container. Credentials, provider URLs and Cookies must never be logged or added
to the Compose file.

Create the local configuration from the tracked templates, then replace every
placeholder with the real value:

```bash
cp .env.example .env
cp config/hostvds.credentials.example config/hostvds.credentials
cp config/sanmao.url.example config/sanmao.url
chmod 600 config/hostvds.credentials config/sanmao.url
docker compose up -d --build
curl -fsS http://127.0.0.1:18080/healthz
curl -fsS http://127.0.0.1:18080/traffic
```

`.env` controls the public port, cache lifetime, upstream timeout, log level,
and optional HostVDS server ID. The example defaults are usable for a normal
single-server setup. The two files under `config/` always remain untracked.

Rotate the HostVDS password by editing `config/hostvds.credentials` and
restarting the container. Nothing else has to change — the next fetch signs in
again. `./set-hostvds-cookie` remains only for the legacy Cookie fallback.
