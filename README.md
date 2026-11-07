# E-Home

Engineering of KNX installations (ETS project import, Zigbee devices, rules) and deployment to the
HomeControl controller. Development: `CLAUDE.md`; the Debian package in detail: `doc/debian.md`.

## Install (Debian 13, Tomcat 10)

```bash
sudo curl -fsSLo /usr/share/keyrings/ehome-archive-keyring.gpg https://haumacher.github.io/ehome/ehome-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/ehome-archive-keyring.gpg] https://haumacher.github.io/ehome stable main" \
  | sudo tee /etc/apt/sources.list.d/ehome.list
sudo apt update && sudo apt install ehome
```

The app runs in Tomcat on `localhost:8080` at `/ehome`; Tomcat is never restarted by the package.

## TLS by a reverse proxy

Controllers connect with a WebSocket (`wss://<host>/ehome/api/controllers/<id>/socket`). The proxy must
pass the upgrade on; a proxy that does not (AJP, or `ProxyPass` without `upgrade=websocket`) makes the
controller report `the server refused the upgrade: HTTP 426`.

Apache (2.4.47 or later; `a2enmod proxy proxy_http headers`), in the TLS virtual host:

```apache
ProxyPreserveHost On
RequestHeader set X-Forwarded-Proto "https"
ProxyTimeout 120
ProxyPass        /ehome/ http://127.0.0.1:8080/ehome/ upgrade=websocket
ProxyPassReverse /ehome/ http://127.0.0.1:8080/ehome/
```

nginx: `/usr/share/doc/ehome/nginx-ehome.conf`.

Tomcat trusts the forwarded headers only from a proxy on the same host (`RemoteIpValve` in
`/etc/tomcat10/Catalina/localhost/ehome.xml`); without them the enrollment answers `ws://` URLs.

## Connect a controller

1. In E-Home: the project's tab "Controller" → add a controller, which shows an enrollment code.
2. On the controller's Settings page: E-Home address `https://<host>/ehome` and the code.

The controller then connects on its own and shows "connected" on its status page.
