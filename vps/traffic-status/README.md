# Traffic Status Bridge

This private VPS service exposes a read-only `/traffic` JSON response for the
standby clock. It queries HostVDS with a manually supplied session Cookie and
reads the standard `Subscription-Userinfo` header from the Sanmao subscription.

Secrets live only in `config/hostvds.cookie` and `config/sanmao.url`. They are
ignored by Git and mounted read-only into the container. Provider URLs and
Cookies must never be logged or added to the Compose file.

Create the local configuration from the tracked templates, then replace every
placeholder with the real value:

```bash
cp .env.example .env
cp config/hostvds.cookie.example config/hostvds.cookie
cp config/sanmao.url.example config/sanmao.url
chmod 600 config/hostvds.cookie config/sanmao.url
docker compose up -d --build
curl -fsS http://127.0.0.1:18080/healthz
curl -fsS http://127.0.0.1:18080/traffic
```

`.env` controls the public port, cache lifetime, upstream timeout, log level,
and optional HostVDS server ID. The example defaults are usable for a normal
single-server setup. The two files under `config/` always remain untracked.

Update an expired HostVDS Cookie without putting it in shell history:

```bash
./set-hostvds-cookie
```
