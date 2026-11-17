#!/bin/sh
# Builds the Debian package of E-Home from the war of the profile "debian":
#
#   ./mvnw install -Pproduction,debian   # ehome/target/ehome.war with H2, data in /var/lib/ehome
#   bin/build-deb.sh [version]           # ehome/target/ehome_<version>_all.deb
#
# Without a version argument: the tag vX.Y.Z on HEAD gives X.Y.Z, otherwise the app's version from
# ehome/pom.xml with the commit (1.0.0~git20261025143012.91d27ec for 1.0.0-SNAPSHOT). The package runs the
# war in Debian's Tomcat 10 (packaging/debian, doc/debian.md).
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WAR="$ROOT/ehome/target/ehome.war"
SRC="$ROOT/packaging/debian"
OUT="$ROOT/ehome/target"

if [ ! -f "$WAR" ]; then
	echo "Missing $WAR: build it with ./mvnw install -Pproduction,debian" >&2
	exit 1
fi
if ! unzip -l "$WAR" | grep -q 'WEB-INF/lib/h2.jar'; then
	echo "$WAR is not the war of the profile debian (no H2 driver): ./mvnw install -Pproduction,debian" >&2
	exit 1
fi

VERSION="$1"
if [ -z "$VERSION" ]; then
	TAG=$(git -C "$ROOT" describe --tags --exact-match --match 'v[0-9]*' 2>/dev/null || true)
	if [ -n "$TAG" ]; then
		VERSION=${TAG#v}
	else
		POM_VERSION=$(grep -m1 -A1 '<artifactId>de.haumacher.ehome</artifactId>' "$ROOT/ehome/pom.xml" \
			| sed -n 's:.*<version>\(.*\)</version>.*:\1:p')
		# The commit time in UTC, to the second: two snapshots of one day are ordered by it, not by
		# the hash (an older package with only the date, 1.0.0~git20261103.<hash>, sorts below it).
		COMMIT=$(TZ=UTC0 git -C "$ROOT" log -1 --format='%cd.%h' --date=format-local:%Y%m%d%H%M%S)
		VERSION="${POM_VERSION%-SNAPSHOT}~git$COMMIT"
	fi
fi

# Every configuration variable of the app (${env:tl_...} in its configuration) has its line in the
# settings the package ships, commented out with its note (haumacher/ehome #185).
MISSING=
for name in $(grep -rhoE '\$\{env:tl_[A-Za-z0-9_]+' "$ROOT/ehome/src/main/webapp/WEB-INF" | sed 's/^\${env://' | sort -u); do
	if ! grep -qE "^#?$name=" "$SRC/ehome.env"; then
		MISSING="$MISSING $name"
	fi
done
if [ -n "$MISSING" ]; then
	echo "Settings without a line in $SRC/ehome.env:$MISSING" >&2
	exit 1
fi

STAGE="$OUT/deb"
rm -rf "$STAGE"
install -d "$STAGE/DEBIAN"
install -D -m 0644 "$WAR" "$STAGE/usr/share/ehome/ehome.war"
install -D -m 0644 "$SRC/ehome.xml" "$STAGE/usr/share/ehome/tomcat/ehome.xml"
install -D -m 0644 "$SRC/nginx-ehome.conf" "$STAGE/usr/share/doc/ehome/nginx-ehome.conf"
install -D -m 0644 "$SRC/tomcat10-ehome.conf" "$STAGE/usr/lib/systemd/system/tomcat10.service.d/ehome.conf"
# The settings: a conffile (dpkg asks at an upgrade when the administrator changed it), readable by root
# only (an API key goes there); the generated passwords are in /etc/ehome/secrets.env (postinst).
install -D -m 0640 "$SRC/ehome.env" "$STAGE/etc/ehome/ehome.env"
echo /etc/ehome/ehome.env > "$STAGE/DEBIAN/conffiles"
install -m 0755 "$SRC/preinst" "$SRC/postinst" "$SRC/prerm" "$SRC/postrm" "$STAGE/DEBIAN/"
SIZE=$(du -sk --exclude=DEBIAN "$STAGE" | cut -f1)
sed -e "s/@VERSION@/$VERSION/" -e "s/@SIZE@/$SIZE/" "$SRC/control" > "$STAGE/DEBIAN/control"

DEB="$OUT/ehome_${VERSION}_all.deb"
dpkg-deb --root-owner-group -Zgzip --build "$STAGE" "$DEB"
echo "$DEB"
