#!/bin/bash
# Build Mesa 26.2.4 for aarch64 with scripts/mesa/0001-egl-wayland-keep-zink-on-shm.patch.
# Runs under qemu-user. Writes $CACHE/mesa-zink.tar.xz and prints its sha256.
set -euo pipefail

CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/oma-mesa-build"
ROOT="$CACHE/root"
SRC="${MESA_SRC:-/tmp/mesa-26.2.4}"
PATCH="$(cd "$(dirname "$0")" && pwd)/0001-egl-wayland-keep-zink-on-shm.patch"
BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
mkdir -p "$CACHE"
exec > >(tee -a "$CACHE/build.log") 2>&1

echo "mesa build start $(date -Is)"
while [ ! -f "$SRC/meson.build" ]; do
  if ! pgrep -f 'git clone --depth 1 --branch mesa-26.2.4' >/dev/null; then
    echo "mesa source missing at $SRC" >&2
    exit 1
  fi
  sleep 5
done

if ! grep -q 'disp->Options.ForceSoftware || disp->Options.Zink' "$SRC/src/egl/drivers/dri2/platform_wayland.c"; then
  patch -p1 -d "$SRC" < "$PATCH"
fi

if [ ! -x "$ROOT/bin/bash" ]; then
  mkdir -p "$ROOT"
  curl -fL --retry 3 -o "$CACHE/ubuntu-base.tar.gz" "$BASE_URL"
  tar -C "$ROOT" -xzf "$CACHE/ubuntu-base.tar.gz"
fi
cp /etc/resolv.conf "$ROOT/etc/resolv.conf"
mkdir -p "$ROOT/usr/sbin" "$ROOT/proc" "$ROOT/sys" "$ROOT/dev" "$ROOT/mesa" "$ROOT/dest" "$ROOT/etc/apt/apt.conf.d"
# The user namespace only maps root, so apt cannot drop to the _apt user.
printf 'APT::Sandbox::User "root";\n' > "$ROOT/etc/apt/apt.conf.d/99-no-sandbox"
printf '#!/bin/sh\nexit 101\n' > "$ROOT/usr/sbin/policy-rc.d"
chmod +x "$ROOT/usr/sbin/policy-rc.d"

cat > "$ROOT/build-inner.sh" << 'EOF'
set -eux
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  build-essential ninja-build pkg-config python3-mako python3-yaml python3-packaging python3-pip \
  flex bison cmake \
  libwayland-dev libdrm-dev \
  libx11-dev libxext-dev libxfixes-dev libxrandr-dev libxrender-dev \
  libxcb1-dev libx11-xcb-dev libxcb-glx0-dev libxcb-dri3-dev \
  libxcb-present-dev libxcb-shm0-dev libxcb-xfixes0-dev \
  libelf-dev zlib1g-dev libexpat1-dev libffi-dev libglvnd-dev
cd /mesa
pip3 install --break-system-packages 'meson>=1.4.0'
hash -r
meson --version
# Ubuntu 24.04's Wayland has no wayland-egl-backend.pc. Mesa 26.2 requires it.
if ! pkg-config --exists 'wayland-egl-backend >= 3'; then
  curl -fL --retry 3 -o /tmp/wayland.tar.xz \
    https://gitlab.freedesktop.org/wayland/wayland/-/releases/1.24.0/downloads/wayland-1.24.0.tar.xz
  rm -rf /tmp/wayland-src /tmp/wayland-build
  mkdir -p /tmp/wayland-src
  tar -C /tmp/wayland-src --strip-components=1 -xJf /tmp/wayland.tar.xz
  meson setup /tmp/wayland-build /tmp/wayland-src \
    --prefix=/usr --libdir=lib/aarch64-linux-gnu \
    -Ddocumentation=false -Ddtd_validation=false -Dtests=false
  ninja -C /tmp/wayland-build install
fi
rm -rf /build
meson setup /build \
  --prefix=/usr/local/omalauncher-mesa \
  --libdir=lib \
  -Dbuildtype=release \
  -Db_ndebug=true \
  -Dplatforms=wayland \
  -Dgallium-drivers=zink \
  -Dvulkan-drivers=[] \
  -Degl=enabled \
  -Dglvnd=enabled \
  -Dglx=disabled \
  -Dllvm=disabled \
  -Dgles1=disabled \
  -Dgles2=enabled \
  -Dshared-glapi=enabled \
  -Dgbm=disabled \
  -Dtools=[] \
  -Dvideo-codecs=[] \
  -Dxmlconfig=disabled
ninja -C /build -j4
rm -rf /dest
DESTDIR=/dest ninja -C /build install
find /dest -type f \( -name '*.so' -o -name '*.so.*' \) -exec strip --strip-unneeded {} +
tar -C /dest -cJf /mesa-zink.tar.xz usr/local/omalauncher-mesa
sha256sum /mesa-zink.tar.xz
sha256sum /dest/usr/local/omalauncher-mesa/lib/libEGL_mesa.so
find /dest/usr/local/omalauncher-mesa -type f | sort
EOF
chmod +x "$ROOT/build-inner.sh"

# A new pid namespace is required before a user namespace may mount proc.
unshare -U -r -m -p -f --propagation private bash -c "
set -euo pipefail
mount --bind '$ROOT' '$ROOT'
mount -t proc proc '$ROOT/proc'
mount -t sysfs sysfs '$ROOT/sys' || true
mount --rbind /dev '$ROOT/dev'
mount --bind '$SRC' '$ROOT/mesa'
chroot '$ROOT' bash /build-inner.sh
"
cp "$ROOT/mesa-zink.tar.xz" "$CACHE/mesa-zink.tar.xz"
echo "mesa build done $(date -Is)"
sha256sum "$CACHE/mesa-zink.tar.xz"
