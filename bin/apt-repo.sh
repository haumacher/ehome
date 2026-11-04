#!/bin/sh
# Builds the apt repository of E-Home (doc/debian.md "The apt repository"), the site served by GitHub
# Pages at https://haumacher.github.io/ehome/:
#
#   bin/apt-repo.sh <site dir> <suite> <package.deb>
#
# The site holds two suites, "stable" (the releases, tags vX.Y.Z) and "snapshot" (every push to
# master), each with the newest $APT_KEEP packages (default 2: a package is about 130 MB, a Pages site
# at most 1 GB). The packages already published are fetched from $APT_REPO_URL (the live site; none
# when it is empty or not reachable yet) and checked against their published SHA-256, those of stable
# from the directory $APT_STABLE_DIR instead when it is set (the workflow downloads the packages of
# the newest GitHub releases there); the new package is added to <suite>. The indexes (Packages, Release) are written with apt-ftparchive and signed with
# the GnuPG key $APT_SIGNING_KEY_ID (default: the only secret key of the keyring), its passphrase in
# $APT_SIGNING_PASSPHRASE; the public key is published beside them as ehome-archive-keyring.gpg.
set -e

SITE=$1
SUITE=$2
DEB=$3
if [ -z "$SITE" ] || [ -z "$SUITE" ] || [ ! -f "$DEB" ]; then
	echo "usage: $0 <site dir> <stable|snapshot> <package.deb>" >&2
	exit 2
fi
case "$SUITE" in
	stable|snapshot) ;;
	*) echo "$0: unknown suite $SUITE (stable or snapshot)" >&2; exit 2 ;;
esac
# The package is copied after the script changed into the site.
DEB=$(readlink -f "$DEB")
URL=${APT_REPO_URL%/}
KEEP=${APT_KEEP:-2}
ARCHES="amd64 arm64 all"

rm -rf "$SITE"
mkdir -p "$SITE"
cd "$SITE"

# The published packages of both suites. The releases of stable come from $APT_STABLE_DIR when it is
# set (the packages of the GitHub releases, the source of truth of the suite): a site built from the
# live one alone loses a release whenever a deployment of the live site did not take effect.
for suite in stable snapshot; do
	mkdir -p "pool/$suite"
	if [ "$suite" = stable ] && [ -n "$APT_STABLE_DIR" ]; then
		for deb in "$APT_STABLE_DIR"/*.deb; do
			[ -e "$deb" ] || continue
			echo "stable: release $(basename "$deb")"
			cp "$deb" pool/stable/
		done
		continue
	fi
	[ -n "$URL" ] || continue
	if ! curl -fsSL "$URL/dists/$suite/main/binary-all/Packages" -o "packages.$suite" 2>/dev/null; then
		echo "$suite: nothing published at $URL yet"
		continue
	fi
	awk '/^Filename:/ { file = $2 } /^SHA256:/ { print $2, file }' "packages.$suite" | while read -r sum file; do
		echo "$suite: fetching $file"
		curl -fsSL "$URL/$file" -o "$file"
		echo "$sum  $file" | sha256sum -c --quiet -
	done
	rm -f "packages.$suite"
done

cp "$DEB" "pool/$SUITE/"

# The newest $KEEP packages of each suite, by Debian version.
for suite in stable snapshot; do
	for deb in pool/$suite/*.deb; do
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
			echo "$suite: dropping ${entry#*|}"
			rm -f "${entry#*|}"
		fi
	done
done

KEY=${APT_SIGNING_KEY_ID:-$(gpg --batch --list-secret-keys --with-colons | awk -F: '/^sec/ { print $5; exit }')}
if [ -z "$KEY" ]; then
	echo "$0: no signing key in the keyring" >&2
	exit 1
fi
sign() {
	printf '%s' "$APT_SIGNING_PASSPHRASE" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 \
		--local-user "$KEY" "$@"
}

for suite in stable snapshot; do
	for arch in $ARCHES; do
		dir="dists/$suite/main/binary-$arch"
		mkdir -p "$dir"
		apt-ftparchive packages "pool/$suite" > "$dir/Packages"
		gzip -9 -n -k "$dir/Packages"
	done
	if [ "$suite" = stable ]; then
		label="E-Home releases"
	else
		label="E-Home snapshots of master"
	fi
	apt-ftparchive \
		-o APT::FTPArchive::Release::Origin=E-Home \
		-o "APT::FTPArchive::Release::Label=$label" \
		-o APT::FTPArchive::Release::Suite=$suite \
		-o APT::FTPArchive::Release::Codename=$suite \
		-o "APT::FTPArchive::Release::Architectures=$ARCHES" \
		-o APT::FTPArchive::Release::Components=main \
		-o "APT::FTPArchive::Release::Description=E-Home, engineering of KNX and Zigbee installations" \
		release "dists/$suite" > "dists/$suite/Release.tmp"
	mv "dists/$suite/Release.tmp" "dists/$suite/Release"
	sign --clearsign -o "dists/$suite/InRelease" "dists/$suite/Release"
	sign --armor --detach-sign -o "dists/$suite/Release.gpg" "dists/$suite/Release"
	echo "$suite: $(ls "pool/$suite" | wc -l) package(s): $(ls "pool/$suite" | tr '\n' ' ')"
done

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
<p>Two suites: <code>stable</code> holds the releases, <code>snapshot</code> a build of every change on
<code>master</code>. The repository is signed with the key <code>$FINGERPRINT</code>.</p>
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
