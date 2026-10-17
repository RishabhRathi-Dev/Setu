#!/usr/bin/env bash
# A private ext4 journal over a fully written disk image; never a RAM filesystem.
set -euo pipefail

parent=$(realpath "${RUNNER_TEMP:?RUNNER_TEMP is required}")
work=$(mktemp -d "$parent/setu-volume-XXXXXX")
image="$work/data.img"
mountpoint="$work/data"
mkdir "$mountpoint"
device=
cleanup() {
    if mountpoint -q "$mountpoint"; then sudo umount "$mountpoint"; fi
    if [[ -n "$device" ]]; then sudo losetup --detach "$device"; fi
}
trap cleanup EXIT

# Writing every block also completes backing-file extent allocation before load.
# Sparse allocation would move that work back into measured durability barriers.
dd if=/dev/zero of="$image" bs=8M count=1024 conv=fsync status=none
mkfs.ext4 -q -F -m 0 -E lazy_itable_init=0,lazy_journal_init=0,nodiscard "$image"
device=$(sudo losetup --find --show --direct-io=on "$image")
sudo mount -t ext4 -o data=ordered,barrier=1,max_batch_time=0 "$device" "$mountpoint"
sudo chown "$(id -u):$(id -g)" "$mountpoint"
findmnt -T "$parent" -o SOURCE,FSTYPE,OPTIONS
findmnt -T "$mountpoint" -o SOURCE,FSTYPE,OPTIONS
sudo losetup --list --output NAME,BACK-FILE,DIO "$device"
[[ $(sudo losetup --noheadings --output DIO "$device" | tr -d ' ') == 1 ]]
printf 'SETU_BENCHMARK_VOLUME=%s\nSETU_BENCHMARK_LOOP=%s\n' "$mountpoint" "$device" >> "${GITHUB_ENV:?GITHUB_ENV is required}"
trap - EXIT
