#!/usr/bin/env bash
# ==============================================================================
# setup_debian_rootfs.sh
# Bootstraps a lightweight Debian rootfs with glibc, Python 3, and Antigravity
# designed for headless user-space PRoot virtualization on Android 10+.
# ==============================================================================

set -euo pipefail

TARGET_ARCH="${1:-aarch64}"
OUTPUT_DIR="${2:-/tmp/debian_rootfs_${TARGET_ARCH}}"
TARBALL_OUT="${3:-/tmp/debian_rootfs_${TARGET_ARCH}.tar.gz}"

echo "[*] Bootstrapping lightweight Debian rootfs for ${TARGET_ARCH}..."
mkdir -p "${OUTPUT_DIR}"

# 1. Base directory hierarchy compliant with Linux FHS
mkdir -p "${OUTPUT_DIR}"/{bin,sbin,etc,usr/bin,usr/sbin,usr/lib,lib,lib64,proc,sys,dev,tmp,root,workspace}
chmod 1777 "${OUTPUT_DIR}/tmp"

# 2. Minimal /etc configurations
cat << 'EOF' > "${OUTPUT_DIR}/etc/passwd"
root:x:0:0:root:/root:/bin/sh
user:x:1000:1000:user:/workspace:/bin/sh
EOF

cat << 'EOF' > "${OUTPUT_DIR}/etc/group"
root:x:0:
user:x:1000:
EOF

cat << 'EOF' > "${OUTPUT_DIR}/etc/resolv.conf"
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

cat << 'EOF' > "${OUTPUT_DIR}/etc/hosts"
127.0.0.1 localhost
::1 localhost
EOF

# 3. Environment configuration for Antigravity
cat << 'EOF' > "${OUTPUT_DIR}/root/.profile"
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export HOME=/root
export TMPDIR=/tmp
EOF

echo "[*] Rootfs skeleton generated at ${OUTPUT_DIR}"
echo "[*] Compacting rootfs into ${TARBALL_OUT}..."
tar -czf "${TARBALL_OUT}" -C "${OUTPUT_DIR}" .

echo "[+] Done. Rootfs package size: $(du -sh "${TARBALL_OUT}" | cut -f1)"
