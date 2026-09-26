#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MP4 产物自检：不用 ffmpeg，直接解析 ISO-BMFF 盒子。

输出重点：
  * mvhd 总时长（判断 "00:00" / 天文数字）
  * 每条轨道：handler、编解码器、分辨率/声道采样率、样本数、时长、fps
  * 音轨 AAC 帧大小统计 —— 这是判断"有没有真的录到声音"的无依赖手段：
      纯静音 AAC 帧极小且几乎恒定；有内容的音频帧大小分布很散。
"""
import struct, sys, os

CONTAINERS = {b'moov', b'trak', b'mdia', b'minf', b'stbl', b'edts', b'udta'}


def boxes(data, start, end):
    p = start
    while p + 8 <= end:
        size = struct.unpack('>I', data[p:p + 4])[0]
        typ = data[p + 4:p + 8]
        hdr = 8
        if size == 1:
            if p + 16 > end:
                return
            size = struct.unpack('>Q', data[p + 8:p + 16])[0]
            hdr = 16
        elif size == 0:
            size = end - p
        if size < hdr or p + size > end:
            return
        yield typ, p + hdr, p + size
        p += size


def find(data, start, end, path):
    """按路径查找第一个盒子，返回 (payload_start, payload_end)。"""
    cur = [(start, end)]
    for want in path:
        nxt = []
        for (s, e) in cur:
            for typ, ps, pe in boxes(data, s, e):
                if typ == want:
                    nxt.append((ps, pe))
        if not nxt:
            return None
        cur = nxt
    return cur[0]


def all_of(data, start, end, path):
    cur = [(start, end)]
    for want in path:
        nxt = []
        for (s, e) in cur:
            for typ, ps, pe in boxes(data, s, e):
                if typ == want:
                    nxt.append((ps, pe))
        cur = nxt
    return cur


def full_boxes(data, s, e, typ):
    return [(ps, pe) for t, ps, pe in boxes(data, s, e) if t == typ]


def u32(b, o):
    return struct.unpack('>I', b[o:o + 4])[0]


def u64(b, o):
    return struct.unpack('>Q', b[o:o + 8])[0]


def parse(path):
    with open(path, 'rb') as f:
        data = f.read()
    n = len(data)
    print("文件: %s" % os.path.basename(path))
    print("大小: %.2f MB (%d 字节)" % (n / 1048576.0, n))

    mvhd = find(data, 0, n, [b'moov', b'mvhd'])
    if mvhd:
        b, o = data, mvhd[0]
        ver = b[o]
        if ver == 1:
            ts = u32(b, o + 20); dur = u64(b, o + 24)
        else:
            ts = u32(b, o + 12); dur = u32(b, o + 16)
        print("总时长(mvhd): %.3f s   [timescale=%d duration=%d]" % (dur / float(ts), ts, dur))

    traks = all_of(data, 0, n, [b'moov', b'trak'])
    print("轨道数: %d" % len(traks))
    for i, (ts_, te_) in enumerate(traks):
        hdlr = find(data, ts_, te_, [b'mdia', b'hdlr'])
        handler = '?'
        if hdlr:
            handler = data[hdlr[0] + 8:hdlr[0] + 12].decode('latin1').strip()
        mdhd = find(data, ts_, te_, [b'mdia', b'mdhd'])
        mts = mdur = 0
        if mdhd:
            o = mdhd[0]
            if data[o] == 1:
                mts = u32(data, o + 20); mdur = u64(data, o + 24)
            else:
                mts = u32(data, o + 12); mdur = u32(data, o + 16)
        stsd = find(data, ts_, te_, [b'mdia', b'minf', b'stbl', b'stsd'])
        codec = '?'
        extra = ''
        if stsd:
            o = stsd[0] + 8           # version/flags + entry_count
            fmt = data[o + 4:o + 8].decode('latin1')
            codec = fmt
            if handler == 'vide' and fmt in ('avc1', 'hvc1', 'hev1'):
                w = struct.unpack('>H', data[o + 32:o + 34])[0]
                h = struct.unpack('>H', data[o + 34:o + 36])[0]
                extra = "%dx%d" % (w, h)
            elif handler == 'soun':
                ch = struct.unpack('>H', data[o + 24:o + 26])[0]
                sr = struct.unpack('>I', data[o + 32:o + 36])[0] >> 16
                extra = "%d ch, %d Hz" % (ch, sr)
        stsz = find(data, ts_, te_, [b'mdia', b'minf', b'stbl', b'stsz'])
        sizes = []
        if stsz:
            o = stsz[0]
            fixed = u32(data, o + 4)
            cnt = u32(data, o + 8)
            if fixed:
                sizes = [fixed] * cnt
            else:
                sizes = list(struct.unpack('>%dI' % cnt, data[o + 12:o + 12 + 4 * cnt]))
        stts = find(data, ts_, te_, [b'mdia', b'minf', b'stbl', b'stts'])
        dur_units = 0
        deltas = []
        if stts:
            o = stts[0]
            ec = u32(data, o + 4)
            for k in range(ec):
                c = u32(data, o + 8 + k * 8)
                d = u32(data, o + 12 + k * 8)
                dur_units += c * d
                if len(deltas) < 40:
                    deltas.append((c, d))
        secs = dur_units / float(mts) if mts else 0.0
        print("\n  [轨道 %d] handler=%s codec=%s %s" % (i, handler, codec, extra))
        print("    样本数=%d  时长=%.3f s  timescale=%d" % (len(sizes), secs, mts))
        if secs > 0:
            print("    平均 %.2f 样本/秒" % (len(sizes) / secs))
        if sizes and handler == 'soun':
            avg = sum(sizes) / float(len(sizes))
            mn, mx = min(sizes), max(sizes)
            var = sum((x - avg) ** 2 for x in sizes) / float(len(sizes))
            print("    AAC 帧字节: 平均=%.1f 最小=%d 最大=%d 标准差=%.1f" % (avg, mn, mx, var ** 0.5))
            if avg < 40 and (mx - mn) < 30:
                print("    >>> 判定: 音轨基本是静音（没有捕获到目标应用的声音）")
            else:
                print("    >>> 判定: 音轨有实际内容（录到了声音）")
        if deltas:
            nz = [(d, c) for (c, d) in deltas if d > 0]
            nz.sort(key=lambda x: -x[1])
            print("    帧间隔(时间基单位) 前几种: " + ", ".join(
                "%d x%d(%.2fms)" % (d, c, d * 1000.0 / mts) for d, c in nz[:5]))
        if sizes and handler == 'vide':
            hmm = [s for s in sizes]
            print("    视频帧字节: 平均=%.1f 最小=%d 最大=%d" % (
                sum(hmm) / float(len(hmm)), min(hmm), max(hmm)))
            print("    视频平均帧率: %.1f fps" % (len(hmm) / secs if secs else 0))

    # AVC 配置
    i = data.find(b'avcC')
    if i > 0:
        cfg = data[i + 4:i + 4 + 4]
        prof, compat, lev = cfg[0], cfg[1], cfg[2]
        lv = {0x1e: '3.0', 0x1f: '3.1', 0x20: '3.2', 0x28: '4.0', 0x29: '4.1',
              0x2a: '4.2', 0x32: '5.0', 0x33: '5.1', 0x34: '5.2'}.get(lev, hex(lev))
        print("\nAVC 配置(avcC): profile=%d compat=0x%02x level=0x%02x(%s)" % (prof, compat, lev, lv))
        n_sps = data[i + 9] & 0x1F
        p = i + 10
        sps_len = struct.unpack('>H', data[p:p + 2])[0]
        print("  SPS 数量=%d, 长度=%d 字节 -> %s" % (
            n_sps, sps_len, "SPS/PPS 已写入（可独立解码）" if sps_len > 0 else "缺失"))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法: mp4probe.py <file.mp4>")
        sys.exit(2)
    for p in sys.argv[1:]:
        parse(p)
        print("\n" + "-" * 60)
