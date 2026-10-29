#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
协议算法离线验证脚本（与 App 内「协议层自检」等价的 Python 参考实现）。

用途：在没有真机的环境下，验证本工程协议层的算法是否正确：
  1. ADB 报文：构建 → 解析 往返，以及损坏报文的识别
  2. sparse：raw → 切段 → 合并还原 往返（大镜像分段刷写的正确性）
  3. payload.bin：构造 manifest（protobuf）→ 解析 → 解出分区 → 与原镜像比对
  4. Fastboot 响应包解析（OKAY / FAIL / DATA / INFO）

运行：python3 tools/verify_protocols.py
"""
import struct
import io
import sys
import lzma

PASSED = 0
FAILED = 0
REPORT = []


def check(name, ok, detail=""):
    global PASSED, FAILED
    if ok:
        PASSED += 1
        REPORT.append(f"✔ {name}：{detail}")
    else:
        FAILED += 1
        REPORT.append(f"✘ {name}：{detail}")


# ---------------------------------------------------------------- 1. ADB 报文
CMD_CNXN = 0x4E584E43
CMD_AUTH = 0x48545541
CMD_OPEN = 0x4E45504F
CMD_WRTE = 0x45545257


def adb_checksum(data: bytes) -> int:
    import zlib
    return zlib.crc32(data) & 0xFFFFFFFF


def adb_build(command: int, arg0: int, arg1: int, data: bytes = b"") -> bytes:
    return (struct.pack("<IIIIII", command, arg0, arg1, len(data),
                        adb_checksum(data), command ^ 0xFFFFFFFF) + data)


def adb_parse(packet: bytes):
    command, arg0, arg1, length, check, magic = struct.unpack("<IIIIII", packet[:24])
    if magic != (command ^ 0xFFFFFFFF):
        raise ValueError("magic 不匹配")
    data = packet[24:24 + length]
    if length and adb_checksum(data) != check:
        raise ValueError("CRC32 不匹配")
    return command, arg0, arg1, data


def test_adb():
    payload = b"shell:getprop\x00"
    pkt = adb_build(CMD_OPEN, 1, 0, payload)
    cmd, a0, a1, data = adb_parse(pkt)
    check("ADB 报文往返", cmd == CMD_OPEN and a0 == 1 and data == payload,
          "命令/参数/数据一致，头部 24 字节、CRC32 与 magic 校验通过")

    bad = bytearray(adb_build(CMD_WRTE, 1, 1, b"\x01\x02\x03"))
    bad[24] ^= 0xFF  # 篡改数据区首字节
    try:
        adb_parse(bytes(bad))
        check("ADB 损坏报文识别", False, "未能识别被篡改的数据")
    except ValueError:
        check("ADB 损坏报文识别", True, "篡改数据后能正确报出校验错误")


# ---------------------------------------------------------------- 2. sparse
SPARSE_MAGIC = 0xED26FF3A
FILE_HDR_SZ = 28
CHUNK_HDR_SZ = 12
TYPE_RAW, TYPE_FILL, TYPE_DONT_CARE = 0xCAC1, 0xCAC2, 0xCAC3


def sparse_build(data: bytes, block_size: int = 4096, max_segment: int = None):
    """把 raw 数据切成若干 sparse 段（与 SparseSplitter 同一算法）。"""
    segments = []
    pos = 0
    total_blocks = (len(data) + block_size - 1) // block_size
    budget = (max_segment or len(data)) - FILE_HDR_SZ - CHUNK_HDR_SZ
    while pos < len(data):
        seg = min(budget, len(data) - pos)
        seg = (seg // block_size) * block_size or (len(data) - pos)
        chunk = data[pos:pos + seg]
        hdr = struct.pack("<IHHHHIIII", SPARSE_MAGIC, 1, 0, FILE_HDR_SZ, CHUNK_HDR_SZ,
                          block_size, total_blocks, 1, 0)
        blocks = (len(chunk) + block_size - 1) // block_size
        ch = struct.pack("<HHII", TYPE_RAW, 0, blocks, CHUNK_HDR_SZ + len(chunk))
        segments.append(hdr + ch + chunk)
        pos += len(chunk)
        if not chunk:
            break
    return segments


def sparse_to_raw(stream: io.BytesIO) -> bytes:
    """sparse → raw（与 SparseImage.toRaw 同一算法）。"""
    out = bytearray()
    hdr = stream.read(FILE_HDR_SZ)
    if len(hdr) < FILE_HDR_SZ:
        return bytes(out)
    magic, _, _, _, _, blk_sz, _, total_chunks, _ = struct.unpack("<IHHHHIIII", hdr)
    assert magic == SPARSE_MAGIC, "不是 sparse 镜像"
    for _ in range(total_chunks):
        ch = stream.read(CHUNK_HDR_SZ)
        if len(ch) < CHUNK_HDR_SZ:
            break
        ctype, _, chunk_blocks, total_sz = struct.unpack("<HHII", ch)
        data_len = total_sz - CHUNK_HDR_SZ
        payload = stream.read(data_len)
        if ctype == TYPE_RAW:
            out += payload
            pad = chunk_blocks * blk_sz - len(payload)
            if pad > 0:
                out += b"\x00" * pad
        elif ctype == TYPE_FILL:
            fill = payload[:4]
            out += (fill * (blk_sz // 4))[:chunk_blocks * blk_sz]
        elif ctype == TYPE_DONT_CARE:
            out += b"\x00" * (chunk_blocks * blk_sz)
        else:
            raise ValueError(f"未知 chunk 类型 0x{ctype:X}")
    return bytes(out)


def test_sparse():
    original = bytes((i * 7 + 3) % 251 for i in range(3 * 1024 * 1024 + 1234))
    segments = sparse_build(original, 4096, 700 * 1024)
    merged = bytearray()
    for seg in segments:
        merged += sparse_to_raw(io.BytesIO(seg))
    # sparse 按块对齐，合并结果会向上补齐到 block_size 的整数倍（与 simg2img 行为一致）
    aligned = ((len(original) + 4095) // 4096) * 4096
    check("sparse 切段与还原",
          len(segments) > 1 and len(merged) == aligned and bytes(merged[:len(original)]) == original,
          f"{len(original)} 字节切成 {len(segments)} 段再合并，有效数据逐字节一致"
          f"（总长按块对齐到 {aligned} 字节，尾部补零，与 simg2img 行为一致）")


# ------------------------------------------------------- 3. payload.bin
def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def tag(field: int, wire: int) -> bytes:
    return varint((field << 3) | wire)


def ld(field: int, payload: bytes) -> bytes:
    return tag(field, 2) + varint(len(payload)) + payload


def varint_field(field: int, value: int) -> bytes:
    return tag(field, 0) + varint(value)


def read_varint(buf: bytes, pos: int):
    result = 0
    shift = 0
    while pos < len(buf):
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7
    raise ValueError("varint 截断")


def parse_fields(buf: bytes):
    pos = 0
    while pos < len(buf):
        key, pos = read_varint(buf, pos)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, pos = read_varint(buf, pos)
            yield number, wire, value, None
        elif wire == 2:
            length, pos = read_varint(buf, pos)
            yield number, wire, 0, buf[pos:pos + length]
            pos += length
        else:
            raise ValueError(f"不支持的 wire 类型 {wire}")


REPLACE, REPLACE_XZ, ZERO = 0, 8, 6


def build_payload(partition: str, image: bytes, block_size: int = 4096,
                  compressed: bool = False) -> bytes:
    blob = lzma.compress(image, format=lzma.FORMAT_XZ) if compressed else image
    op_type = REPLACE_XZ if compressed else REPLACE
    extent = varint_field(1, 0) + varint_field(2, len(image) // block_size)
    op = (varint_field(1, op_type) + varint_field(2, 0) + varint_field(3, len(blob))
          + ld(6, extent))
    part = ld(1, partition.encode()) + ld(8, op)
    manifest = varint_field(3, block_size) + ld(13, part)
    header = b"CrAU" + struct.pack(">QQI", 2, len(manifest), 0)
    return header + manifest + blob


def dump_payload(payload: bytes):
    magic = payload[:4]
    assert magic == b"CrAU", "不是 payload.bin"
    version, manifest_size, sig_size = struct.unpack(">QQI", payload[4:24])
    assert version == 2, f"不支持的版本 {version}"
    manifest = payload[24:24 + manifest_size]
    data_start = 24 + manifest_size + sig_size
    block_size = 4096
    result = {}
    for number, wire, value, raw in parse_fields(manifest):
        if number == 3:
            block_size = value
        elif number == 13:
            name, ops = None, []
            for pn, pw, pv, praw in parse_fields(raw):
                if pn == 1:
                    name = praw.decode()
                elif pn == 8:
                    otype = ooff = olen = 0
                    extents = []
                    for on, ow, ov, oraw in parse_fields(praw):
                        if on == 1:
                            otype = ov
                        elif on == 2:
                            ooff = ov
                        elif on == 3:
                            olen = ov
                        elif on == 6:
                            start = blocks = 0
                            for en, ew, ev, eraw in parse_fields(oraw):
                                if en == 1:
                                    start = ev
                                elif en == 2:
                                    blocks = ev
                            extents.append((start, blocks))
                    ops.append((otype, ooff, olen, extents))
            out = bytearray()
            for otype, ooff, olen, extents in ops:
                blob = payload[data_start + ooff: data_start + ooff + olen]
                if otype == REPLACE_XZ:
                    blob = lzma.decompress(blob)
                elif otype == ZERO:
                    blob = b"\x00" * sum(b for _, b in extents) * block_size
                for start, blocks in extents:
                    need = blocks * block_size
                    if len(out) < start * block_size:
                        out += b"\x00" * (start * block_size - len(out))
                    out[start * block_size:start * block_size + need] = blob[:need]
                    blob = blob[need:]
            result[name] = bytes(out)
    return result


def test_payload():
    image = bytes((i * 5 + 11) % 233 for i in range(512 * 1024))
    for compressed, label in ((False, "REPLACE"), (True, "REPLACE_XZ")):
        payload = build_payload("boot", image, 4096, compressed)
        got = dump_payload(payload)
        ok = got.get("boot") == image
        check(f"payload.bin 解包（{label}）", ok,
              f"{len(image)} 字节分区镜像解出后与原镜像一致")


# ------------------------------------------------- 4. Fastboot 响应包解析
def parse_fastboot_packet(packet: bytes):
    type_ = packet[:4].decode("ascii", "ignore")
    payload = packet[4:].split(b"\x00")[0].decode("ascii", "ignore").strip()
    return type_, payload


def test_fastboot():
    def pkt(t, p):
        return (t.encode() + p.encode()).ljust(64, b"\x00")

    ok = (parse_fastboot_packet(pkt("OKAY", ""))[0] == "OKAY"
          and parse_fastboot_packet(pkt("FAIL", "partition not found"))[1] == "partition not found"
          and parse_fastboot_packet(pkt("DATA", "0x08000000"))[0] == "DATA"
          and parse_fastboot_packet(pkt("INFO", "erasing"))[1] == "erasing")
    check("Fastboot 响应解析", ok, "OKAY / FAIL / DATA / INFO 四类响应解析正确")


def main():
    test_adb()
    test_sparse()
    test_payload()
    test_fastboot()
    print("\n".join(REPORT))
    print(f"\n结果：通过 {PASSED} 项，失败 {FAILED} 项。")
    return 0 if FAILED == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
