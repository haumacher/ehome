#!/bin/sh
# Builds the apt repository of E-Home (doc/debian.md "The apt repository"), the site served by GitHub
# Pages at https://haumacher.github.io/ehome/:
#
#   bin/apt-repo.sh <site dir> <release dir>
#
# The site holds one suite, "stable": the newest $APT_KEEP releases (default 2; a package is about
# 160 MB, a Pages site at most 1 GB). The packages are the .deb files of <release dir> (the workflow
# downloads the packages of the newest GitHub releases there, the releases being the source of truth;
# a release made by the same run is among them). The indexes (Packages, Release) are written with
# apt-ftparchive and signed with the GnuPG key $APT_SIGNING_KEY_ID (default: the only secret key of
# the keyring), its passphrase in $APT_SIGNING_PASSPHRASE; the public key is published beside them as
# ehome-archive-keyring.gpg. There is no suite of master's builds (dropped 2026-11-13: every push
# rebuilt and uploaded the whole site, about 630 MB); a build of master is an artifact of its run.
set -e
SITE=$1
RELEASES=$2
if [ -z "$SITE" ] || [ ! -d "$RELEASES" ]; then
	echo "usage: $0 <site dir> <release dir>" >&2
	exit 2
fi
RELEASES=$(readlink -f "$RELEASES")
URL=${APT_REPO_URL%/}
KEEP=${APT_KEEP:-2}
ARCHES="amd64 arm64 all"
SUITE=stable
rm -rf "$SITE"
mkdir -p "$SITE/pool/$SUITE"
cd "$SITE"
for deb in "$RELEASES"/*.deb; do
	[ -e "$deb" ] || continue
	echo "$SUITE: release $(basename "$deb")"
	cp "$deb" "pool/$SUITE/"
done

# The newest $KEEP packages, by Debian version.
for deb in pool/$SUITE/*.deb; do
	[ -e "$deb" ] || continue
	echo "$(dpkg-deb -f "$deb" Version) $deb"
done > versions.txt
# Newest first, in dpkg's order (insertion with dpkg --compare-versions).
sorted=""
while read -r version deb; do
	placed=""
	new=""
	for entry in $sorted; do
		v=${entry%%|*}
		if [ -z "$placed" ] && dpkg --compare-versions "$version" gt "$v"; then
			new="$new $version|$deb"
			placed=1
		fi
		new="$new $entry"
	done
	[ -n "$placed" ] || new="$new $version|$deb"
	sorted=$new
done < versions.txt
rm -f versions.txt
count=0
for entry in $sorted; do
	count=$((count + 1))
	if [ "$count" -gt "$KEEP" ]; then
		echo "$SUITE: dropping ${entry#*|}"
		rm -f "${entry#*|}"
	fi
done
if [ "$count" -eq 0 ]; then
	echo "$0: no release package in $RELEASES" >&2
	exit 1
fi

KEY=${APT_SIGNING_KEY_ID:-$(gpg --batch --list-secret-keys --with-colons | awk -F: '/^sec/ { print $5; exit }')}
if [ -z "$KEY" ]; then
	echo "$0: no signing key in the keyring" >&2
	exit 1
fi
sign() {
	printf '%s' "$APT_SIGNING_PASSPHRASE" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 \
		--local-user "$KEY" "$@"
}

for arch in $ARCHES; do
	dir="dists/$SUITE/main/binary-$arch"
	mkdir -p "$dir"
	apt-ftparchive packages "pool/$SUITE" > "$dir/Packages"
	gzip -9 -n -k "$dir/Packages"
done
apt-ftparchive \
	-o APT::FTPArchive::Release::Origin=E-Home \
	-o "APT::FTPArchive::Release::Label=E-Home releases" \
	-o APT::FTPArchive::Release::Suite=$SUITE \
	-o APT::FTPArchive::Release::Codename=$SUITE \
	-o "APT::FTPArchive::Release::Architectures=$ARCHES" \
	-o APT::FTPArchive::Release::Components=main \
	-o "APT::FTPArchive::Release::Description=E-Home, engineering of KNX and Zigbee installations" \
	release "dists/$SUITE" > "dists/$SUITE/Release.tmp"
mv "dists/$SUITE/Release.tmp" "dists/$SUITE/Release"
sign --clearsign -o "dists/$SUITE/InRelease" "dists/$SUITE/Release"
sign --armor --detach-sign -o "dists/$SUITE/Release.gpg" "dists/$SUITE/Release"
echo "$SUITE: $(ls "pool/$SUITE" | wc -l) package(s): $(ls "pool/$SUITE" | tr '\n' ' ')"

gpg --batch --export "$KEY" > ehome-archive-keyring.gpg
gpg --batch --armor --export "$KEY" > ehome-archive-keyring.asc
FINGERPRINT=$(gpg --batch --with-colons --fingerprint "$KEY" | awk -F: '/^fpr/ { print $10; exit }')

PAGE=${URL:-https://haumacher.github.io/ehome}
cat > index.html <<END
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>E-Home apt repository</title>
<style>
body { font-family: system-ui, sans-serif; max-width: 52rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }
pre { background: #f3f3f3; padding: .75rem; overflow-x: auto; }
code { font-size: .95em; }
</style>
</head>
<body>
<h1>E-Home apt repository</h1>
<p>Debian packages of <a href="https://github.com/haumacher/ehome">E-Home</a> for Debian 13 (trixie) and
Ubuntu 24.04. The package runs E-Home in the distribution's Tomcat 10; see
<a href="https://github.com/haumacher/ehome/blob/master/doc/debian.md">doc/debian.md</a>.</p>
<p>The suite <code>stable</code> holds the newest releases. The repository is signed with the key
<code>$FINGERPRINT</code>.</p>
<pre>sudo curl -fsSLo /usr/share/keyrings/ehome-archive-keyring.gpg $PAGE/ehome-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/ehome-archive-keyring.gpg] $PAGE stable main" \\
  | sudo tee /etc/apt/sources.list.d/ehome.list
sudo apt update
sudo apt install ehome</pre>
<p>An upgrade (<code>sudo apt upgrade</code>) stops the app, not Tomcat, backs up the database to
<code>/var/lib/ehome/backup/</code> and deploys the new version.</p>
</body>
</html>
END
touch .nojekyll
echo "site: $(du -sh . | cut -f1) in $SITE, signed by $FINGERPRINT"
